package dispatch.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import com.fasterxml.jackson.databind.JsonNode;
import dispatch.Json;
import dispatch.Redactor;
import dispatch.agent.DataAccess;
import dispatch.agent.claude.ClaudeCodeAgent;
import dispatch.agent.claude.OwnerPlugins;
import dispatch.agent.sandbox.Bubblewrap;
import dispatch.agent.sandbox.Confinements;
import dispatch.agent.sandbox.Probe;
import dispatch.agent.sandbox.Sandbox;
import dispatch.agent.sandbox.SandboxSetting;
import dispatch.agent.sandbox.Sandboxes;
import dispatch.config.Config;
import dispatch.config.DataSource;
import dispatch.data.DataSources;
import dispatch.domain.Phase;
import dispatch.domain.Plan;
import dispatch.domain.Priority;
import dispatch.domain.Requester;
import dispatch.domain.RunKind;
import dispatch.domain.Task;
import dispatch.testing.GitFixture;
import dispatch.workspace.Delivery;
import dispatch.workspace.Gh;
import dispatch.workspace.Git;
import dispatch.workspace.Workspaces;
import java.io.IOException;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

/**
 * A report end to end (ADR 0043): real Claude Code in the real sandbox plans and then runs a report on a Mongo 7 in docker,
 * reached through a real SSH tunnel to a user-mode sshd. Off unless DISPATCH_LIVE_DATA=1, since it spends the account's quota
 * and starts a container. The made-up data has one planted gap, February 2025 at zero, that the report must warn about.
 */
class LiveDataReportTest {

    private static final String PROJECT = "webapp";
    private static final Set<String> WRITE_TOOLS = Set.of("insert-many", "update-many", "delete-many", "create-collection",
            "create-index", "drop-collection", "drop-database", "drop-index", "rename-collection");

    private Process sshd;
    private String container;

    @AfterEach
    void tearDown() throws Exception {
        if (sshd != null) {
            sshd.destroy();
            if (!sshd.waitFor(5, TimeUnit.SECONDS)) {
                sshd.destroyForcibly();
            }
        }
        if (container != null) {
            Result removed = run(List.of("docker", "rm", "-f", container));
            if (removed.exit() != 0) {
                System.out.println("LIVE data: could not remove container " + container + ": " + removed.output());
            }
        }
    }

