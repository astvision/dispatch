package dispatch.config;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
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
