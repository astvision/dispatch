package dispatch.cli;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dispatch.config.Config;
import dispatch.config.ConfigLoader;
import dispatch.testing.GitFixture;
import dispatch.testing.ScriptedTerminal;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ProjectAddCommandTest {

    // Split, so secret scanners never see a token-shaped literal in this file.
    private static final Map<String, String> ENV = Map.of("TELEGRAM_BOT_TOKEN", "123456789" + ":AAH-fake-token-for-tests-only-0123456789");

    @TempDir
    Path dir;

    private GitFixture repos;
    private Path config;
    private Path life;
    private final ScriptedTerminal terminal = new ScriptedTerminal();

    @BeforeEach
    void setUp() throws IOException {
        repos = GitFixture.create(dir, "alm");
        life = dir.resolve("work/life");
        GitFixture.sh(dir, "git", "clone", "--quiet", repos.origin.toString(), life.toString());
        config = dir.resolve("dispatch.yaml");
        Files.writeString(config, new String(ProjectAddCommandTest.class.getResourceAsStream("/personal.yaml").readAllBytes())
                .replace("STATE_DIR", quoted(dir.resolve("state")))
                .replace("CLONE", quoted(repos.repo("alm"))));
    }

    @Test
    void cloneIsAddedWithItsOriginAndDefaultBranch() {
        int exit = add(new Cli.ProjectAdd(config, life, null, null, null, "opus", "high", null));

        assertEquals(0, exit, terminal.output());
        Config loaded = ConfigLoader.load(config, ENV);
        Config.Project added = loaded.projects().get(1);
        assertEquals("life", added.name(), "named after its folder");
        assertEquals(life.toString(), added.path());
        assertEquals(repos.origin.toString(), added.repo());
        assertEquals("main", added.baseBranch(), "origin's default branch");
        assertEquals("opus", added.model());
        assertEquals("high", added.effort());
        assertEquals(List.of("alm", "life"), loaded.telegram().groups().getFirst().projects());
        assertTrue(terminal.output().contains("OK   added life: " + life), terminal.output());
    }

    @Test
    void clashingNameOrAFolderThatIsNoCloneIsRefusedWithoutTouchingTheConfig() throws IOException {
        String before = Files.readString(config);

        int clash = add(new Cli.ProjectAdd(config, life, "alm", null, null, null, null, null));
        int notAClone = add(new Cli.ProjectAdd(config, Files.createDirectories(dir.resolve("work/notes")), null, null, null, null, null, null));
        int badEffort = add(new Cli.ProjectAdd(config, life, null, null, null, null, "extreme", null));

        assertEquals(List.of(1, 1, 1), List.of(clash, notAClone, badEffort));
        assertEquals(before, Files.readString(config));
        String output = terminal.output();
        assertTrue(output.contains("FAIL a project named 'alm' already exists; choose another name with --name"), output);
        assertTrue(output.contains("FAIL " + dir.resolve("work/notes") + " is not a git clone"), output);
        assertTrue(output.contains("effort: must be one of low, medium, high, xhigh, max"), output);
    }

    /** ADR 0026: --agent puts the project on Codex or Gemini, and adds that agent's command when the config has none. */
    @Test
    void agentPutsTheProjectOnCodexAndAddsCodexWhenMissing() {
        int exit = add(new Cli.ProjectAdd(config, life, null, null, null, "gpt-5-codex", "xhigh", null, "codex"));

        assertEquals(0, exit, terminal.output());
        Config loaded = ConfigLoader.load(config, ENV);
        assertEquals("codex", loaded.projects().get(1).agent());
        assertEquals("codex", loaded.agents().get("codex").command(), "found on PATH, as claude is by default");
        assertEquals("claude-code", loaded.projects().getFirst().agent(), "the other project keeps its agent");
        assertTrue(terminal.output().contains("OK   added life: " + life), terminal.output());
        assertTrue(terminal.output().contains("agent codex"), terminal.output());
    }

    @Test
    void anUnknownAgentOrAnEffortItLacksIsRefusedWithoutTouchingTheConfig() throws IOException {
        String before = Files.readString(config);

        int unknown = add(new Cli.ProjectAdd(config, life, null, null, null, null, null, null, "aider"));
        int geminiEffort = add(new Cli.ProjectAdd(config, life, null, null, null, null, "high", null, "gemini"));

        assertEquals(List.of(1, 1), List.of(unknown, geminiEffort));
        assertEquals(before, Files.readString(config));
        assertTrue(terminal.output().contains("agents.aider: unsupported agent type (supported: claude-code, codex, gemini)"),
                terminal.output());
        assertTrue(terminal.output().contains("effort: gemini has no effort setting; remove it"), terminal.output());
    }

    @Test
    void anUnwritableConfigDirectoryFailsCleanlyInsteadOfCrashing() throws IOException {
        Assumptions.assumeFalse(System.getProperty("os.name").toLowerCase().contains("win"), "POSIX permissions");
        Files.setPosixFilePermissions(dir, PosixFilePermissions.fromString("r-xr-xr-x"));
        try {
            Path probe = dir.resolve(".write-probe");
            boolean enforced;
            try {
                Files.createFile(probe);
                Files.deleteIfExists(probe);
                enforced = false;
            } catch (IOException e) {
                enforced = true;
            }
            Assumptions.assumeTrue(enforced, "directory permissions are not enforced here (likely running as root)");

            int exit = add(new Cli.ProjectAdd(config, life, null, null, null, null, null, null));

            assertEquals(1, exit, terminal.output());
            assertTrue(terminal.output().contains("FAIL"), terminal.output());
            assertFalse(terminal.output().contains("Exception"), "a clean failure, not a raw stack trace: " + terminal.output());
        } finally {
            Files.setPosixFilePermissions(dir, PosixFilePermissions.fromString("rwxr-xr-x"));
        }
    }

    private int add(Cli.ProjectAdd options) {
        return new ProjectAddCommand(terminal).run(options, ENV);
    }

    private static String quoted(Path path) {
        return path.toString().replace("'", "''");
    }
}
