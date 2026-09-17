package dispatch.cli;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;

class ConfigTextTest {

    private static final List<String> LIFE = List.of("name: life", "path: '/home/bold/work/life'", "baseBranch: master",
            "agent: claude-code");

    @Test
    void projectIsAddedToItsGroupAndTheProjectListWithoutReformattingAnything() {
        String before = """
                team: bold
                telegram:
                  groups:
                    - name: bold
                      members:
                        - id: 1
                          name: 'Bold'   # me
                      projects:
                        - alm

                projects:
                  - name: alm
                    path: '/home/bold/work/alm'
                    # model: opus      # maybe later
                """;

        String after = ConfigText.addProject(before, "bold", "life", LIFE);

        assertEquals("""
                team: bold
                telegram:
                  groups:
                    - name: bold
                      members:
                        - id: 1
                          name: 'Bold'   # me
                      projects:
                        - alm
                        - life

                projects:
                  - name: alm
                    path: '/home/bold/work/alm'
                    # model: opus      # maybe later
                  - name: life
                    path: '/home/bold/work/life'
                    baseBranch: master
                    agent: claude-code
                """, after);
    }

    @Test
    void shippedExampleGetsTwoInsertionsAndNothingElseChanges() throws IOException {
        String example = Files.readString(Path.of("deploy/example.yaml"));

        String after = ConfigText.addProject(example, "backend", "life", LIFE);

        String expected = example.replace("        - autoland-management\n", "        - autoland-management\n        - life\n")
                + "  - name: life\n    path: '/home/bold/work/life'\n    baseBranch: master\n    agent: claude-code\n";
        assertEquals(expected, after, "the new project goes after the commented options of the last one");
    }

    @Test
    void flowStyleListsAndProjectsBeforeOtherKeysAreHandled() {
        String before = """
                telegram:
                  groups:
                    - { name: bold, members: [{ id: 1, name: Bold }], projects: [alm] }
                projects:
                  - name: alm
                    path: '/a'

                # Limits for every project
                limits:
                  plan: { timeout: 15m, budgetUsd: 2 }
                """;

        String after = ConfigText.addProject(before, "bold", "life", LIFE);

        assertTrue(after.contains("projects: [alm, life] }"), after);
        assertTrue(after.contains("""
                    path: '/a'
                  - name: life
                    path: '/home/bold/work/life'
                    baseBranch: master
                    agent: claude-code

                # Limits for every project
                """), after);
    }

    @Test
    void windowsLineEndingsAreKept() {
        String before = "telegram:\r\n  groups:\r\n    - name: bold\r\n      projects:\r\n        - alm\r\nprojects:\r\n  - name: alm\r\n";

        String after = ConfigText.addProject(before, "bold", "life", List.of("name: life"));

        assertEquals("telegram:\r\n  groups:\r\n    - name: bold\r\n      projects:\r\n        - alm\r\n        - life\r\n"
                + "projects:\r\n  - name: alm\r\n  - name: life\r\n", after);
    }

    @Test
    void unknownGroupIsRefused() {
        CliException error = assertThrows(CliException.class,
                () -> ConfigText.addProject("telegram:\n  groups:\n    - name: bold\n      projects: [alm]\nprojects:\n  - name: alm\n",
                        "team", "life", LIFE));

        assertTrue(error.getMessage().contains("no group named 'team'"), error.getMessage());
    }
}
