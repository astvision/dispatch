package dispatch.store;

import dispatch.OwnerOnly;
import dispatch.Log;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Consumer;
import java.util.function.Function;

/**
 * The SQLite state file. All access goes through one connection and one lock.
 * ponytail: single serialized connection; fine for one team's task volume, add a read pool if /tasks ever lags.
 */
public final class Database implements AutoCloseable {

    private static final List<String> MIGRATIONS = List.of("db/001-init.sql", "db/002-execution.sql", "db/003-private-messages.sql", "db/004-priority.sql", "db/005-drafts.sql", "db/006-topics.sql",
            "db/007-outbox-edits.sql", "db/008-split-drafts.sql", "db/009-join-requests.sql",
            "db/010-build-session.sql", "db/011-run-model.sql", "db/012-run-cause.sql");

    private final Connection connection;
    private final ReentrantLock lock = new ReentrantLock();

    private Database(Connection connection) {
        this.connection = connection;
    }

    /**
     * Creates the file owner-only when missing. SQLite gives its -wal and -shm files the same POSIX permissions; on Windows
     * they inherit the owner-only state directory's ACL.
     */
    public static Database open(Path file) {
        try {
            if (!Files.exists(file)) {
                OwnerOnly.createFile(file);
            }
            Connection connection = DriverManager.getConnection("jdbc:sqlite:" + file);
            try (Statement statement = connection.createStatement()) {
                statement.execute("PRAGMA journal_mode = WAL");
                statement.execute("PRAGMA synchronous = FULL");
                statement.execute("PRAGMA foreign_keys = ON");
                statement.execute("PRAGMA busy_timeout = 5000");
            }
            return new Database(connection);
        } catch (SQLException | IOException e) {
            throw new DatabaseException("cannot open database " + file, e);
        }
    }

    /** Applies migrations newer than the file's PRAGMA user_version, each in its own transaction. */
    public void migrate() {
        lock.lock();
        try {
            for (int version = userVersion() + 1; version <= MIGRATIONS.size(); version++) {
                apply(version, MIGRATIONS.get(version - 1));
            }
        } finally {
            lock.unlock();
        }
    }

    public void transaction(Consumer<Tx> work) {
        transactionReturning(tx -> {
            work.accept(tx);
            return null;
        });
    }

    public <T> T transactionReturning(Function<Tx, T> work) {
        if (lock.isHeldByCurrentThread()) {
            throw new IllegalStateException("nested transaction: pass the open Tx down instead of starting another");
        }
        T result;
        List<Runnable> afterCommit;
        lock.lock();
        try {
            Tx tx = new Tx(connection);
            setAutoCommit(false);
            try {
                result = work.apply(tx);
                connection.commit();
            } catch (RuntimeException | Error e) {
                rollback(e);
                throw e;
            } catch (SQLException e) {
                rollback(e);
                throw new DatabaseException("commit failed", e);
            } finally {
                setAutoCommit(true);
            }
            afterCommit = tx.afterCommitActions();
        } finally {
            lock.unlock();
        }
        runAfterCommit(afterCommit);
        return result;
    }

    @Override
    public void close() {
        lock.lock();
        try {
            connection.close();
        } catch (SQLException e) {
            throw new DatabaseException("cannot close database", e);
        } finally {
            lock.unlock();
        }
    }

    private int userVersion() {
        try (Statement statement = connection.createStatement(); ResultSet resultSet = statement.executeQuery("PRAGMA user_version")) {
            return resultSet.getInt(1);
        } catch (SQLException e) {
            throw new DatabaseException("cannot read schema version", e);
        }
    }

    private void apply(int version, String resource) {
        String script = readResource(resource);
        setAutoCommit(false);
        try (Statement statement = connection.createStatement()) {
            for (String sql : statements(script)) {
                statement.execute(sql);
            }
            statement.execute("PRAGMA user_version = " + version);
            connection.commit();
        } catch (SQLException e) {
            rollback(e);
            throw new DatabaseException("migration " + resource + " failed", e);
        } finally {
            setAutoCommit(true);
        }
        Log.info("db.migrated", "version", version, "script", resource);
    }

    /** ponytail: splits on ';' at line end; migrations must not contain triggers or ';' inside literals. */
    private static List<String> statements(String script) {
        String withoutComments = script.lines()
                .filter(line -> !line.strip().startsWith("--"))
                .reduce("", (sql, line) -> sql + line + "\n");
        return Arrays.stream(withoutComments.split(";\\s*(\\n|$)"))
                .map(String::strip)
                .filter(sql -> !sql.isEmpty())
                .toList();
    }

    private static String readResource(String resource) {
        try (InputStream in = Database.class.getClassLoader().getResourceAsStream(resource)) {
            if (in == null) {
                throw new IllegalStateException("migration resource missing: " + resource);
            }
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new IllegalStateException("cannot read migration " + resource, e);
        }
    }

    private void setAutoCommit(boolean autoCommit) {
        try {
            connection.setAutoCommit(autoCommit);
        } catch (SQLException e) {
            throw new DatabaseException("cannot set auto-commit to " + autoCommit, e);
        }
    }

    private void rollback(Throwable cause) {
        try {
            connection.rollback();
        } catch (SQLException rollbackFailure) {
            cause.addSuppressed(rollbackFailure);
        }
    }

    private static void runAfterCommit(List<Runnable> actions) {
        for (Runnable action : actions) {
            try {
                action.run();
            } catch (RuntimeException e) {
                // The transaction is already committed; report the failed side effect and keep running the rest.
                Log.error("db.after_commit_failed", e);
            }
        }
    }
}
