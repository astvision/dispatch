package dispatch.worker;

import dispatch.Json;
import dispatch.Redactor;
import dispatch.agent.Agent;
import dispatch.agent.claude.ClaudeCodeAgent;
import dispatch.config.Config;
import dispatch.core.ActiveRuns;
import dispatch.core.AttachmentSource;
import dispatch.core.Groups;
import dispatch.core.Job;
import dispatch.core.JobEvents;
import dispatch.core.JobResult;
import dispatch.domain.Phase;
import dispatch.domain.Requester;
import dispatch.domain.RunKind;
import dispatch.store.Database;
import dispatch.testing.FakeClaude;
import dispatch.testing.FakeGh;
import dispatch.testing.GitFixture;
import dispatch.testing.TestClock;
import dispatch.workspace.Delivery;
import dispatch.workspace.Gh;
import dispatch.workspace.Git;
import dispatch.workspace.Workspaces;
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
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.util.concurrent.atomic.AtomicBoolean;
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
    /** What the default {@link #offer}'s {@link JobEvents} learned: {@link WorkerLoopTest} checks what reached this machine. */
    final AtomicReference<String> recordedWorktree = new AtomicReference<>();
    /** Set as soon as the agent starts, whoever's pid it is; {@link #awaitAgentStarted} waits on this, not on the pid check below. */
    final AtomicBoolean started = new AtomicBoolean();
    final AtomicBoolean agentStartedWithoutAPid = new AtomicBoolean();
    /** Real wall-clock progress interval for a test's {@link WorkerLoop}, short enough that a cancel test need not wait 10 s. */
    static final Duration TEST_PROGRESS = Duration.ofMillis(200);
    /** A real origin, seed clone and Dispatch-owned "alm" clone; {@link WorkerLoopTest} points a worker's own clone at it. */
    GitFixture repos;
    private final List<WorkerLoop> loops = new ArrayList<>();

    @BeforeEach
    void setUpFixture() throws Exception {
        stateDir = dir;
        db = Database.open(dir.resolve("dispatch.db"));
        db.migrate();
        insertTaskSeven();
        repos = GitFixture.create(dir, "alm");
        activeRuns = new ActiveRuns();
        keys = new WorkerKeys(db, clock);
        remote = new RemoteWorkers(db, clock, () -> { }, Duration.ofMillis(50), Duration.ofMillis(2));
        api = WorkerApi.start(config(), groups(), keys, remote, attachments());
    }

    @AfterEach
    void tearDownFixture() {
        // A loop left running would keep polling this test's (about to close) server and leak its virtual threads.
        loops.forEach(WorkerLoop::stop);
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
        offer(job, recordingEvents());
    }

    void offer(Job job, JobEvents events) {
        control = activeRuns.register(job.taskId(), job.seq());
        Thread.ofVirtual().start(() -> reported.set(remote.run(job, events, control)));
        awaitOffer();
    }

    /** What the team machine learns while the job runs, into {@link #recordedWorktree} and {@link #agentStartedWithoutAPid}. */
    private JobEvents recordingEvents() {
        return new JobEvents() {
            @Override
            public void worktreeCreated(String worktree, String baseSha) {
                recordedWorktree.set(worktree);
            }

            @Override
            public void agentStarted(Long pid, Instant processStart) {
                agentStartedWithoutAPid.set(pid == null);
                started.set(true);
            }
        };
    }

    /** Task 7's planning run, as the Coordinator would offer it: no worktree yet, so the worker makes one in its own clone. */
    Job planJob() {
        return planJob("Plan this: fix the login timeout");
    }

    Job planJob(String prompt) {
        return new Job(TASK_ID, 1, RunKind.PLAN,
                new Job.Project("alm", "git@github.com:acme/alm.git", null, "main", "claude-code", List.of()),
                "main", null, null, null, UUID.fromString("11111111-2222-3333-4444-555555555555"), false, prompt, null,
                null, Duration.ofSeconds(30).toMillis(), new BigDecimal("2"), List.of(),
                "dispatch #7: Fix the login timeout", List.of("Requested-by: Bold"), null);
    }

    /** Pairs {@code name} with Bold and starts a real {@link WorkerLoop}, wired to this fixture's server, on a virtual thread. */
    void startLoop(String name, Path clonePath) throws Exception {
        startLoop(name, Map.of("alm", new WorkerConfig.Project(clonePath.toString(), null, null)), WorkerClient::new);
    }

    /** As {@link #startLoop(String, Path)}, but with a {@link WorkerClient} this test controls, e.g. one call failing on demand. */
    void startLoop(String name, Path clonePath, ClientFactory clientFactory) throws Exception {
        startLoop(name, Map.of("alm", new WorkerConfig.Project(clonePath.toString(), null, null)), clientFactory);
    }

    /** As {@link #startLoop}, but the member never added "alm" to worker.yaml: this computer cannot run it. */
    void startLoopWithoutProjects(String name) throws Exception {
        startLoop(name, Map.of(), WorkerClient::new);
    }

    /** How {@link #startLoop} builds this test's {@link WorkerClient}; {@code WorkerClient::new} is the ordinary one. */
    @FunctionalInterface
    interface ClientFactory {
        WorkerClient create(HttpClient http, URI team, String key);
    }

    private void startLoop(String name, Map<String, WorkerConfig.Project> projects, ClientFactory clientFactory) throws Exception {
        String key = pair(BOLD, name);
        Path bin = Files.createDirectories(workerStateDir(name).resolve("bin"));
        URI team = URI.create("http://127.0.0.1:" + api.port());
        WorkerConfig workerConfig = new WorkerConfig(team.toString(), name, 1, FakeClaude.install(bin).toString(),
                FakeGh.install(bin).toString(), workerStateDir(name), projects);
        WorkerClient client = clientFactory.create(http, team, key);
        Git git = new Git("git", null, Duration.ofSeconds(30));
        Workspaces workspaces = new Workspaces(workerConfig.stateDir(), git);
        Delivery delivery = new Delivery(git, new Gh(workerConfig.ghCommand(), null, Duration.ofSeconds(30)),
                "Dispatch (backend)", "dispatch-backend@example.com");
        Map<String, Agent> agents = Map.of("claude-code",
                new ClaudeCodeAgent(workerConfig.claudeCommand(), FakeClaude.environment(), Duration.ofSeconds(1)));
        WorkerLoop loop = new WorkerLoop(workerConfig, client, agents, workspaces, delivery, Redactor.patternsOnly(),
                new ActiveRuns(), TEST_PROGRESS);
        loops.add(loop);
        Thread.ofVirtual().name("worker-loop-" + name).start(loop);
    }

    /** Where {@code name}'s worker keeps its own worktrees, run logs and agent notes: never the team machine's own state. */
    Path workerStateDir(String name) {
        return dir.resolve("workers").resolve(name);
    }

    Path worktreeOf(String name, long taskId) {
        return workerStateDir(name).resolve("worktrees").resolve(Long.toString(taskId));
    }

    /** Waits for the worker's {@code /api/worker/result} to complete the offer {@link #offer} started. */
    JobResult awaitResult() {
        Instant deadline = Instant.now().plusSeconds(30);
        while (reported.get() == null) {
            if (Instant.now().isAfter(deadline)) {
                throw new AssertionError("the job was never reported");
            }
            Thread.onSpinWait();
        }
        return reported.get();
    }

    /** Waits for the worker's progress to report that its agent started. */
    void awaitAgentStarted() {
        Instant deadline = Instant.now().plusSeconds(30);
        while (!started.get()) {
            if (Instant.now().isAfter(deadline)) {
                throw new AssertionError("the agent never started");
            }
            Thread.onSpinWait();
        }
    }

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

    /** The repo /api/worker/projects reports for alm; a test that maps or clones it overrides this with a real one. */
    String almRepo() {
        return "git@github.com:acme/alm.git";
    }

    Config config(String publicUrl) {
        return new Config("backend", dir, new Config.Telegram(List.of(), List.of(new Config.Group("backend", -100L,
                List.of(new Config.Member(100, "Bold"), new Config.Member(200, "Ali")), List.of("alm")))),
                new Config.Scheduler(2), Config.Worktrees.DEFAULT,
                new Config.Limits(new Config.RunLimits(Duration.ofMinutes(30), new BigDecimal("2")),
                        new Config.RunLimits(Duration.ofMinutes(60), new BigDecimal("10"))),
                Map.of("claude-code", new Config.Agent("claude")),
                List.of(new Config.Project("alm", null, almRepo(), null, "main", "claude-code", "opus",
                        "high", List.of(), null, null, null)),
                new Config.Delivery("Dispatch (backend)", "dispatch-backend@example.com", "gh"),
                new Config.Workers(publicUrl, 0), new Config.Secrets("token", null));
    }

    Groups groups() {
        return new Groups(List.of(new Config.Group("backend", -100L,
                List.of(new Config.Member(100, "Bold"), new Config.Member(200, "Ali")), List.of("alm"))));
    }
}
