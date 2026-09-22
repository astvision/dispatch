package dispatch.worker;

import dispatch.Json;
import dispatch.config.Config;
import dispatch.core.ActiveRuns;
import dispatch.core.AttachmentSource;
import dispatch.core.Groups;
import dispatch.core.Job;
import dispatch.core.JobEvents;
import dispatch.core.JobResult;
import dispatch.domain.Phase;
import dispatch.domain.Requester;
import dispatch.store.Database;
import dispatch.testing.TestClock;
import java.io.IOException;
import java.io.OutputStream;
import java.io.UncheckedIOException;
import java.math.BigDecimal;
import java.net.Socket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.io.TempDir;

/**
 * What both {@link WorkerApiTest} (who may talk to the server at all) and {@link WorkerProtocolTest} (what the
 * protocol routes do) need: a migrated database with task 7 already owned by Bold, a {@link WorkerApi} wired to a
 * real {@link RemoteWorkers} and an {@link AttachmentSource} that hands back {@code "hi\n"}, and the HTTP helpers
 * both test classes drive it with.
 */
abstract class WorkerApiFixture {

    static final Requester BOLD = new Requester("telegram:100", "Bold");
    static final Requester ALI = new Requester("telegram:200", "Ali");
    /** The task {@link WorkerProtocolTest}'s jobs are for; pre-inserted so a job naming it has somewhere to point. */
    static final long TASK_ID = 7;

    @TempDir
    Path dir;

    /** Where the server's own files (never a member's attachment) would land; the attachment test proves it stays empty. */
    Path stateDir;
    Database db;
    ActiveRuns activeRuns;
    WorkerKeys keys;
    RemoteWorkers remote;
    WorkerApi api;
    /** Set by {@link #offer}, so a test can drive the same cancel a member's own {@code /cancel} would. */
    ActiveRuns.ActiveRun control;
    final HttpClient http = HttpClient.newHttpClient();
    final TestClock clock = new TestClock(Instant.parse("2026-09-23T10:00:00Z"));
    /** What {@link RemoteWorkers#run} returned for the job {@link #offer} started, once a worker has reported it. */
    final AtomicReference<JobResult> reported = new AtomicReference<>();

    @BeforeEach
    void setUpFixture() throws Exception {
        stateDir = dir;
        db = Database.open(dir.resolve("dispatch.db"));
        db.migrate();
        insertTaskSeven();
        activeRuns = new ActiveRuns();
        keys = new WorkerKeys(db, clock);
        remote = new RemoteWorkers(db, clock, () -> { }, Duration.ofMillis(50), Duration.ofMillis(2));
        api = WorkerApi.start(config(), groups(), keys, remote, attachments());
    }

    @AfterEach
    void tearDownFixture() {
        api.close();
        db.close();
    }

