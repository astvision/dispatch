package dispatch.ui;

import dispatch.Json;
import dispatch.Language;
import dispatch.Log;
import dispatch.Text;
import java.io.IOException;
import java.net.ConnectException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.function.Supplier;

/** {@code dispatch ui}'s side of the desk port (D-2): task calls go on to the running bot, with the token from desk.json. */
final class DeskProxy implements UiServer.Forward {

    private static final Duration TIMEOUT = Duration.ofSeconds(5);
    private static final Text NOT_RUNNING = Text.of("desk.botNotRunning");

    private final Supplier<Path> stateDir;
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(TIMEOUT).build();

    /** @param stateDir read on every call: the file is found again after the bot restarts on another port */
    DeskProxy(Supplier<Path> stateDir) {
        this.stateDir = stateDir;
    }

    @Override
    public boolean handles(String path) {
        return path.startsWith("/api/tasks/") || path.equals("/api/live");
    }

    @Override
    public UiServer.Forwarded forward(String path, String method, byte[] body, Language language, String member) {
        Optional<DeskFile> desk = DeskFile.read(stateDir.get());
        if (desk.isEmpty()) {
            return notRunning(language);
        }
        HttpRequest.Builder request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + desk.get().port() + path))
                .timeout(TIMEOUT)
                .header("Authorization", "desk " + desk.get().token())
                .header("Accept-Language", language.name().toLowerCase(Locale.ROOT));
        if (member != null && !member.isBlank()) {
            request.header(DeskAuth.MEMBER_HEADER, member);
        }
        if (method.equals("GET")) {
            request.GET();
        } else {
            request.header("Content-Type", "application/json").POST(HttpRequest.BodyPublishers.ofByteArray(body));
        }
        try {
            HttpResponse<byte[]> answer = http.send(request.build(), HttpResponse.BodyHandlers.ofByteArray());
            return new UiServer.Forwarded(answer.statusCode(), answer.body());
        } catch (ConnectException e) {
            // Refused: a stopped bot, or one that crashed and left its file. No news, and the page asks every 5 seconds.
            return notRunning(language);
        } catch (IOException e) {
            // Taken but not answered in time, or cut off: a bot that runs but is stuck, which the page can only call
            // stopped. The line says what really happened.
            Log.warn("desk.unanswered", "port", desk.get().port(), "path", path, "error", e.toString());
            return notRunning(language);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return notRunning(language);
        }
    }

    private static UiServer.Forwarded notRunning(Language language) {
        return new UiServer.Forwarded(503, Json.write(Map.of("error", "bot_not_running", "message", NOT_RUNNING.render(language)))
                .getBytes(StandardCharsets.UTF_8));
    }
}
