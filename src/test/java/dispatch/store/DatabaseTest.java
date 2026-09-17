package dispatch.store;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dispatch.domain.Phase;
import java.math.BigDecimal;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class DatabaseTest {

    @TempDir
    Path dir;

    private Database db;

    @BeforeEach
    void open() {
        db = Database.open(dir.resolve("dispatch.db"));
        db.migrate();
    }

    @AfterEach
    void close() {
        db.close();
    }

    @Test
    void afterCommitActionsRunOnlyOnceTheWorkIsCommitted() {
        List<String> seenByAction = new ArrayList<>();

        db.transaction(tx -> {
            tx.update("INSERT INTO kv (key, value) VALUES (?, ?)", "telegram.offset", "42");
            tx.afterCommit(() -> seenByAction.add(readOffset().orElse("missing")));
            assertTrue(seenByAction.isEmpty());
        });

        assertEquals(List.of("42"), seenByAction);
    }

    @Test
    void failingWorkIsRolledBackAndItsAfterCommitActionsAreDropped() {
        AtomicBoolean actionRan = new AtomicBoolean();

        assertThrows(IllegalStateException.class, () -> db.transaction(tx -> {
            tx.update("INSERT INTO kv (key, value) VALUES (?, ?)", "telegram.offset", "42");
            tx.afterCommit(() -> actionRan.set(true));
            throw new IllegalStateException("handler bug");
        }));

        assertEquals(Optional.empty(), readOffset());
        assertTrue(!actionRan.get());
    }

    @Test
    void migratingAgainKeepsExistingData() {
        db.transaction(tx -> tx.update("INSERT INTO kv (key, value) VALUES ('k', 'v')"));

        db.migrate();

        assertEquals("v", db.transactionReturning(tx -> tx.one("SELECT value FROM kv WHERE key = 'k'", row -> row.string("value"))).orElseThrow());
    }

    @Test
    void version1DatabaseIsUpgradedInPlace() throws Exception {
        Path file = databaseAtVersion(1, """
                INSERT INTO task (id, project, title, description, phase, requester_ref, requester_name, origin_ref, chat_ref,
                                  session_id, base_branch, created_at, updated_at)
                VALUES (1, 'alm', 't', 't', 'AWAITING_APPROVAL', 'telegram:1', 'Bold', 'telegram:-1/5', 'telegram:-1',
                        '63d36fba-124d-4737-8020-d37d4998abca', 'main', '2026-09-17T10:00:00.000Z', '2026-09-17T10:00:00.000Z')""", """
                INSERT INTO run (task_id, seq, kind, status, instruction, requested_by, queued_at)
                VALUES (1, 1, 'PLAN', 'SUCCEEDED', 't', 'telegram:1', '2026-09-17T10:00:00.000Z')""");

        try (Database upgraded = Database.open(file)) {
            upgraded.migrate();

            dispatch.domain.Task task = upgraded.transactionReturning(tx -> Tasks.find(tx, 1)).orElseThrow();
            assertEquals(dispatch.domain.Phase.AWAITING_APPROVAL, task.phase());
            assertNull(task.prUrl());
            dispatch.domain.Run run = upgraded.transactionReturning(tx -> Runs.find(tx, 1, 1)).orElseThrow();
            assertEquals("telegram:1", run.requestedBy());
            assertNull(run.requestedByName(), "runs recorded before version 2 have no requester name");
        }
    }

    @Test
    void version2DatabaseDropsUnsentTaskListsThatNoLongerRender() throws Exception {
        Path file = databaseAtVersion(2, """
                INSERT INTO outbox (id, kind, chat_ref, payload, status, next_attempt_at, created_at)
                VALUES (1, 'TASK_LIST', 'telegram:-1', '{"tasks":[]}', 'PENDING', '2026-09-17T10:00:00.000Z', '2026-09-17T10:00:00.000Z')""", """
                INSERT INTO outbox (id, kind, chat_ref, payload, status, next_attempt_at, created_at)
                VALUES (2, 'HELP', 'telegram:-1', '{"projects":[]}', 'PENDING', '2026-09-17T10:00:01.000Z', '2026-09-17T10:00:01.000Z')""");

        try (Database upgraded = Database.open(file)) {
            upgraded.migrate();

            Outbox.Message next = upgraded.transactionReturning(tx -> Outbox.nextDue(tx, Instant.parse("2026-09-17T11:00:00Z")))
                    .orElseThrow();
            assertEquals(2, next.id(), "the removed kind is not picked up, so the sender cannot crash on it");
            assertNull(next.fallbackChatRef());
        }
        assertEquals("FAILED", dispatch.testing.SqlRows.single(file, "SELECT status FROM outbox WHERE id = 1").get("status"));
    }

    /** A state file as an older Dispatch left it: the first {@code version} migrations applied, then {@code inserts}. */
    private Path databaseAtVersion(int version, String... inserts) throws Exception {
        Path file = dir.resolve("v" + version + ".db");
        try (java.sql.Connection connection = java.sql.DriverManager.getConnection("jdbc:sqlite:" + file);
             java.sql.Statement statement = connection.createStatement()) {
            String[] scripts = {"/db/001-init.sql", "/db/002-execution.sql"};
            for (int i = 0; i < version; i++) {
                try (java.io.InputStream script = getClass().getResourceAsStream(scripts[i])) {
                    String sqlText = new String(script.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
                    for (String sql : sqlText.split(";\\s*\\n")) {
                        String withoutComments = sql.lines().filter(line -> !line.strip().startsWith("--"))
                                .reduce("", (joined, line) -> joined + line + "\n");
                        if (!withoutComments.isBlank()) {
                            statement.execute(withoutComments);
                        }
                    }
                }
            }
            statement.execute("PRAGMA user_version = " + version);
            for (String insert : inserts) {
                statement.execute(insert);
            }
        }
        return file;
    }

    @Test
    void nestedTransactionIsRejected() {
        IllegalStateException error = assertThrows(IllegalStateException.class,
                () -> db.transaction(outer -> db.transaction(inner -> inner.update("SELECT 1"))));

        assertTrue(error.getMessage().contains("nested"), error.getMessage());
    }

    @Test
    void typedParametersRoundTrip() {
        UUID session = UUID.fromString("63d36fba-124d-4737-8020-d37d4998abca");
        Instant at = Instant.parse("2026-09-17T10:15:30.123456Z");

        db.transaction(tx -> {
            tx.update("CREATE TEMP TABLE sample (at TEXT, phase TEXT, session TEXT, cost TEXT, pid INTEGER, missing TEXT)");
            tx.update("INSERT INTO sample VALUES (?, ?, ?, ?, ?, ?)", at, Phase.AWAITING_APPROVAL, session, new BigDecimal("0.168185"), 81234L, null);
        });

        Sample sample = db.transactionReturning(tx -> tx.one("SELECT * FROM sample", row -> new Sample(
                row.string("at"), row.instant("at"), row.enumValue("phase", Phase.class), row.uuid("session"),
                row.decimal("cost"), row.longOrNull("pid"), row.instant("missing")))).orElseThrow();

        assertEquals("2026-09-17T10:15:30.123Z", sample.rawAt());
        assertEquals(Instant.parse("2026-09-17T10:15:30.123Z"), sample.at());
        assertEquals(Phase.AWAITING_APPROVAL, sample.phase());
        assertEquals(session, sample.session());
        assertEquals(new BigDecimal("0.168185"), sample.cost());
        assertEquals(81234L, sample.pid());
        assertNull(sample.missing());
    }

    @Test
    void newDatabaseFileIsReadableByItsOwnerOnly() throws Exception {
        Path file = dir.resolve("fresh.db");

        try (Database fresh = Database.open(file)) {
            fresh.migrate();
            fresh.transaction(tx -> tx.update("INSERT INTO kv (key, value) VALUES ('k', 'v')"));

            Path wal = dir.resolve("fresh.db-wal");
            assertTrue(java.nio.file.Files.exists(wal), "WAL file expected while the database is open");
            assertEquals("rw-------", java.nio.file.attribute.PosixFilePermissions.toString(java.nio.file.Files.getPosixFilePermissions(wal)));
        }

        assertEquals("rw-------", java.nio.file.attribute.PosixFilePermissions.toString(java.nio.file.Files.getPosixFilePermissions(file)));
    }

    @Test
    void insertReturnsGeneratedId() {
        long first = db.transactionReturning(tx -> tx.insert("INSERT INTO kv (key, value) VALUES ('a', '1')"));
        long second = db.transactionReturning(tx -> tx.insert("INSERT INTO kv (key, value) VALUES ('b', '2')"));

        assertEquals(first + 1, second);
    }

    private Optional<String> readOffset() {
        return db.transactionReturning(tx -> tx.one("SELECT value FROM kv WHERE key = 'telegram.offset'", row -> row.string("value")));
    }

    private record Sample(String rawAt, Instant at, Phase phase, UUID session, BigDecimal cost, Long pid, Instant missing) {
    }
}