    @Test
    @EnabledIfEnvironmentVariable(named = "DISPATCH_LIVE_DATA", matches = "1")
    @Timeout(value = 40, unit = TimeUnit.MINUTES)
    void aReportPlanIsApprovedThenRunThroughATunnelAndWarnsAboutTheGap() throws Exception {
        assumeTrue(onPath("docker") && Files.isExecutable(Path.of("/usr/sbin/sshd")) && onPath("claude"),
                "needs docker, /usr/sbin/sshd and claude");
        Sandbox sandbox = Sandboxes.detect(SandboxSetting.AUTO, Probe.system(System.getenv()));
        assumeTrue(sandbox instanceof Bubblewrap, "needs bwrap");
        Path root = Files.createTempDirectory(Path.of("target").toAbsolutePath(), "live-data");
        Path configFile = Files.createDirectories(root.resolve("config")).resolve("dispatch.yaml");

        String readOnlyPassword = UUID.randomUUID().toString().replace("-", "");
        String mongo = startMongo(readOnlyPassword);
        List<String> ssh = startSshd(Files.createDirectories(root.resolve("ssh")));
        DataSource data = new DataSource("live-data", mongo, PROJECT, "mongodb://ro:" + readOnlyPassword + "@" + mongo + "/" + PROJECT);
        Config.Project project = new Config.Project(PROJECT, null, null, null, "main", "claude-code", null, null, List.of(), null,
                null, null, null, "off", "off", "off", data);
        Config config = new Config(null, root.resolve("state"), null, null, null, null, Map.of(), List.of(project), null, null,
                null, null, null);

        GitFixture repos = GitFixture.create(root, PROJECT);
        seedRepository(repos);
        Git git = new Git("git", null, Duration.ofSeconds(30));
        Workspaces workspaces = new Workspaces(repos.stateDir, git);
        SkillsPlugin.install(workspaces.skillsPluginDir());
        ClaudeCodeAgent claude = new ClaudeCodeAgent("claude", System.getenv(), Duration.ofSeconds(10),
                Confinements.of(sandbox, repos.stateDir, configFile, List.of(), List.of()),
                OwnerPlugins.NONE);
        JobRunner runner = new JobRunner(workspaces, new Delivery(git, new Gh("gh", null, Duration.ofSeconds(30)), "Dispatch",
                "dispatch@example.com"), Map.of("claude-code", claude), Redactor.patternsOnly(), (fileRef, target) -> {
                    throw new IllegalStateException("no files in this test");
                }, new TestCommand(Confinements.of(sandbox, repos.stateDir, configFile, List.of(), List.of())),
                java.time.Clock.systemUTC(), DataSources.of(config, ssh));
        Job.Project on = new Job.Project(PROJECT, repos.origin.toString(), null, "main", "claude-code", List.of(), null, null, null);

        // 1. The plan explains the query and reads no data.
        Task asked = task(1, "How many visits per year?");
        UUID session = UUID.randomUUID();
        Job planJob = job(asked, 1, RunKind.PLAN, on, null, session, false, Prompts.plan(asked), Duration.ofMinutes(10), "2");
        JobRecorder planEvents = new JobRecorder();
        JobResult planned = runner.run(planJob, planEvents, new ActiveRuns().register(1, 1));
        System.out.println("LIVE data plan: " + planned.outcome() + " " + planned.failureDetail() + " cost="
                + (planned.agent() == null ? null : planned.agent().costUsd()) + "\n  " + (planned.agent() == null ? null
                : planned.agent().structuredOutput()));
        assertEquals(JobResult.Outcome.SUCCEEDED, planned.outcome(), planned.failureDetail());
        Plan plan = Plan.parse(planned.agent().structuredOutput());
        assertEquals(Plan.Result.REPORT, plan.result(), planned.agent().structuredOutput());
        assertTrue(String.join("\n", plan.steps()).contains("visits"), plan.steps().toString());
        String planLog = Files.readString(Path.of(workspaces.runLogBase(1, 1) + ".jsonl"));
        assertFalse(planLog.contains(DataAccess.TOOL_PREFIX + "aggregate"), "a plan never sees the aggregate tool");

        // 2. Approved: the report runs in the planning session and reads the data.
        Job reportJob = job(asked, 2, RunKind.REPORT, on, planEvents.worktree, session, true, Prompts.report(asked),
                Duration.ofMinutes(20), "5");
        JobResult reported = runner.run(reportJob, new JobRecorder(), new ActiveRuns().register(1, 2));
        System.out.println("LIVE data report: " + reported.outcome() + " " + reported.failureDetail() + " cost="
                + (reported.agent() == null ? null : reported.agent().costUsd()));
        assertEquals(JobResult.Outcome.SUCCEEDED, reported.outcome(), reported.failureDetail());
        String html = Json.read(reported.agent().structuredOutput()).path("html").asText("");
        Path saved = Path.of("target", "live-data-report.html").toAbsolutePath();
        Files.writeString(saved, html);
        System.out.println("LIVE data report HTML: " + saved);
        assertTrue(html.stripLeading().toLowerCase().startsWith("<!doctype html"), html.substring(0, Math.min(200, html.length())));
        assertTrue(html.contains("2024") && html.contains("2025"), "both years are reported");
        assertTrue(namesFebruary2025(html), "the warning names the planted gap");
        Set<String> dataTools = initDataTools(Files.readString(Path.of(workspaces.runLogBase(1, 2) + ".jsonl")));
        assertEquals(new TreeSet<>(DataAccess.Level.FULL.allowedTools()), dataTools, "a report gets exactly the FULL tools");
        assertTrue(dataTools.stream().noneMatch(WRITE_TOOLS::contains), dataTools.toString());

        // 3. A code task on the same data project is planned as a change, not a report.
        Task fix = task(2, "Fix the null check in the visits loader");
        JobResult fixPlanned = runner.run(job(fix, 1, RunKind.PLAN, on, null, UUID.randomUUID(), false, Prompts.plan(fix),
                Duration.ofMinutes(10), "2"), new JobRecorder(), new ActiveRuns().register(2, 1));
        System.out.println("LIVE data fix plan: " + fixPlanned.outcome() + " " + fixPlanned.failureDetail() + " cost="
                + (fixPlanned.agent() == null ? null : fixPlanned.agent().costUsd()));
        assertEquals(JobResult.Outcome.SUCCEEDED, fixPlanned.outcome(), fixPlanned.failureDetail());
        assertEquals(Plan.Result.PLAN, Plan.parse(fixPlanned.agent().structuredOutput()).result(),
                fixPlanned.agent().structuredOutput());

        // 4. The read-only user cannot write, whatever reaches it.
        Result insert = run(List.of("docker", "exec", container, "mongosh", "--quiet",
                "mongodb://ro:" + readOnlyPassword + "@127.0.0.1:27017/" + PROJECT, "--eval",
                "try { db.visits.insertOne({x: 1}); print('written') } catch (e) { print(e.codeName); quit(1) }"));
        assertNotEquals(0, insert.exit(), insert.output());
        assertEquals("Unauthorized", insert.output().strip(), insert.output());
    }

