package dispatch.worker;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import dispatch.Json;
import dispatch.Log;
import dispatch.config.Config;
import dispatch.core.Groups;
import dispatch.store.Workers;
import dispatch.ui.ApiException;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Executors;

/**
 * Where members' computers reach this machine: a second HTTP server on 127.0.0.1:&lt;workers.port&gt;, behind the owner's
 * tunnel or reverse proxy. It shares nothing with {@code dispatch ui} but the jar and {@link ApiException} — no cookies,
 * no pages, no session: every request but pairing carries a worker key, which names the member whose jobs that worker
 * may take.
 */
public final class WorkerApi implements AutoCloseable {

    public static final String PAIR = "/api/worker/pair";
    public static final String NEXT = "/api/worker/next";
    public static final String PROGRESS = "/api/worker/progress";
    public static final String ATTACHMENT = "/api/worker/attachment";
    public static final String RESULT = "/api/worker/result";
    public static final String PROJECTS = "/api/worker/projects";

    private static final int MAX_BODY = 64 * 1024;
    private static final String WRONG_KEY =
            "this worker key is not valid any more: run dispatch worker pair again with a new code from /worker";

    private final HttpServer server;
    private final Config config;
    private final Groups groups;
    private final WorkerKeys keys;
    private final Set<String> hosts;

    private WorkerApi(HttpServer server, Config config, Groups groups, WorkerKeys keys) {
        this.server = server;
        this.config = config;
        this.groups = groups;
        this.keys = keys;
        this.hosts = allowedHosts(config.workers(), server.getAddress().getPort());
    }

    /** @param config must have a {@code workers} block; {@code port} 0 takes any free port, as tests do */
    public static WorkerApi start(Config config, Groups groups, WorkerKeys keys) throws IOException {
        HttpServer http = HttpServer.create(
                new InetSocketAddress(InetAddress.getByName("127.0.0.1"), config.workers().port()), 0);
        WorkerApi api = new WorkerApi(http, config, groups, keys);
        http.createContext("/", api::handle);
        http.setExecutor(Executors.newVirtualThreadPerTaskExecutor());
        http.start();
        Log.info("worker_api.started", "port", api.port(), "public_url", config.workers().publicUrl());
        return api;
    }

    public int port() {
        return server.getAddress().getPort();
    }

    @Override
    public void close() {
        server.stop(0);
    }

    /**
     * A reverse proxy passes the name members use; a worker on the same machine uses the loopback address. Anything else
     * is a request that was not meant for this server.
     */
    private static Set<String> allowedHosts(Config.Workers workers, int port) {
        Set<String> hosts = new HashSet<>(Set.of("127.0.0.1:" + port, "localhost:" + port));
        URI url = URI.create(workers.publicUrl());
        if (url.getHost() != null) {
            hosts.add(url.getPort() < 0 ? url.getHost() : url.getHost() + ":" + url.getPort());
            hosts.add(url.getHost());
        }
        return Set.copyOf(hosts);
    }

    private void handle(HttpExchange exchange) throws IOException {
        try (exchange) {
            try {
                respond(exchange);
            } catch (ApiException e) {
                json(exchange, e.status(), error(e.code(), e.getMessage()));
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                json(exchange, 503, error("stopping", "Dispatch is stopping"));
            } catch (RuntimeException e) {
                Log.error("worker_api.failed", e, "path", exchange.getRequestURI().getPath());
                json(exchange, 500, error("internal", "something went wrong on the team machine"));
            }
        }
    }

    private void respond(HttpExchange exchange) throws IOException, InterruptedException {
        exchange.getResponseHeaders().set("X-Content-Type-Options", "nosniff");
        exchange.getResponseHeaders().set("Cache-Control", "no-store");
        if (!hosts.contains(hostOf(exchange))) {
            throw new ApiException(403, "host", "this request was not addressed to this Dispatch");
        }
        String path = exchange.getRequestURI().getPath();
        if (!Set.of(PAIR, NEXT, PROGRESS, ATTACHMENT, RESULT, PROJECTS).contains(path)) {
            throw new ApiException(404, "not_found", "no such worker API: " + path);
        }
        if (!exchange.getRequestMethod().equals("POST")) {
            throw new ApiException(405, "method", path + " only answers POST");
        }
        JsonNode body = body(exchange);
        if (path.equals(PAIR)) {
            json(exchange, 200, Json.write(pair(body)));
            return;
        }
        Workers.Paired worker = authenticate(exchange);
        route(exchange, path, worker, body);
    }

