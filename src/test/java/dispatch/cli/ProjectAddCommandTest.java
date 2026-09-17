package dispatch.cli;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dispatch.config.Config;
import dispatch.config.ConfigLoader;
import dispatch.testing.GitFixture;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
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

    private int add(Cli.ProjectAdd options) {
        return new ProjectAddCommand(terminal).run(options, ENV);
    }

    private static String quoted(Path path) {
        return path.toString().replace("'", "''");
    }
}