    private static boolean namesFebruary2025(String html) {
        return html.contains("2025-02") || html.contains("2-р сар") || html.contains("February 2025") || html.contains("Feb 2025");
    }

    /** The dispatch_data tools the run's init event listed, without their prefix. */
    private static Set<String> initDataTools(String streamLog) {
        for (String line : streamLog.split("\n")) {
            if (!line.contains("\"subtype\":\"init\"")) {
                continue;
            }
            Set<String> tools = new TreeSet<>();
            for (JsonNode tool : Json.read(line).path("tools")) {
                if (tool.asText().startsWith(DataAccess.TOOL_PREFIX)) {
                    tools.add(tool.asText().substring(DataAccess.TOOL_PREFIX.length()));
                }
            }
            return tools;
        }
        throw new AssertionError("no init event in the run's stream log");
    }

    /** Mongo 7 with auth on a random local port, a read-only user {@code ro} on webapp, and two years of daily visits. */
    private String startMongo(String readOnlyPassword) throws Exception {
        String rootPassword = UUID.randomUUID().toString();
        container = "dispatch-live-data-" + UUID.randomUUID().toString().substring(0, 8);
        Result started = run(List.of("docker", "run", "-d", "--rm", "--name", container, "-p", "127.0.0.1::27017",
                "-e", "MONGO_INITDB_ROOT_USERNAME=root", "-e", "MONGO_INITDB_ROOT_PASSWORD=" + rootPassword, "mongo:7.0"));
        assertEquals(0, started.exit(), started.output());
        // Through the container's own address: the entrypoint's first mongod, which creates root, listens on 127.0.0.1 only,
        // so an answer there means the real one is up.
        String address = run(List.of("docker", "inspect", "-f", "{{range .NetworkSettings.Networks}}{{.IPAddress}}{{end}}",
                container)).output().strip();
        List<String> asRoot = List.of("docker", "exec", container, "mongosh", "--quiet", "--host", address, "-u", "root",
                "-p", rootPassword, "--authenticationDatabase", "admin", "--eval");
        Instant deadline = Instant.now().plusSeconds(90);
        while (!ping(asRoot)) {
            assertTrue(Instant.now().isBefore(deadline), "mongo did not start within 90 s");
            Thread.sleep(1000);
        }
        // Zero views through February 2025: the planted gap.
        String seed = """
                const webapp = db.getSiblingDB('webapp');
                webapp.createUser({user: 'ro', pwd: '%s', roles: [{role: 'read', db: 'webapp'}]});
                const docs = [];
                for (let d = new Date(Date.UTC(2024, 0, 1)); d <= new Date(Date.UTC(2025, 11, 31)); d = new Date(d.getTime() + 86400000)) {
                  const gap = d.getUTCFullYear() === 2025 && d.getUTCMonth() === 1;
                  docs.push({date: d, views: gap ? 0 : 120 + (d.getUTCDate() * 7) %% 50});
                }
                webapp.visits.insertMany(docs);
                webapp.visits.createIndex({date: 1});
                print(webapp.visits.countDocuments());
                """.formatted(readOnlyPassword);
        List<String> command = new ArrayList<>(asRoot);
        command.add(seed);
        Result seeded = run(command);
        assertTrue(seeded.output().strip().endsWith("731"), seeded.output());
        String port = run(List.of("docker", "port", container, "27017/tcp")).output().strip().lines().findFirst().orElseThrow();
        return "127.0.0.1:" + port.substring(port.lastIndexOf(':') + 1);
    }

    private static boolean ping(List<String> asRoot) throws Exception {
        List<String> command = new ArrayList<>(asRoot);
        command.add("db.runCommand({ping: 1}).ok");
        Result result = run(command);
        return result.exit() == 0 && result.output().strip().endsWith("1");
    }