    /** The protocol routes join this switch in the next task. */
    private void route(HttpExchange exchange, String path, Workers.Paired worker, JsonNode body) throws IOException,
            InterruptedException {
        if (path.equals(PROJECTS)) {
            json(exchange, 200, Json.write(projects(worker)));
            return;
        }
        throw new ApiException(404, "not_found", "no such worker API: " + path);
    }

    private Workers.Paired authenticate(HttpExchange exchange) {
        String header = exchange.getRequestHeaders().getFirst("Authorization");
        String key = header != null && header.startsWith("Bearer ") ? header.substring("Bearer ".length()).strip() : null;
        // Unknown and revoked answer alike, so nobody can learn which keys ever existed.
        return keys.authenticate(key).orElseThrow(() -> new ApiException(401, "unauthorized", WRONG_KEY));
    }

    private ObjectNode pair(JsonNode body) {
        String code = body.path("code").asText("");
        String name = body.path("name").asText("").strip();
        if (name.isEmpty() || name.length() > 40) {
            throw new ApiException(400, "invalid", "name: required, at most 40 characters");
        }
        WorkerKeys.NewKey paired = keys.pair(code, name)
                .orElseThrow(() -> new ApiException(401, "pairing_code",
                        "this pairing code is unknown, used already or older than "
                                + WorkerKeys.CODE_LIFETIME.toMinutes() + " minutes; ask the bot for a new one with /worker"));
        return Json.object().put("workerId", paired.workerId()).put("key", paired.key()).put("team", config.team());
    }

    /** What a worker needs before it can run anything: the team's projects as configured, and the commit author. */
    private ObjectNode projects(Workers.Paired worker) {
        Set<String> mine = groups.projectsOfMember(worker.memberRef());
        ObjectNode answer = Json.object().put("team", config.team())
                .put("authorName", config.delivery().authorName())
                .put("authorEmail", config.delivery().authorEmail());
        ArrayNode projects = answer.putArray("projects");
        for (Config.Project project : config.projects()) {
            if (!mine.contains(project.name())) {
                continue;
            }
            projects.addObject().put("name", project.name()).put("repo", project.repo())
                    .put("baseBranch", project.baseBranch()).put("model", project.model()).put("effort", project.effort());
        }
        return answer;
    }

    private static String hostOf(HttpExchange exchange) {
        String host = exchange.getRequestHeaders().getFirst("Host");
        return host == null ? "" : host.strip();
    }

    private static JsonNode body(HttpExchange exchange) throws IOException {
        byte[] raw = exchange.getRequestBody().readNBytes(MAX_BODY + 1);
        if (raw.length > MAX_BODY) {
            // Drain what is left (up to 1 MiB) so the JDK client sees the 413 instead of a reset connection.
            exchange.getRequestBody().readNBytes(1024 * 1024 - raw.length);
            throw new ApiException(413, "too_large", "the request is larger than " + MAX_BODY / 1024 + " KiB");
        }
        try {
            return raw.length == 0 ? Json.object() : Json.MAPPER.readTree(raw);
        } catch (JsonProcessingException e) {
            throw new ApiException(400, "invalid", "the request is not JSON");
        }
    }

    static String error(String code, String message) {
        return Json.write(Map.of("error", code, "message", message));
    }

    static void json(HttpExchange exchange, int status, String body) throws IOException {
        exchange.getResponseHeaders().set("Content-Type", "application/json; charset=utf-8");
        send(exchange, status, body.getBytes(StandardCharsets.UTF_8));
    }

    static void send(HttpExchange exchange, int status, byte[] body) throws IOException {
        exchange.sendResponseHeaders(status, body.length);
        try (OutputStream out = exchange.getResponseBody()) {
            out.write(body);
        }
    }
}
