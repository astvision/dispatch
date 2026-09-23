package dispatch.worker;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import dispatch.Json;
import dispatch.Log;
import dispatch.OwnerOnly;
import dispatch.config.Config;
import dispatch.core.AttachmentSource;
import dispatch.core.Groups;
import dispatch.core.Job;
import dispatch.core.JobResult;
import dispatch.domain.Attachment;
import dispatch.store.Database;
import dispatch.store.Workers;
import dispatch.telegram.TelegramNames;
import dispatch.ui.ApiException;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Where members' computers reach this machine: a second HTTP server on 127.0.0.1:&lt;workers.port&gt;, behind the owner's
 * tunnel or reverse proxy. It shares nothing with {@code dispatch ui} but the jar and {@link ApiException} — no cookies,
 * no pages, no session: every request but pairing carries a worker key, which names the member whose jobs that worker
 * may take.
 *
 * <p>{@code config} is the snapshot this instance was started with, exactly as every other component in the process
 * holds it (there is no config hot-reload anywhere in Dispatch today, only {@link Groups}, whose membership changes
 * in-process as people join). {@code /api/worker/projects} answers from that snapshot, so a config file edited on disk
 * is not visible here until the process restarts, which rebuilds this server too. Deliberate until W-4 gives config a
 * live-reload story.
 */
public final class WorkerApi implements AutoCloseable {

    public static final String PAIR = "/api/worker/pair";
    public static final String NEXT = "/api/worker/next";
    public static final String PROGRESS = "/api/worker/progress";
    public static final String ATTACHMENT = "/api/worker/attachment";
    public static final String RESULT = "/api/worker/result";
    public static final String PROJECTS = "/api/worker/projects";

    /**
     * {@code base} (any trailing slash removed) plus {@code path}, one of this class's own route constants. Both
     * {@link dispatch.cli.Checks} (probing {@code workers.publicUrl}) and {@link WorkerClient} (every request a
     * worker sends) build a route this same way, so a trailing slash in the configured URL is harmless instead of
     * doubling up into a path nothing serves, and a reverse proxy's own sub-path (e.g. {@code https://host/dispatch})
     * is kept instead of silently dropped, as {@code URI.resolve} would drop it for an absolute path like these.
     */
    public static URI url(String base, String path) {
        return URI.create((base.endsWith("/") ? base.substring(0, base.length() - 1) : base) + path);
    }

    private static final int MAX_BODY = 64 * 1024;
    /** Matches {@code WorkerKeys}' own cap, so a name this route accepts is never rejected again once cleaned there. */
    private static final int MAX_NAME_LENGTH = 40;
    /** How long a request may take to deliver its body before this thread gives up on it. */
    private static final Duration DEFAULT_BODY_READ_TIMEOUT = Duration.ofSeconds(10);
    /** A small, bounded drain of an oversized body: enough for the client to see the 413, not a megabyte. */
    private static final int DRAIN_BYTES = 8 * 1024;
    /**
     * How many requests one worker key may have running at once. A long {@code /next} poll parks a thread and a
     * socket for up to 25 s, and an attachment fetch holds one for as long as the download takes, so this is what
     * keeps a buggy or hostile worker from piling up an unbounded number of either. Package-visible so
     * {@code WorkerProtocolTest} can size its own flood past it without duplicating the number.
     */
    static final int MAX_IN_FLIGHT_PER_WORKER = 4;
    private static final String WRONG_KEY =
            "this worker key is not valid any more: run dispatch worker pair again with a new code from /worker";

    private final HttpServer server;
    private final Config config;
    private final Groups groups;
    private final WorkerKeys keys;
    private final RemoteWorkers workers;
    private final AttachmentSource attachments;
    private final Database db;
    private final Clock clock;
    private final Set<String> hosts;
    private final Duration bodyReadTimeout;
    private final ExecutorService bodyReads;
    private final Map<Long, AtomicInteger> inFlightByWorker = new ConcurrentHashMap<>();

    private WorkerApi(HttpServer server, Config config, Groups groups, WorkerKeys keys, RemoteWorkers workers,
                      AttachmentSource attachments, Database db, Clock clock, Duration bodyReadTimeout) {
        this.server = server;
        this.config = config;
        this.groups = groups;
        this.keys = keys;
        this.workers = workers;
        this.attachments = attachments;
        this.db = db;
        this.clock = clock;
        this.hosts = allowedHosts(config.workers(), server.getAddress().getPort());
        this.bodyReadTimeout = bodyReadTimeout;
        this.bodyReads = Executors.newVirtualThreadPerTaskExecutor();
    }