    /** A user-mode sshd on a random port that lets this user in with a throwaway key; returns the ssh command reaching it. */
    private List<String> startSshd(Path dir) throws Exception {
        assertEquals(0, run(List.of("ssh-keygen", "-q", "-t", "ed25519", "-N", "", "-f", dir.resolve("host_key").toString())).exit());
        assertEquals(0, run(List.of("ssh-keygen", "-q", "-t", "ed25519", "-N", "", "-f", dir.resolve("user_key").toString())).exit());
        Files.copy(dir.resolve("user_key.pub"), dir.resolve("authorized_keys"));
        int port;
        try (java.net.ServerSocket socket = new java.net.ServerSocket(0, 1, java.net.InetAddress.getLoopbackAddress())) {
            port = socket.getLocalPort();
        }
        Path sshdConfig = dir.resolve("sshd_config");
        Files.writeString(sshdConfig, """
                Port %d
                ListenAddress 127.0.0.1
                HostKey %s
                AuthorizedKeysFile %s
                PidFile none
                StrictModes no
                PasswordAuthentication no
                KbdInteractiveAuthentication no
                AllowTcpForwarding yes
                """.formatted(port, dir.resolve("host_key"), dir.resolve("authorized_keys")));
        sshd = new ProcessBuilder("/usr/sbin/sshd", "-D", "-e", "-f", sshdConfig.toString())
                .redirectErrorStream(true).redirectOutput(dir.resolve("sshd.log").toFile()).start();
        Path sshConfig = dir.resolve("ssh_config");
        Files.writeString(sshConfig, """
                Host live-data
                  HostName 127.0.0.1
                  Port %d
                  IdentityFile %s
                  IdentitiesOnly yes
                  StrictHostKeyChecking no
                  UserKnownHostsFile /dev/null
                  LogLevel ERROR
                """.formatted(port, dir.resolve("user_key")));
        List<String> ssh = List.of("ssh", "-F", sshConfig.toString());
        Instant deadline = Instant.now().plusSeconds(15);
        List<String> probe = new ArrayList<>(ssh);
        probe.addAll(List.of("-o", "BatchMode=yes", "live-data", "true"));
        while (run(probe).exit() != 0) {
            assertTrue(sshd.isAlive() && Instant.now().isBefore(deadline), "sshd did not let us in: " + Files.readString(dir.resolve("sshd.log")));
            Thread.sleep(300);
        }
        return ssh;
    }

    /** A made-up web app whose visits loader has a wrong null check, for the code task. */
    private static void seedRepository(GitFixture repos) throws IOException {
        Files.writeString(repos.seed.resolve("README.md"), """
                # webapp

                A small web app. Daily visit counts live in the MongoDB collection `visits`, one document per day:
                `{date, views}`. `src/visits.js` loads them for the dashboard.
                """);
        Files.createDirectories(repos.seed.resolve("src"));
        Files.writeString(repos.seed.resolve("src/visits.js"), """
                // Loads one day's views for the dashboard.
                export async function loadVisits(db, day) {
                  const doc = await db.collection('visits').findOne({ date: day });
                  if (doc === undefined) {
                    return 0;
                  }
                  return doc.views;
                }
                """);
        repos.commitAndPush("webapp with a visits loader");
    }

    private static Task task(long id, String description) {
        Instant now = Instant.now();
        return new Task(id, PROJECT, description, description, Phase.PLANNING, Priority.NORMAL, new Requester("telegram:100", "Bold"),
                "telegram:100/" + id, "telegram:100", UUID.randomUUID(), null, "main", null, null, null, null, null, null, null, now,
                null, null, now, null);
    }

    private static Job job(Task task, int seq, RunKind kind, Job.Project project, String worktree, UUID session, boolean resume,
                           String prompt, Duration timeout, String budgetUsd) {
        return new Job(task.id(), seq, kind, project, "main", null, worktree, null, session, resume, prompt, "sonnet", null,
                timeout.toMillis(), new BigDecimal(budgetUsd), List.of(), "dispatch #" + task.id() + ": " + task.title(),
                List.of(), null, null, null, null, null, null);
    }

    private static boolean onPath(String command) {
        try {
            return run(List.of("sh", "-c", "command -v " + command)).exit() == 0;
        } catch (Exception e) {
            return false;
        }
    }

    private record Result(int exit, String output) {
    }

    private static Result run(List<String> command) throws IOException, InterruptedException {
        Process process = new ProcessBuilder(command).redirectErrorStream(true).redirectInput(ProcessBuilder.Redirect.from(
                new java.io.File("/dev/null"))).start();
        String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        return new Result(process.waitFor(), output);
    }

    /** Keeps the worktree the planning run made, which the report run continues in. */
    private static final class JobRecorder implements JobEvents {
        private String worktree;

        @Override
        public void worktreeCreated(String worktree, String baseSha) {
            this.worktree = worktree;
        }

        @Override
        public void agentStarted(Long pid, Instant processStart) {
        }
    }
}
