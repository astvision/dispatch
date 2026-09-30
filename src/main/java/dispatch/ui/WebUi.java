package dispatch.ui;

import dispatch.Json;
import dispatch.Log;
import dispatch.Text;
import dispatch.cli.ServiceCommand;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.TimeUnit;

/**
 * "Вэб UI нээх" in the Mini App (ADR 0018, amended): a fresh one-time link to the web UI on this computer, from the
 * {@code dispatch ui} already running here, or from one started for it. The link only works on 127.0.0.1, so it helps an
 * admin whose Telegram runs on the computer the bot runs on.
 */
public final class WebUi {

    /** Starts {@code dispatch ui} apart from the bot, so the bot's own restart does not stop it; throws ApiException. */
    public interface Starter {
        void start(List<String> command);
    }

    /** What an open web UI asks within, so a tab left open keeps it running (Main.ui). */
    static final int IDLE_MINUTES = 30;

    private final Path stateDir;
    /** Null when this computer cannot start one for the Mini App (not systemd, or not run from a jar). */
    private final List<String> command;
    private final String byHand;
    private final Starter starter;
    private final Duration startWait;
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).build();

    WebUi(Path stateDir, List<String> command, String byHand, Starter starter, Duration startWait) {
        this.stateDir = stateDir;
        this.command = command == null ? null : List.copyOf(command);
        this.byHand = byHand;
        this.starter = starter;
        this.startWait = startWait;
    }

    /**
     * The web UI for the bot {@code configFile} configures. On Linux a user service manager starts it; elsewhere the
     * Mini App says what to run instead (a later slice may start it on macOS and Windows too).
     *
     * @param instance the instance this bot is (ADR 0028), null for the default one
     */
    public static WebUi forThisMachine(Path stateDir, Path configFile, String instance, Map<String, String> environment) {
        String byHand = instance == null ? "dispatch ui" : "dispatch ui --instance " + instance;
        Path jar = ServiceCommand.runningJar();
        Optional<String> java = ProcessHandle.current().info().command();
        boolean linux = System.getProperty("os.name", "").startsWith("Linux");
        if (!linux || jar == null || java.isEmpty()) {
            return new WebUi(stateDir, null, byHand, WebUi::run, Duration.ofSeconds(15));
        }
        return new WebUi(stateDir, systemdCommand(java.get(), jar, configFile, instance, environment.get("PATH")), byHand,
                WebUi::run, Duration.ofSeconds(15));
    }

    /**
     * {@code dispatch ui} in a transient unit of the user's service manager, outside the bot's own cgroup, so restarting
     * the bot does not stop it. It gets the bot's PATH, as the bot's own unit does, and stops once nobody uses it.
     */
    static List<String> systemdCommand(String java, Path jar, Path configFile, String instance, String path) {
        List<String> command = new ArrayList<>(List.of("systemd-run", "--user", "--collect", "--quiet",
                "--unit=" + (instance == null ? "dispatch-ui" : "dispatch-ui-" + instance)));
        if (path != null) {
            command.add("--setenv=PATH=" + path);
        }
        command.addAll(List.of(java, "-jar", jar.toString(), "ui", "--no-browser", "--idle-minutes", Integer.toString(IDLE_MINUTES)));
        // --instance finds its own config, and refuses a --config beside it (Cli.refuseConfigWithInstance).
        command.addAll(instance == null ? List.of("--config", configFile.toString()) : List.of("--instance", instance));
        return command;
    }

    /** One at a time: a second tap while the first starts it would start a second one, whose unit name is taken. */
    public synchronized String open() {
        Optional<String> running = ask();
        if (running.isPresent()) {
            return running.get();
        }
        if (command == null) {
            throw new ApiException(400, "webui_by_hand", Text.of("refusal.webUiByHand", byHand));
        }
        starter.start(command);
        long deadline = System.nanoTime() + startWait.toNanos();
        while (System.nanoTime() < deadline) {
            try {
                Thread.sleep(200);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }
            Optional<String> started = ask();
            if (started.isPresent()) {
                Log.info("webui.started", "state_dir", stateDir);
                return started.get();
            }
        }
        Log.warn("webui.not_started", "state_dir", stateDir, "waited_s", startWait.toSeconds());
        throw new ApiException(503, "webui_not_started", Text.of("refusal.webUiNotStarted", byHand));
    }

    /** A new link from the web UI ui.json names; empty when there is none, or it does not answer (gone, or too old). */
    private Optional<String> ask() {
        Optional<UiFile> file = UiFile.read(stateDir);
        if (file.isEmpty()) {
            return Optional.empty();
        }
        HttpRequest request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + file.get().port() + "/link"))
                .header(UiAuth.KEY_HEADER, file.get().key()).timeout(Duration.ofSeconds(3))
                .POST(HttpRequest.BodyPublishers.noBody()).build();
        try {
            HttpResponse<String> answer = http.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            if (answer.statusCode() != 200) {
                Log.warn("webui.link_refused", "port", file.get().port(), "status", answer.statusCode());
                return Optional.empty();
            }
            String url = Json.read(answer.body()).path("url").asText("");
            return url.startsWith("http://127.0.0.1:") ? Optional.of(url) : Optional.empty();
        } catch (IOException e) {
            // A crash leaves ui.json behind: nothing listens there now, and a new one is started.
            return Optional.empty();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return Optional.empty();
        }
    }

    /** Runs the start command, which returns once the service manager took it; its output says why when it did not. */
    private static void run(List<String> command) {
        try {
            Process process = new ProcessBuilder(command).redirectErrorStream(true).start();
            if (!process.waitFor(10, TimeUnit.SECONDS)) {
                process.destroyForcibly();
                throw new IOException("did not return within 10 s");
            }
            String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8).strip();
            if (process.exitValue() != 0) {
                throw new IOException("exit " + process.exitValue() + ": " + output);
            }
        } catch (IOException e) {
            Log.warn("webui.start_failed", "command", command.getFirst(), "error", e.getMessage());
            throw new ApiException(500, "webui_not_started", Text.of("refusal.webUiStartFailed", e.getMessage()));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new ApiException(500, "webui_not_started", Text.of("refusal.webUiStartFailed", "interrupted"));
        }
    }
}
