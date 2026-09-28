package dispatch.store;

import dispatch.OwnerOnly;
import dispatch.Log;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.nio.file.attribute.UserPrincipal;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Duration;
import java.time.Instant;
import java.util.Arrays;
import java.util.HexFormat;
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
            "db/010-build-session.sql", "db/011-run-model.sql", "db/012-run-cause.sql",
            "db/013-attachments.sql", "db/014-agent-started.sql", "db/015-workers.sql",
            "db/016-worker-readiness.sql", "db/017-telegram-usernames.sql", "db/018-plan-answers.sql",
            "db/019-member-prefs.sql", "db/020-assistant.sql", "db/021-draft-discarded.sql", "db/022-worker-capacity.sql",
            "db/023-additions.sql", "db/024-merged.sql", "db/025-draft-source.sql", "db/026-outbox-edit-of.sql");

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
            prepareSqlite();
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

    /**
     * The SQLite driver unpacks its native library into a folder and loads it from there. Windows loads a library through
     * its ANSI code page, after the JDK spells the path out in full (an 8.3 short name is expanded again), so a temporary
     * folder with letters outside the code page, as under C:\Users\Өлзий, opened no database (run 36395743054). Such a
     * machine unpacks it into a folder of this user's own under ProgramData instead, whose full name the code page spells.
     * {@link #open} calls this; code that reaches SQLite through plain JDBC (the tests' SqlRows) calls it first.
     */
    public static void prepareSqlite() {
        if (!System.getProperty("os.name").startsWith("Windows") || System.getProperty("org.sqlite.tmpdir") != null) {
            return;
        }
        String temp = System.getProperty("java.io.tmpdir");
        if (loadable(temp)) {
            return;
        }
        String programData = System.getenv("ProgramData");
        if (programData == null) {
            throw refusal(temp, "ProgramData is not set", null);
        }
        Path folder = Path.of(programData, "dispatch-" + userKey());
        if (!loadable(folder.toString())) {
            throw refusal(temp, folder + " does not fit the code page either", null);
        }
        try {
            ownFolder(folder);
        } catch (IOException e) {
            throw refusal(temp, e.getMessage(), e);
        }
        removeLeftovers(folder);
        System.setProperty("org.sqlite.tmpdir", folder.toString());
    }

    private static DatabaseException refusal(String temp, String why, Exception cause) {
        return new DatabaseException("SQLite cannot be loaded from " + temp + ", whose name has letters Windows cannot load a"
                + " library from, and its own folder is not usable (" + why + "); set TMP for Dispatch to a folder named in plain"
                + " letters", cause);
    }

    /** Whether Windows can load a library from {@code path}: its full spelling fits the ANSI code page. */
    private static boolean loadable(String path) {
        try {
            String full = new File(path).getCanonicalPath();
            return Charset.forName(System.getProperty("native.encoding")).newEncoder().canEncode(full);
        } catch (IOException e) {
            return false;
        }
    }

    /**
     * Makes {@code folder} this user's alone, or checks that the one already there is: a plain folder (not a link someone
     * could later point elsewhere, between the driver's check and its load) owned by the user Dispatch runs as. A folder
     * it creates has one ACL entry, the user's, so nobody else can see into it or add to it.
     */
    static void ownFolder(Path folder) throws IOException {
        OwnerOnly.createDirectories(folder);
        BasicFileAttributes attributes = Files.readAttributes(folder, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
        if (!attributes.isDirectory() || attributes.isOther()) {
            throw new IOException(folder + " is a link, not a folder");
        }
        Path probe = Files.createTempFile("dispatch-owner", null);
        try {
            UserPrincipal owner = Files.getOwner(folder, LinkOption.NOFOLLOW_LINKS);
            if (!owner.equals(Files.getOwner(probe))) {
                throw new IOException(folder + " belongs to " + owner.getName());
            }
        } finally {
            Files.deleteIfExists(probe);
        }
    }

    /**
     * The driver removes a library it unpacked only at its next start, and only once that library's .lck file is gone,
     * which a forced stop (the Windows service's Stop and Restart) leaves behind; nobody cleans ProgramData for us. The
     * driver's files a day old go; one a running Dispatch has loaded is locked and stays until a later start.
     */
    static void removeLeftovers(Path folder) {
        Instant dayAgo = Instant.now().minus(Duration.ofDays(1));
        try (DirectoryStream<Path> files = Files.newDirectoryStream(folder, "sqlite-*")) {
            for (Path file : files) {
                try {
                    if (Files.getLastModifiedTime(file, LinkOption.NOFOLLOW_LINKS).toInstant().isBefore(dayAgo)) {
                        Files.deleteIfExists(file);
                    }
                } catch (IOException inUse) {
                    // A library a running Dispatch has loaded: it goes at a later start.
                }
            }
        } catch (IOException e) {
            Log.warn("sqlite.leftovers_failed", "folder", folder, "error", e.getMessage());
        }
    }

    /** The user's own folder name under ProgramData, in plain letters whatever the user's name is spelled in. */
    private static String userKey() {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(System.getProperty("user.name").getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest, 0, 8);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is always available", e);
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

    /**
     * One migration in its own transaction, with foreign keys off while it runs: rebuilding a table another table refers
     * to (SQLite cannot change a CHECK constraint any other way) drops the table they refer to. This is SQLite's own
     * procedure for such changes; foreign_key_check before the commit still refuses a migration that breaks a reference.
     */
    private void apply(int version, String resource) {
        String script = readResource(resource);
        // A no-op inside a transaction, so it is set before one starts.
        foreignKeys(false);
        setAutoCommit(false);
        try (Statement statement = connection.createStatement()) {
            for (String sql : statements(script)) {
                statement.execute(sql);
            }
            try (ResultSet broken = statement.executeQuery("PRAGMA foreign_key_check")) {
                if (broken.next()) {
                    throw new SQLException("leaves a broken reference from " + broken.getString("table") + " to " + broken.getString("parent"));
                }
            }
            statement.execute("PRAGMA user_version = " + version);
            connection.commit();
        } catch (SQLException e) {
            rollback(e);
            throw new DatabaseException("migration " + resource + " failed", e);
        } finally {
            setAutoCommit(true);
            foreignKeys(true);
        }
        Log.info("db.migrated", "version", version, "script", resource);
    }

    private void foreignKeys(boolean on) {
        try (Statement statement = connection.createStatement()) {
            statement.execute("PRAGMA foreign_keys = " + (on ? "ON" : "OFF"));
        } catch (SQLException e) {
            throw new DatabaseException("cannot turn foreign keys " + (on ? "on" : "off"), e);
        }
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