    /** Downloads always write {@code "hi\n"}: the protocol tests only care that the bytes pass through unchanged. */
    AttachmentSource attachments() {
        return (fileRef, target) -> {
            try {
                Files.writeString(target, "hi\n");
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
        };
    }

    /** Task 7, requested by Bold, so a job that names it (as {@code WorkerProtocolTest.job} does) has a real owner. */
    private void insertTaskSeven() {
        db.transaction(tx -> tx.update("""
                        INSERT INTO task (id, project, title, description, phase, requester_ref, requester_name, origin_ref,
                                          chat_ref, session_id, base_branch, created_at, updated_at)
                        VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)""",
                TASK_ID, "alm", "Fix the login timeout", "Fix the login timeout", Phase.PLANNING, BOLD.ref(), BOLD.name(),
                "telegram:100/7", "telegram:100", UUID.fromString("11111111-2222-3333-4444-555555555555"), "main",
                clock.instant(), clock.instant()));
    }

    /** Pairs Bold's own computer, the member task 7 belongs to. */
    String pair() throws Exception {
        return pair(BOLD, "ann-laptop");
    }

    String pair(Requester member, String name) throws Exception {
        return Json.read(post(WorkerApi.PAIR, null, pairBody(keys.newCode(member), name)).body()).get("key").asText();
    }

    private static String pairBody(String code, String name) {
        return "{\"code\":\"" + code + "\",\"name\":\"" + name + "\"}";
    }

    /**
     * Offers {@code job} exactly as the Coordinator would, on its own virtual thread: {@link #control} is set before
     * this returns, and {@link #reported} is filled once some worker's {@code /api/worker/result} completes it.
     */
    void offer(Job job) {
        offer(job, NOOP_EVENTS);
    }

    void offer(Job job, JobEvents events) {
        control = activeRuns.register(job.taskId(), job.seq());
        Thread.ofVirtual().start(() -> reported.set(remote.run(job, events, control)));
        awaitOffer();
    }

    private static final JobEvents NOOP_EVENTS = new JobEvents() {
        @Override
        public void worktreeCreated(String worktree, String baseSha) {
        }

        @Override
        public void agentStarted(Long pid, Instant processStart) {
        }
    };

    /** The Coordinator reads the store before it offers the job; a test must not poll before that happened. */
    private void awaitOffer() {
        Instant deadline = Instant.now().plusSeconds(10);
        while (!remote.hasOffers()) {
            if (Instant.now().isAfter(deadline)) {
                throw new AssertionError("the job was never offered");
            }
            Thread.onSpinWait();
        }
    }

    HttpResponse<String> post(String path, String key, String body) throws Exception {
        HttpRequest.Builder request = HttpRequest.newBuilder(uri(path)).timeout(Duration.ofSeconds(30))
                .POST(HttpRequest.BodyPublishers.ofString(body));
        if (key != null) {
            request.header("Authorization", "Bearer " + key);
        }
        return http.send(request.build(), HttpResponse.BodyHandlers.ofString());
    }

    URI uri(String path) {
        return URI.create("http://127.0.0.1:" + api.port() + path);
    }

    record Raw(int status, String body) {
    }

    Raw rawPost(String path, String host, String key, String body) throws IOException {
        return rawPost(api.port(), path, host, key, body);
    }

    Raw rawPost(int port, String path, String host, String key, String body) throws IOException {
        byte[] payload = body.getBytes(StandardCharsets.UTF_8);
        String head = "POST " + path + " HTTP/1.1\r\n"
                + "Host: " + host + "\r\n"
                + "Authorization: Bearer " + key + "\r\n"
                + "Content-Type: application/json\r\n"
                + "Content-Length: " + payload.length + "\r\n"
                + "Connection: close\r\n\r\n";
        try (Socket socket = new Socket("127.0.0.1", port)) {
            // A regression in the server's read/close handling must fail this test, not hang the build.
            socket.setSoTimeout(5000);
            OutputStream out = socket.getOutputStream();
            out.write(head.getBytes(StandardCharsets.US_ASCII));
            out.write(payload);
            out.flush();
            String response = new String(socket.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
            int status = Integer.parseInt(response.substring(9, 12));
            return new Raw(status, response.substring(response.indexOf("\r\n\r\n") + 4));
        }
    }

    record Captured(HttpResponse<String> answer, String log) {
    }

    Captured capturingStdout(Callable<HttpResponse<String>> action) throws Exception {
        PrintStream out = System.out;
        ByteArrayOutputStream logged = new ByteArrayOutputStream();
        try {
            System.setOut(new PrintStream(logged, true, StandardCharsets.UTF_8));
            return new Captured(action.call(), logged.toString(StandardCharsets.UTF_8));
        } finally {
            System.setOut(out);
        }
    }

    Config config() {
        return config("http://127.0.0.1:0");
    }

    Config config(String publicUrl) {
        return new Config("backend", dir, new Config.Telegram(List.of(), List.of(new Config.Group("backend", -100L,
                List.of(new Config.Member(100, "Bold"), new Config.Member(200, "Ali")), List.of("alm")))),
                new Config.Scheduler(2), Config.Worktrees.DEFAULT,
                new Config.Limits(new Config.RunLimits(Duration.ofMinutes(30), new BigDecimal("2")),
                        new Config.RunLimits(Duration.ofMinutes(60), new BigDecimal("10"))),
                Map.of("claude-code", new Config.Agent("claude")),
                List.of(new Config.Project("alm", null, "git@github.com:acme/alm.git", null, "main", "claude-code", "opus",
                        "high", List.of(), null, null, null)),
                new Config.Delivery("Dispatch (backend)", "dispatch-backend@example.com", "gh"),
                new Config.Workers(publicUrl, 0), new Config.Secrets("token", null));
    }

    Groups groups() {
        return new Groups(List.of(new Config.Group("backend", -100L,
                List.of(new Config.Member(100, "Bold"), new Config.Member(200, "Ali")), List.of("alm"))));
    }
}