    /**
     * @param config      must have a {@code workers} block; {@code port} 0 takes any free port, as tests do
     * @param workers     the jobs waiting for members' computers
     * @param attachments where a task's files come from — the channel they were sent in; the bytes pass through and no
     *                    copy stays on this machine
     */
    public static WorkerApi start(Config config, Groups groups, WorkerKeys keys, RemoteWorkers workers,
                                  AttachmentSource attachments, Database db, Clock clock) throws IOException {
        return start(config, groups, keys, workers, attachments, db, clock, DEFAULT_BODY_READ_TIMEOUT);
    }

    /** @param bodyReadTimeout overrides {@link #DEFAULT_BODY_READ_TIMEOUT}; a real wall-clock bound, for tests. */
    static WorkerApi start(Config config, Groups groups, WorkerKeys keys, RemoteWorkers workers,
                           AttachmentSource attachments, Database db, Clock clock, Duration bodyReadTimeout) throws IOException {
        HttpServer http = HttpServer.create(
                new InetSocketAddress(InetAddress.getByName("127.0.0.1"), config.workers().port()), 0);
        WorkerApi api = new WorkerApi(http, config, groups, keys, workers, attachments, db, clock, bodyReadTimeout);
        http.createContext("/", api::handle);
        http.setExecutor(Executors.newVirtualThreadPerTaskExecutor());
        http.start();
        Log.info("worker_api.started", "port", api.port(), "public_url", config.workers().publicUrl());
        return api;
    }

    public int port() {
        return server.getAddress().getPort();
    }

