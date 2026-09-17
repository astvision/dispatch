package dispatch.testing;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import dispatch.Json;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Map;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedDeque;
import java.util.concurrent.Executors;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

/** A local stand-in for api.telegram.org: records every call and answers like the Bot API. */
public final class FakeTelegram implements AutoCloseable {

    public static final String TOKEN = "TEST-TOKEN";
    public static final String BOT_USERNAME = "dispatch_test_bot";

    public record Request(String method, String contentType, byte[] body) {

        public JsonNode json() {
            return Json.read(text());
        }

        public String text() {
            return new String(body, StandardCharsets.UTF_8);
        }
    }

    private final HttpServer server;
    private final Map<String, BlockingQueue<Request>> requests = new ConcurrentHashMap<>();
    private final Map<String, Deque<String[]>> scripted = new ConcurrentHashMap<>();
    private final Map<Long, String[]> refusedChats = new ConcurrentHashMap<>();
    private final BlockingQueue<JsonNode> updates = new LinkedBlockingQueue<>();
    private final AtomicLong nextMessageId = new AtomicLong(1000);

    private FakeTelegram(HttpServer server) {
        this.server = server;
    }

    public static FakeTelegram start() throws IOException {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        FakeTelegram fake = new FakeTelegram(server);
        server.createContext("/", fake::handle);
        server.setExecutor(Executors.newVirtualThreadPerTaskExecutor());
        server.start();
        return fake;
    }

    public URI baseUri() {
        return URI.create("http://127.0.0.1:" + server.getAddress().getPort() + "/bot" + TOKEN + "/");
    }

    /** The next call to {@code method} gets this HTTP status and body instead of a success. */
    public void respond(String method, int status, String body) {
        scripted.computeIfAbsent(method, m -> new ConcurrentLinkedDeque<>()).add(new String[] {String.valueOf(status), body});
    }

    /** Every sendMessage to {@code chatId} gets this HTTP status and body, e.g. a user who never started the bot. */
    public void refuseChat(long chatId, int status, String body) {
        refusedChats.put(chatId, new String[] {String.valueOf(status), body});
    }

    public void pushUpdate(JsonNode update) {
        updates.add(update);
    }

    public Request awaitRequest(String method, Duration timeout) throws InterruptedException {
        Request request = queue(method).poll(timeout.toMillis(), TimeUnit.MILLISECONDS);
        if (request == null) {
            throw new AssertionError("no " + method + " call within " + timeout);
        }
        return request;
    }

    public List<Request> drain(String method) {
        List<Request> drained = new ArrayList<>();
        queue(method).drainTo(drained);
        return drained;
    }

    @Override
    public void close() {
        server.stop(0);
    }

    private void handle(HttpExchange exchange) throws IOException {
        String path = exchange.getRequestURI().getPath();
        String method = path.substring(path.lastIndexOf('/') + 1);
        byte[] body = exchange.getRequestBody().readAllBytes();
        queue(method).add(new Request(method, exchange.getRequestHeaders().getFirst("Content-Type"), body));

        if (method.equals("sendMessage")) {
            String[] refused = refusedChats.get(Json.read(new String(body, StandardCharsets.UTF_8)).path("chat_id").asLong());
            if (refused != null) {
                reply(exchange, Integer.parseInt(refused[0]), refused[1]);
                return;
            }
        }
        String[] scriptedResponse = scripted.getOrDefault(method, new ConcurrentLinkedDeque<>()).poll();
        if (scriptedResponse != null) {
            reply(exchange, Integer.parseInt(scriptedResponse[0]), scriptedResponse[1]);
            return;
        }
        String result = switch (method) {
            case "getMe" -> "{\"id\":1,\"is_bot\":true,\"username\":\"" + BOT_USERNAME + "\"}";
            case "getUpdates" -> pendingUpdates();
            case "sendMessage", "sendDocument" -> "{\"message_id\":" + nextMessageId.getAndIncrement() + "}";
            default -> "true";
        };
        reply(exchange, 200, "{\"ok\":true,\"result\":" + result + "}");
    }

    private String pendingUpdates() {
        ArrayNode result = Json.MAPPER.createArrayNode();
        try {
            JsonNode first = updates.poll(200, TimeUnit.MILLISECONDS);
            if (first != null) {
                result.add(first);
                List<JsonNode> rest = new ArrayList<>();
                updates.drainTo(rest);
                rest.forEach(result::add);
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        return result.toString();
    }

    private BlockingQueue<Request> queue(String method) {
        return requests.computeIfAbsent(method, m -> new LinkedBlockingQueue<>());
    }

    private static void reply(HttpExchange exchange, int status, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "application/json");
        exchange.sendResponseHeaders(status, bytes.length);
        try (OutputStream out = exchange.getResponseBody()) {
            out.write(bytes);
        }
    }
}
