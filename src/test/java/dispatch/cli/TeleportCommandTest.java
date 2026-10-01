package dispatch.cli;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dispatch.domain.Phase;
import dispatch.domain.Priority;
import dispatch.domain.Requester;
import dispatch.domain.RunCause;
import dispatch.domain.RunKind;
import dispatch.store.Database;
import dispatch.store.Runs;
import dispatch.store.Tasks;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class TeleportCommandTest {

    private static final Instant T0 = Instant.parse("2026-09-30T10:00:00Z");
    private static final UUID PLAN_SESSION = UUID.fromString("f21d5473-1d77-44eb-a148-3aa616a104ad");
    private static final UUID BUILD_SESSION = UUID.fromString("20dec587-7b17-4987-9466-ef8fb42b9afc");

    @TempDir
    Path dir;

    private Database db;
    private Path worktree;

    @BeforeEach
    void open() throws Exception {
        db = Database.open(dir.resolve("dispatch.db"));
        db.migrate();
        worktree = Files.createDirectories(dir.resolve("worktrees/15"));
    }

    @AfterEach
    void close() {
        db.close();
    }

    /** The token lives in dispatch.env, not the shell: teleport reads it there, as `dispatch run` does. */
    @Test
    void readsTheSecretsBesideTheConfig() throws Exception {
        Path config = dir.resolve("dispatch.yaml");
        Files.writeString(config, new String(TeleportCommandTest.class.getResourceAsStream("/personal.yaml").readAllBytes())
                .replace("STATE_DIR", dir.toString().replace("'", "''"))
                .replace("CLONE", dir.resolve("work/alm").toString().replace("'", "''")));
        SecretsFile.write(SecretsFile.beside(config), Map.of("TELEGRAM_BOT_TOKEN", "123456789" + ":AAH-fake-token-for-tests-only-0123456789"));

        CliException error = assertThrows(CliException.class,
                () -> TeleportCommand.run(new Cli.Teleport(config, 99, false), Map.of("PATH", "/usr/bin")));

        assertEquals("no task #99 in this instance", error.getMessage(), "the config loaded and the task was looked up");
    }

    @Test
    void opensTheBuildSessionInTheTasksWorktree() {
        long id = task(BUILD_SESSION, "SUCCEEDED");

        TeleportCommand.Target target = target(id, false, "claude-code");

        assertEquals(worktree, target.workdir());
        assertEquals(List.of("claude", "--resume", BUILD_SESSION.toString()), target.command());
    }

    @Test
    void thePlanFlagOrATaskNeverBuiltOpensThePlanningSession() {
        assertEquals(PLAN_SESSION.toString(), target(task(BUILD_SESSION, "SUCCEEDED"), true, "claude-code").command().getLast());
        assertEquals(PLAN_SESSION.toString(), target(task(null, "SUCCEEDED"), false, "claude-code").command().getLast());
    }

    @Test
    void refusesWhileARunIsActiveForAnotherAgentAndWithoutAWorktree() {
        assertRefused(task(BUILD_SESSION, "RUNNING"), "claude-code", "is running");
        assertRefused(task(BUILD_SESSION, "QUEUED"), "claude-code", "is running");
        assertRefused(task(BUILD_SESSION, "SUCCEEDED"), "codex", "Claude Code");
        long gone = task(BUILD_SESSION, "SUCCEEDED");
        db.transaction(tx -> tx.update("UPDATE task SET worktree = ? WHERE id = ?", dir.resolve("worktrees/gone").toString(), gone));
        assertRefused(gone, "claude-code", "worktree");
        assertRefused(999, "claude-code", "no task #999");
    }

    @Test
    void aTaskAWorkerRanIsOpenedOnThatComputer() {
        long id = task(BUILD_SESSION, "SUCCEEDED");
        db.transaction(tx -> {
            long worker = tx.insert("INSERT INTO worker (member_ref, name, key_sha256, created_at) VALUES ('telegram:1', 'ann-laptop', 'h', ?)", T0);
            tx.update("UPDATE task SET worker_id = ? WHERE id = ?", worker, id);
        });

        assertRefused(id, "claude-code", "ann-laptop");
    }

    private TeleportCommand.Target target(long id, boolean plan, String agent) {
        return db.transactionReturning(tx -> TeleportCommand.target(tx, id, plan, agent, "claude"));
    }

    private void assertRefused(long id, String agent, String reason) {
        CliException refused = org.junit.jupiter.api.Assertions.assertThrows(CliException.class, () -> target(id, false, agent));
        assertTrue(refused.getMessage().contains(reason), refused.getMessage());
    }

    private long task(UUID buildSession, String runStatus) {
        return db.transactionReturning(tx -> {
            long id = Tasks.insert(tx, new Tasks.NewTask("alm", "t", "t", new Requester("telegram:1", "Bold"),
                    "telegram:-1/" + UUID.randomUUID(), "telegram:-1", PLAN_SESSION, "main", Priority.NORMAL), Phase.COMPLETED, T0);
            tx.update("UPDATE task SET worktree = ?, build_session_id = ? WHERE id = ?", worktree.toString(),
                    buildSession == null ? null : buildSession.toString(), id);
            Runs.insert(tx, new Runs.NewRun(id, 1, RunKind.PLAN, RunCause.TASK, "t", new Requester("telegram:1", "Bold")), T0);
            tx.update("UPDATE run SET status = ? WHERE task_id = ? AND seq = 1", runStatus, id);
            return id;
        });
    }
}