    /**
     * Stops accepting new requests and gives an in-flight one — most likely a parked {@code /next} long poll — up to a
     * second to finish before its socket is torn down (the JDK's own {@code stop(delay)} wait), then lets
     * {@link #bodyReads} drain whatever body read or response write that request's own handler still had in flight
     * instead of aborting it outright: a hard {@code shutdownNow()} straight after {@code stop(0)} used to reject that
     * still-parked handler's own response write, then its 500 fallback's write, then {@link #closeBounded}'s own close
     * — three rejections deep, surfacing as {@code worker_api.failed}, the same event a genuine 500 uses, on every
     * ordinary shutdown with a poll in flight. {@code shutdownNow()} still runs, but only as a backstop for whatever
     * did not drain in time.
     *
     * <p>A parked {@code /next} is woken through {@link RemoteWorkers#stopPolling} before that wait, so it answers
     * rather than being cut off — which is what makes a shutdown quiet for production's 25 s poll and not only for a
     * test's short one.
     */
    @Override
    public void close() {
        // First: a poll parked in RemoteWorkers is waiting on that class's lock, not on this server, so stop(1) below
        // would only cut its socket. Woken here, it answers {"job": null} and the exchange ends normally.
        workers.stopPolling();
        server.stop(1);
        bodyReads.shutdown();
        try {
            if (!bodyReads.awaitTermination(bodyReadTimeout.toMillis(), TimeUnit.MILLISECONDS)) {
                bodyReads.shutdownNow();
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            bodyReads.shutdownNow();
        }
    }

    /**
     * A reverse proxy passes the name members use; a worker on the same machine uses the loopback address. Anything else
     * is a request that was not meant for this server. Matched case-insensitively, since {@code Host} is: only the host
     * (with its port, if any) of {@code workers.publicUrl} is added — never the bare host on top of it, which would widen
     * the allow-list past what the config actually names.
     */
    private static Set<String> allowedHosts(Config.Workers workers, int port) {
        Set<String> hosts = new HashSet<>(Set.of("127.0.0.1:" + port, "localhost:" + port));
        URI url = URI.create(workers.publicUrl());
        if (url.getHost() != null) {
            hosts.add(url.getPort() < 0 ? url.getHost() : url.getHost() + ":" + url.getPort());
        }
        Set<String> lowercased = new HashSet<>();
        hosts.forEach(host -> lowercased.add(host.toLowerCase(Locale.ROOT)));
        return Set.copyOf(lowercased);
    }

    private void handle(HttpExchange exchange) throws IOException {
        try {
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
        } finally {
            // Not try-with-resources: HttpExchange.close() itself is the other unbounded read in this class (see
            // closeBounded), and the response above is already written and flushed by the time this runs either way.
            closeBounded(exchange);
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
        withInFlightLimit(worker, () -> route(exchange, path, worker, body));
    }

    @FunctionalInterface
    private interface RouteAction {
        void run() throws IOException, InterruptedException;
    }

    /**
     * Bounds how many requests one worker key may have running at once (see {@link #MAX_IN_FLIGHT_PER_WORKER}). The
     * count is reserved before {@code action} runs and released once it returns or throws, so a request refused here
     * never touches {@code workers} or {@code attachments} at all.
     */
    private void withInFlightLimit(Workers.Paired worker, RouteAction action) throws IOException, InterruptedException {
        AtomicInteger inFlight = inFlightByWorker.computeIfAbsent(worker.id(), id -> new AtomicInteger());
        if (inFlight.incrementAndGet() > MAX_IN_FLIGHT_PER_WORKER) {
            inFlight.decrementAndGet();
            throw new ApiException(429, "too_many_requests",
                    "this worker already has " + MAX_IN_FLIGHT_PER_WORKER + " requests running; wait for one to finish");
        }
        try {
            action.run();
        } finally {
            inFlight.decrementAndGet();
        }
    }

    private void route(HttpExchange exchange, String path, Workers.Paired worker, JsonNode body) throws IOException,
            InterruptedException {
        switch (path) {
            case PROJECTS -> json(exchange, 200, Json.write(projects(worker)));
            case NEXT -> {
                readiness(body).ifPresent(reported ->
                        db.transaction(tx -> Workers.saveReadiness(tx, worker.id(), reported, clock.instant())));
                Optional<Job> job = workers.next(worker);
                ObjectNode answer = Json.object();
                if (job.isPresent()) {
                    answer.set("job", Json.MAPPER.<JsonNode>valueToTree(job.get()));
                } else {
                    answer.putNull("job");
                }
                json(exchange, 200, Json.write(answer));
            }
            case PROGRESS -> {
                boolean cancel = workers.progress(worker, new RemoteWorkers.Progress(
                        requiredLong(body, "taskId"), requiredInt(body, "seq"),
                        text(body, "worktree"), text(body, "baseSha"), body.path("agentStarted").asBoolean(false),
                        optionalInt(body, "steps"), text(body, "lastAction")));
                json(exchange, 200, Json.write(Json.object().put("cancel", cancel)));
            }
            case RESULT -> {
                JobResult result = readJobResult(required(body, "result"));
                workers.result(worker, requiredLong(body, "taskId"), requiredInt(body, "seq"), result);
                json(exchange, 200, Json.write(Json.object().put("ok", true)));
            }
            case ATTACHMENT -> attachment(exchange, worker, requiredLong(body, "taskId"), required(body, "fileRef").asText());
            default -> throw new ApiException(404, "not_found", "no such worker API: " + path);
        }
    }

    /**
     * One of the leased job's files, fetched from the channel and streamed straight through. The team machine writes it
     * owner-only while it is in flight, under a name unique to this request (two concurrent fetches of the same file
     * must not race one's delete against the other's read), and deletes it whatever happens: the bytes belong to the
     * requester.
     */
    private void attachment(HttpExchange exchange, Workers.Paired worker, long taskId, String fileRef) throws IOException {
        Job job = workers.leased(worker, taskId);
        Attachment file = job.attachments().stream().filter(candidate -> candidate.fileRef().equals(fileRef)).findFirst()
                .orElseThrow(() -> new ApiException(404, "not_found", "task " + taskId + " has no such file"));
        Path copy = config.stateDir().resolve("outgoing")
                .resolve(taskId + "-" + WorkerKeys.sha256(file.fileRef()).substring(0, 16) + "-" + UUID.randomUUID());
        byte[] bytes;
        try {
            OwnerOnly.createDirectories(copy.getParent());
            attachments.download(file.fileRef(), copy);
            // Checked before buffering, not just relied on from Attachment.size (which the channel may not have
            // reported): N concurrent fetches must not be able to hold N x an unbounded file size in heap.
            long size = Files.size(copy);
            if (size > Attachment.MAX_BYTES) {
                throw new IllegalStateException(
                        "downloaded " + size + " bytes, more than the " + Attachment.MAX_BYTES + "-byte limit");
            }
            bytes = Files.readAllBytes(copy);
        } catch (IOException | RuntimeException e) {
            // The real cause can carry the download URL (a bot-token query parameter, for a Telegram-backed source):
            // logged here, on the team machine, never echoed back to the worker that asked.
            Log.error("worker_api.attachment_failed", e, "task", taskId, "worker", worker.id());
            throw new ApiException(500, "internal", "something went wrong fetching this file on the team machine");
        } finally {
            Files.deleteIfExists(copy);
        }
        exchange.getResponseHeaders().set("Content-Type", "application/octet-stream");
        send(exchange, 200, bytes);
    }

    /** Empty when the worker sent none: an older worker keeps working and counts as ready. */
    private static Optional<Readiness> readiness(JsonNode body) {
        JsonNode reported = body.path("readiness");
        if (!reported.isObject()) {
            return Optional.empty();
        }
        Map<String, Readiness.Check> projects = new LinkedHashMap<>();
        reported.path("projects").properties().forEach(entry ->
                projects.put(entry.getKey(), check(entry.getValue())));
        return Optional.of(new Readiness(check(reported.path("claude")), check(reported.path("gh")), projects));
    }

    private static Readiness.Check check(JsonNode node) {
        // A field a worker left out is "fine": only an explicit false holds anything.
        return new Readiness.Check(node.path("ok").asBoolean(true), node.path("detail").asText(null));
    }

    private static JobResult readJobResult(JsonNode node) {
        try {
            return Json.MAPPER.treeToValue(node, JobResult.class);
        } catch (JsonProcessingException e) {
            throw new ApiException(400, "invalid", "result: not a JobResult");
        }
    }

    private static JsonNode required(JsonNode body, String field) {
        if (!body.hasNonNull(field)) {
            throw new ApiException(400, "invalid", field + ": required");
        }
        return body.get(field);
    }

    private static long requiredLong(JsonNode body, String field) {
        JsonNode value = required(body, field);
        if (!value.isIntegralNumber()) {
            throw new ApiException(400, "invalid", field + ": must be a whole number");
        }
        return value.asLong();
    }

    private static int requiredInt(JsonNode body, String field) {
        JsonNode value = required(body, field);
        if (!value.isIntegralNumber()) {
            throw new ApiException(400, "invalid", field + ": must be a whole number");
        }
        return value.asInt();
    }

    /** As {@link #requiredInt}, but absent (rather than merely non-numeric) is a valid, meaningful {@code null}. */
    private static Integer optionalInt(JsonNode body, String field) {
        if (!body.hasNonNull(field)) {
            return null;
        }
        JsonNode value = body.get(field);
        if (!value.isIntegralNumber()) {
            throw new ApiException(400, "invalid", field + ": must be a whole number");
        }
        return value.asInt();
    }

    private static String text(JsonNode body, String field) {
        return body.hasNonNull(field) ? body.get(field).asText() : null;
    }

    private Workers.Paired authenticate(HttpExchange exchange) {
        String header = exchange.getRequestHeaders().getFirst("Authorization");
        String key = header != null && header.startsWith("Bearer ") ? header.substring("Bearer ".length()).strip() : null;
        // Unknown and revoked answer alike, so nobody can learn which keys ever existed.
        return keys.authenticate(key).orElseThrow(() -> new ApiException(401, "unauthorized", WRONG_KEY));
    }

    private ObjectNode pair(JsonNode body) {
        String code = body.path("code").asText("");
        // Cleaned here, once, before the emptiness/length check below; WorkerKeys.pair's own boundName cleans the
        // (already-clean) name again — TelegramNames.clean is idempotent, so that costs nothing — and keeps its own
        // blank check too, but that check can no longer fire from this caller: a name this route accepts is never
        // blank once cleaned, which is the one thing that check rejects. Validated once, here; cleaned twice.
        String name = TelegramNames.clean(body.path("name").asText(""));
        if (name.isEmpty() || name.length() > MAX_NAME_LENGTH) {
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
        return host == null ? "" : host.strip().toLowerCase(Locale.ROOT);
    }

    private JsonNode body(HttpExchange exchange) throws IOException {
        InputStream in = exchange.getRequestBody();
        byte[] raw = readBounded(in, MAX_BODY + 1);
        if (raw.length > MAX_BODY) {
            // Best-effort, itself bounded by the same deadline: enough for the client to see the 413 instead of a
            // reset connection, without this thread waiting on bytes a stalled or hostile client never sends.
            try {
                readBounded(in, DRAIN_BYTES);
            } catch (ApiException | IOException ignored) {
                // the 413 below is what matters; a failed drain just means the connection closes instead of reusing.
            }
            throw new ApiException(413, "too_large", "the request is larger than " + MAX_BODY / 1024 + " KiB");
        }
        try {
            return raw.length == 0 ? Json.object() : Json.MAPPER.readTree(raw);
        } catch (JsonProcessingException e) {
            throw new ApiException(400, "invalid", "the request is not JSON");
        }
    }

    /**
     * Reads up to {@code maxBytes} of {@code in}, bounded by {@link #bodyReadTimeout} of real wall-clock time — not the
     * unbounded wait {@code InputStream.read} itself offers, which would let a client that sends headers and then
     * nothing hold this request's thread forever. The read runs on its own virtual thread so this one can wait on it
     * with a deadline.
     *
     * <p>Timing out interrupts that thread: the JDK's own request stream is backed by the connection's channel, and
     * interrupting a thread blocked on it closes the channel as required by {@link java.nio.channels.Channel}'s
     * contract — there is no supported way to abort only the read half. That closes the whole connection, not just
     * this read, so a stalled client is refused by having its connection dropped rather than by a graceful JSON body;
     * either way this thread is freed at the deadline instead of held forever.
     */
    private byte[] readBounded(InputStream in, int maxBytes) throws IOException {
        Future<byte[]> read = bodyReads.submit(() -> in.readNBytes(maxBytes));
        try {
            return read.get(bodyReadTimeout.toMillis(), TimeUnit.MILLISECONDS);
        } catch (TimeoutException e) {
            read.cancel(true);
            throw new ApiException(400, "invalid",
                    "the request body did not arrive within " + bodyReadTimeout.toSeconds() + "s");
        } catch (ExecutionException e) {
            if (e.getCause() instanceof IOException io) {
                throw io;
            }
            throw new IllegalStateException("reading the request body failed", e.getCause());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new ApiException(503, "stopping", "Dispatch is stopping");
        }
    }

    /**
     * Closes {@code exchange} under the same deadline as {@link #readBounded}, as a backstop for any path that
     * reaches here without having gone through {@link #send}: normally {@code send} itself already closes (or, on
     * a timeout, tears down) this exchange's streams, since writing a response is what triggers the JDK's own
     * drain (see {@link #send}'s doc) — so by the time this runs, {@code exchange.close()} is ordinarily a fast
     * no-op finding everything already closed.
     */
    private void closeBounded(HttpExchange exchange) {
        Future<?> closing = bodyReads.submit(exchange::close);
        try {
            closing.get(bodyReadTimeout.toMillis(), TimeUnit.MILLISECONDS);
        } catch (TimeoutException e) {
            closing.cancel(true);
        } catch (ExecutionException e) {
            // HttpExchange.close() does not declare a checked exception and swallows IOException internally on its
            // own; nothing more to do here even if some other RuntimeException escaped it.
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            closing.cancel(true);
        }
    }

    static String error(String code, String message) {
        return Json.write(Map.of("error", code, "message", message));
    }

    private void json(HttpExchange exchange, int status, String body) throws IOException {
        exchange.getResponseHeaders().set("Content-Type", "application/json; charset=utf-8");
        send(exchange, status, body.getBytes(StandardCharsets.UTF_8));
    }

    /**
     * Sends the response, bounded by the same deadline as {@link #readBounded}. For the small JSON bodies most routes
     * send, writing it is not itself slow; closing the response's {@code OutputStream} is what the JDK uses as the
     * signal that the exchange is done — {@code FixedLengthOutputStream.close} (and its chunked/undefined-length
     * siblings) reflexively closes the *request* stream too, which is exactly {@link #readBounded}'s problem again: a
     * declared {@code Content-Length} this class never fully read (an oversized body past {@link #MAX_BODY}+
     * {@link #DRAIN_BYTES}, say) leaves that close needing to drain the rest with the same untimed blocking read.
     * Bounding this whole call, not just the read side, is what actually keeps a client that stalls after tripping a
     * 413 from holding this thread forever. That bound is not free for {@link #attachment}'s own, much larger body: a
     * slow connection can still be genuinely mid-write when the deadline lands, and cutting the call off there (the
     * same interrupt-closes-the-channel mechanism as {@link #readBounded}) costs that in-flight response too, not
     * only the connection's reuse — a truncated download, not a clean refusal.
     */
    private void send(HttpExchange exchange, int status, byte[] body) throws IOException {
        Future<?> sending = bodyReads.submit(() -> {
            exchange.sendResponseHeaders(status, body.length);
            try (OutputStream out = exchange.getResponseBody()) {
                out.write(body);
            }
            return null;
        });
        try {
            sending.get(bodyReadTimeout.toMillis(), TimeUnit.MILLISECONDS);
        } catch (TimeoutException e) {
            sending.cancel(true);
        } catch (ExecutionException e) {
            if (e.getCause() instanceof IOException io) {
                throw io;
            }
            throw new IllegalStateException("writing the response failed", e.getCause());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            sending.cancel(true);
        }
    }
}
