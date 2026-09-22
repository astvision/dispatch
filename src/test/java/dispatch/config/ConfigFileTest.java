package dispatch.config;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.DisabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;

class ConfigFileTest {

    // Split, so secret scanners never see a token-shaped literal in this file.
    private static final Map<String, String> ENV = Map.of("TELEGRAM_BOT_TOKEN", "123456789" + ":AAH-fake-token-for-tests-only-0123456789");
    private static final String YAML = """
            team: acme
            stateDir: STATE

            telegram:
              admins:
                - 100
              groups:
                - name: acme
                  chatId: -1001234567890
                  members:
                    - id: 100
                      name: 'Bold'
                  projects:
                    - alm

            delivery:
              authorName: 'Dispatch (acme)'
              authorEmail: 'dispatch@example.com'

            scheduler:
              maxConcurrentRuns: 2

            limits:
              plan:
                timeout: 15m
                budgetUsd: 2
              execute:
                timeout: 60m
                budgetUsd: 10

            agents:
              claude-code:
                command: 'claude'

            projects:
              - name: alm
                path: ALM
                baseBranch: main
                agent: claude-code
            """;

    @TempDir
    Path dir;

    private Path file;

    @BeforeEach
    void setUp() throws IOException {
        file = dir.resolve("dispatch.yaml");
        Files.writeString(file, YAML.replace("STATE", quoted(dir.resolve("state"))).replace("ALM", quoted(dir.resolve("alm"))));
    }

    @Test
    void editAppliesTheChangeAndReplacesTheFileOnlyWhenItValidates() {
        Config config = ConfigFile.edit(file, ENV, text -> ConfigEdit.set(text, ConfigEdit.At.of("scheduler", "maxConcurrentRuns"), "5"));

        assertEquals(5, config.scheduler().maxConcurrentRuns());
        assertEquals(5, ConfigLoader.load(file, ENV).scheduler().maxConcurrentRuns());
    }

    @Test
    void aChangeThatFailsValidationLeavesTheFileUnchanged() throws IOException {
        String before = Files.readString(file);

        assertThrows(ConfigException.class,
                () -> ConfigFile.edit(file, ENV, text -> ConfigEdit.set(text, ConfigEdit.At.of("scheduler", "maxConcurrentRuns"), "0")));

        assertEquals(before, Files.readString(file));
    }

    @Test
    @DisabledOnOs(value = OS.WINDOWS, disabledReason = "POSIX permissions")
    void aSaveThatActuallyChangesTheFileKeepsItsPermissions() throws Exception {
        Files.setPosixFilePermissions(file, PosixFilePermissions.fromString("rw-r-----"));

        ConfigFile.edit(file, ENV, text -> ConfigEdit.set(text, ConfigEdit.At.of("scheduler", "maxConcurrentRuns"), "5"));

        assertEquals("rw-r-----", PosixFilePermissions.toString(Files.getPosixFilePermissions(file)),
                "the replaced file keeps the original's permissions, not the draft's default ones");
    }

    @Test
    @DisabledOnOs(value = OS.WINDOWS, disabledReason = "POSIX permissions")
    void anUnchangedEditNeverRewritesTheFile() throws Exception {
        Files.setPosixFilePermissions(file, PosixFilePermissions.fromString("rw-------"));
        FileTime before = Files.getLastModifiedTime(file);

        Config config = ConfigFile.edit(file, ENV, text -> text);

        assertEquals(2, config.scheduler().maxConcurrentRuns(), "the unchanged text is still validated and its config returned");
        assertEquals(before, Files.getLastModifiedTime(file), "no replace happened: the file was never rewritten");
        assertEquals("rw-------", PosixFilePermissions.toString(Files.getPosixFilePermissions(file)), "so its permissions stayed too");
    }

    @Test
    void twoConcurrentEditsThroughEditBothLand() throws Exception {
        CyclicBarrier barrier = new CyclicBarrier(2);
        CompletableFuture<Void> first = CompletableFuture.runAsync(() -> {
            await(barrier);
            ConfigFile.edit(file, ENV, text -> ConfigEdit.append(text, ConfigEdit.At.of("telegram", "admins"), "201"));
        });
        CompletableFuture<Void> second = CompletableFuture.runAsync(() -> {
            await(barrier);
            ConfigFile.edit(file, ENV, text -> ConfigEdit.append(text, ConfigEdit.At.of("telegram", "admins"), "202"));
        });

        CompletableFuture.allOf(first, second).get(10, TimeUnit.SECONDS);

        List<Long> admins = ConfigLoader.load(file, ENV).telegram().admins();
        assertEquals(3, admins.size(), "both concurrent appends must land: " + admins);
        assertTrue(admins.containsAll(List.of(100L, 201L, 202L)), admins.toString());
    }

    @Test
    @DisabledOnOs(value = OS.WINDOWS, disabledReason = "symlinks need elevated privileges on Windows")
    void editingThroughASymlinkedDirectoryLocksTheSameFileAsEditingThroughTheRealPath() throws Exception {
        // The leaf file itself is never a symlink here (moving a replacement onto a symlinked leaf unlinks it, a
        // separate filesystem concern); only an ancestor directory is, as e.g. /tmp is on macOS. Both spellings
        // name the very same real file, so only the lock coordination is in question.
        Path realDir = Files.createDirectory(dir.resolve("real"));
        Path real = realDir.resolve("dispatch.yaml");
        Files.writeString(real, YAML.replace("STATE", quoted(dir.resolve("state"))).replace("ALM", quoted(dir.resolve("alm"))));
        Path linkedDir = Files.createSymbolicLink(dir.resolve("link"), realDir);
        Path viaLinkedDir = linkedDir.resolve("dispatch.yaml");

        CyclicBarrier barrier = new CyclicBarrier(2);
        CompletableFuture<Void> viaReal = CompletableFuture.runAsync(() -> {
            await(barrier);
            ConfigFile.edit(real, ENV, text -> ConfigEdit.append(text, ConfigEdit.At.of("telegram", "admins"), "201"));
        });
        CompletableFuture<Void> viaLink = CompletableFuture.runAsync(() -> {
            await(barrier);
            ConfigFile.edit(viaLinkedDir, ENV, text -> ConfigEdit.append(text, ConfigEdit.At.of("telegram", "admins"), "202"));
        });

        CompletableFuture.allOf(viaReal, viaLink).get(10, TimeUnit.SECONDS);

        List<Long> admins = ConfigLoader.load(real, ENV).telegram().admins();
        assertEquals(3, admins.size(), "both edits, one made through the symlinked directory, must land: " + admins);
        assertTrue(admins.containsAll(List.of(100L, 201L, 202L)), admins.toString());
    }

    private static void await(CyclicBarrier barrier) {
        try {
            barrier.await(5, TimeUnit.SECONDS);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private static String quoted(Path path) {
        return "'" + path.toString().replace("'", "''") + "'";
    }
}
