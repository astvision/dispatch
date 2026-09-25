package dispatch.config;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ConfigLoaderTest {

    /** "/" on Linux and macOS, "C:\\" on Windows: paths below it are absolute on every OS. */
    private static final Path ROOT = FileSystems.getDefault().getRootDirectories().iterator().next();
    private static final String STATE = ROOT.resolve("var/lib/dispatch/backend").toString();
    private static final Map<String, String> ENV = Map.of(
            "TELEGRAM_BOT_TOKEN", "123:abc",
            "GH_TOKEN", "github_pat_x",
            "STATE_DIRECTORY", STATE);

    /** dispatch init's own personal.yaml (ADR 0014), with its placeholders filled with absolute, non-existent paths. */
    private static final String PERSONAL_BASE = personalYaml()
            .replace("STATE_DIR", ROOT.resolve("var/lib/dispatch/bold").toString())
            .replace("CLONE", ROOT.resolve("home/bold/work/alm").toString());
    /** The same personal config, but its one group now also links a chat for announcements (this task). */
    private static final String PERSONAL_WITH_CHAT =
            PERSONAL_BASE.replace("    - name: bold\n", "    - name: bold\n      chatId: -4883391545\n");
    /** PERSONAL_WITH_CHAT plus an admin, which makes it a team whose group chat now needs a workers block. */
    private static final String TEAM_WITH_CHAT_NO_WORKERS =
            PERSONAL_WITH_CHAT.replace("telegram:\n", "telegram:\n  admins: [123456789]\n");

    private static String personalYaml() {
        try (InputStream in = ConfigLoaderTest.class.getResourceAsStream("/personal.yaml")) {
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    @TempDir
    Path dir;

    @Test
    void loadsValidConfigResolvingStateDirSecretsAndProjectLimits() throws IOException {
        Config config = ConfigLoader.load(write(VALID), ENV);

        assertEquals("backend", config.team());
        assertEquals(Path.of(STATE), config.stateDir());
        Config.Group backend = config.telegram().groups().getFirst();
        assertEquals("backend", backend.name());
        assertEquals(-1001234567890L, backend.chatId());
        assertEquals(List.of(new Config.Member(123456789L, "Bold"), new Config.Member(222333444L, "Ali")), backend.members());
        assertEquals(List.of("autoland-management", "crm"), backend.projects());
        assertEquals(2, config.scheduler().maxConcurrentRuns());
        assertEquals("/usr/local/bin/claude", config.agents().get("claude-code").command());
        assertEquals(new Config.Delivery("Dispatch (backend)", "dispatch-backend@users.noreply.github.com", "gh"), config.delivery());
        assertEquals(new Config.Secrets("123:abc", "github_pat_x"), config.secrets());

        Config.Project alm = config.projects().getFirst();
        assertEquals("alm", alm.alias());
        assertEquals(List.of(".env"), alm.copyFiles());
        assertEquals(new Config.RunLimits(Duration.ofMinutes(20), new BigDecimal("2")), config.planLimits(alm));
        assertEquals(new Config.RunLimits(Duration.ofMinutes(60), new BigDecimal("15")), config.executeLimits(alm));

        Config.Project crm = config.projects().get(1);
        assertNull(crm.alias());
        assertEquals(List.of(), crm.copyFiles());
        assertEquals(new Config.RunLimits(Duration.ofMinutes(15), new BigDecimal("2")), config.planLimits(crm));
        assertEquals(new Config.RunLimits(Duration.ofMinutes(60), new BigDecimal("10")), config.executeLimits(crm));
    }

    @Test
    void personMayBeAMemberOfSeveralGroups() throws IOException {
        Config config = ConfigLoader.load(write(VALID.replace(TWO_PROJECTS_IN_BACKEND, BACKEND_AND_MOBILE)), ENV);

        assertEquals(2, config.telegram().groups().size());
        Config.Group mobile = config.telegram().groups().get(1);
        assertEquals(List.of("crm"), mobile.projects());
        assertEquals(123456789L, mobile.members().getFirst().id());
    }

    @Test
    void everyProjectBelongsToExactlyOneGroupOfKnownProjects() throws IOException {
        String broken = VALID.replace(TWO_PROJECTS_IN_BACKEND, """
                      projects:
                        - autoland-management
                        - billing
                    - name: mobile
                      chatId: -1001234567890
                      members:
                        - id: 555
                          name: Sara
                      projects:
                        - autoland-management
                """);

        ConfigException error = assertThrows(ConfigException.class, () -> ConfigLoader.load(write(broken), ENV));

        String message = error.getMessage();
        assertTrue(message.contains("telegram.groups[0].projects[1]: 'billing' is not a configured project"), message);
        assertFalse(message.contains("autoland-management"), "a project may be in several groups (ADR 0025): " + message);
        assertTrue(message.contains("projects[1]: 'crm' is not listed in any group"), message);
        assertTrue(message.contains("telegram.groups[1].chatId: -1001234567890 is used by more than one group"), message);
    }

    @Test
    void singleGroupKeysFromBeforeGroupsPointToTheNewShape() throws IOException {
        String old = """
                team: backend
                telegram:
                  groupChatId: -1001234567890
                """;

        ConfigException error = assertThrows(ConfigException.class, () -> ConfigLoader.load(write(old), ENV));

        assertTrue(error.getMessage().contains("groupChatId") && error.getMessage().contains("telegram.groups"), error.getMessage());
    }

    @Test
    void shippedExampleConfigLoads() {
        Config config = ConfigLoader.load(Path.of("deploy/example.yaml"), ENV);

        assertEquals("backend", config.team());
        assertEquals("alm", config.projects().getFirst().alias());
        assertEquals("gh", config.delivery().ghCommand());
    }

    @Test
    void aliasMayRepeatItsOwnProjectName() throws IOException {
        Config config = ConfigLoader.load(write(VALID.replace("alias: alm", "alias: Autoland-Management")), ENV);

        assertEquals("Autoland-Management", config.projects().getFirst().alias());
    }

    @Test
    void ghCommandCanBeConfigured() throws IOException {
        Config config = ConfigLoader.load(write(VALID.replace("  authorEmail: dispatch-backend@users.noreply.github.com",
                "  authorEmail: dispatch-backend@users.noreply.github.com\n  ghCommand: /opt/gh/bin/gh")), ENV);

        assertEquals("/opt/gh/bin/gh", config.delivery().ghCommand());
    }

    @Test
    void executionLimitsAndDeliveryIdentityAreRequired() throws IOException {
        String withoutThem = VALID
                .replace("""
                          execute:
                            timeout: 60m
                            budgetUsd: 10
                        """, "")
                .replace("""
                        delivery:
                          authorName: Dispatch (backend)
                          authorEmail: dispatch-backend@users.noreply.github.com
                        """, "");

        ConfigException error = assertThrows(ConfigException.class, () -> ConfigLoader.load(write(withoutThem), ENV));

        assertTrue(error.getMessage().contains("limits.execute: timeout and budgetUsd are required"), error.getMessage());
        assertTrue(error.getMessage().contains("delivery.authorName"), error.getMessage());
        assertTrue(error.getMessage().contains("delivery.authorEmail"), error.getMessage());
    }

    @Test
    void stateDirInFileWinsOverEnvironment() throws IOException {
        String inFile = ROOT.resolve("srv/dispatch").toString();
        Config config = ConfigLoader.load(write("stateDir: '" + inFile + "'\n" + VALID), ENV);

        assertEquals(Path.of(inFile), config.stateDir());
    }

    @Test
    void unknownKeyIsReportedWithItsPath() throws IOException {
        ConfigException error = assertThrows(ConfigException.class,
                () -> ConfigLoader.load(write(VALID.replace("baseBranch: main", "basebranch: main")), ENV));

        assertTrue(error.getMessage().contains("basebranch"), error.getMessage());
        assertTrue(error.getMessage().contains("projects[0]"), error.getMessage());
    }

    @Test
    void worktreesStayForAWeekUnlessConfiguredOtherwise() throws IOException {
        assertEquals(7, ConfigLoader.load(write(VALID), ENV).worktrees().idleDays());
        String custom = VALID.replace("scheduler:\n", "worktrees:\n  idleDays: 3\nscheduler:\n");
        assertEquals(3, ConfigLoader.load(write(custom), ENV).worktrees().idleDays());

        ConfigException error = assertThrows(ConfigException.class,
                () -> ConfigLoader.load(write(VALID.replace("scheduler:\n", "worktrees:\n  idleDays: 0\nscheduler:\n")), ENV));

        assertTrue(error.getMessage().contains("worktrees.idleDays"), error.getMessage());
    }

    @Test
    void invalidDurationIsReported() throws IOException {
        ConfigException error = assertThrows(ConfigException.class,
                () -> ConfigLoader.load(write(VALID.replace("timeout: 15m", "timeout: 15x")), ENV));

        assertTrue(error.getMessage().contains("15x"), error.getMessage());
    }

    @Test
    void everyValidationProblemIsReportedAtOnce() throws IOException {
        String broken = VALID
                .replace("alias: alm", "alias: crm")
                .replace("id: 222333444", "id: -5")
                .replace("- \".env\"", "- \"../secrets.env\"")
                .replace("agent: claude-code\n    model", "agent: codex\n    model");

        ConfigException error = assertThrows(ConfigException.class,
                () -> ConfigLoader.load(write(broken), Map.of("STATE_DIRECTORY", STATE)));

        String message = error.getMessage();
        assertTrue(message.contains("TELEGRAM_BOT_TOKEN"), message);
        assertTrue(message.contains("telegram.groups[0].members[1].id"), message);
        assertTrue(message.contains("'crm' is used by more than one project"), message);
        assertTrue(message.contains("projects[0].copyFiles[0]"), message);
        assertTrue(message.contains("projects[0].agent: 'codex' is not configured under agents"), message);
    }

    @Test
    void repoUrlWithCredentialsIsRejectedWithoutEchoingThem() throws IOException {
        String withToken = VALID.replace("https://github.com/acme/autoland-management.git",
                "https://x-access-token:TOKENVALUE123456@github.com/acme/autoland-management.git");
        String withUserOnly = VALID.replace("https://github.com/acme/crm.git", "https://TOKENVALUE654321@github.com/acme/crm.git");

        ConfigException error = assertThrows(ConfigException.class, () -> ConfigLoader.load(write(withToken), ENV));
        ConfigException userOnly = assertThrows(ConfigException.class, () -> ConfigLoader.load(write(withUserOnly), ENV));

        assertTrue(error.getMessage().contains("projects[0].repo") && error.getMessage().contains("GH_TOKEN"), error.getMessage());
        assertTrue(!error.getMessage().contains("TOKENVALUE123456"), error.getMessage());
        assertTrue(userOnly.getMessage().contains("projects[1].repo") && !userOnly.getMessage().contains("TOKENVALUE654321"),
                userOnly.getMessage());
    }

    @Test
    void sshRepoUrlsAreAccepted() throws IOException {
        String ssh = VALID.replace("https://github.com/acme/autoland-management.git", "git@github.com:acme/autoland-management.git")
                .replace("https://github.com/acme/crm.git", "ssh://git@github.com/acme/crm.git");

        Config config = ConfigLoader.load(write(ssh), ENV);

        assertEquals("git@github.com:acme/autoland-management.git", config.projects().getFirst().repo());
    }

    @Test
    void secretLookingKeyPointsToTheEnvironmentFile() throws IOException {
        String withSecret = VALID.replace("      chatId: -1001234567890", "      chatId: -1001234567890\n      botToken: 123:abc");

        ConfigException error = assertThrows(ConfigException.class, () -> ConfigLoader.load(write(withSecret), ENV));

        assertTrue(error.getMessage().contains("botToken") && error.getMessage().contains("environment file"), error.getMessage());
    }

    @Test
    void projectMayPointAtAnExistingCloneAnywhereInsteadOfARepoUrl() throws IOException {
        String crmClone = ROOT.resolve("home/bold/work/crm").toString();
        Config config = ConfigLoader.load(write(VALID.replace(CRM_REPO, "    path: '" + crmClone + "'\n")), ENV);

        Config.Project crm = config.projects().get(1);
        assertEquals(crmClone, crm.path());
        assertNull(crm.repo());
        assertNull(config.projects().getFirst().path(), "without a path the clone stays under the state directory");
    }

    @Test
    void projectNeedsARepoUrlOrAnAbsolutePath() throws IOException {
        String yaml = VALID.replace(CRM_REPO, "    path: work/crm\n")
                .replace("    repo: https://github.com/acme/autoland-management.git\n", "");

        ConfigException error = assertThrows(ConfigException.class, () -> ConfigLoader.load(write(yaml), ENV));

        assertTrue(error.getMessage().contains("projects[0].repo: required unless path is set"), error.getMessage());
        assertTrue(error.getMessage().contains("projects[1].path: must be an absolute path to a git clone, got 'work/crm'"),
                error.getMessage());
    }

    @Test
    void projectNameCannotBeDotOrDotDot() throws IOException {
        // dispatch worker init resolves a project name straight into stateDir/repos/<name>; either name would
        // resolve outside that folder.
        ConfigException dot = assertThrows(ConfigException.class,
                () -> ConfigLoader.load(write(VALID.replace("- name: autoland-management\n", "- name: .\n")), ENV));
        ConfigException dotDot = assertThrows(ConfigException.class,
                () -> ConfigLoader.load(write(VALID.replace("- name: autoland-management\n", "- name: ..\n")), ENV));

        assertTrue(dot.getMessage().contains("projects[0].name: letters, digits, '.', '_' and '-' only, got '.'"), dot.getMessage());
        assertTrue(dotDot.getMessage().contains("projects[0].name: letters, digits, '.', '_' and '-' only, got '..'"), dotDot.getMessage());
    }

    @Test
    void adminsWhoApproveNewMembersAreTelegramUsers() throws IOException {
        String withAdmins = VALID.replace("telegram:\n  groups:\n", "telegram:\n  admins: [123456789, 555]\n  groups:\n");
        String invalid = VALID.replace("telegram:\n  groups:\n", "telegram:\n  admins: [0, 555, 555]\n  groups:\n");

        assertEquals(List.of(123456789L, 555L), ConfigLoader.load(write(withAdmins), ENV).telegram().admins());
        assertEquals(List.of(), ConfigLoader.load(write(VALID), ENV).telegram().admins(), "nobody unless configured");
        ConfigException error = assertThrows(ConfigException.class, () -> ConfigLoader.load(write(invalid), ENV));
        assertTrue(error.getMessage().contains("telegram.admins[0]: must be a positive Telegram user id, got 0"), error.getMessage());
        assertTrue(error.getMessage().contains("telegram.admins[2]: 555 is listed more than once"), error.getMessage());
    }

    @Test
    void groupNameFitsInAButtonsData() throws IOException {
        String longName = "a".repeat(41);

        ConfigException error = assertThrows(ConfigException.class,
                () -> ConfigLoader.load(write(VALID.replace("  - name: backend\n", "  - name: " + longName + "\n")), ENV));

        assertTrue(error.getMessage().contains("telegram.groups[0].name: at most 40 characters"), error.getMessage());
    }

    @Test
    void groupNeedsNoChatForAPersonalBot() throws IOException {
        String personal = VALID.replace("      chatId: -1001234567890\n", "")
                .replace(TWO_PROJECTS_IN_BACKEND, BACKEND_AND_MOBILE.replace("      chatId: -1009876543210\n", ""));
        assertTrue(!personal.contains("chatId"), personal);

        Config config = ConfigLoader.load(write(personal), ENV);

        assertNull(config.telegram().groups().getFirst().chatId());
        assertNull(config.telegram().groups().get(1).chatId(), "several groups may go without a chat");
    }

    @Test
    void effortIsOneOfClaudeCodesLevels() throws IOException {
        Config config = ConfigLoader.load(write(VALID.replace("    model: opus\n", "    model: opus\n    effort: xhigh\n")), ENV);
        ConfigException error = assertThrows(ConfigException.class,
                () -> ConfigLoader.load(write(VALID.replace("    model: opus\n", "    model: opus\n    effort: extreme\n")), ENV));

        assertEquals("xhigh", config.projects().getFirst().effort());
        assertNull(config.projects().get(1).effort());
        assertTrue(error.getMessage().contains("projects[0].effort: must be one of low, medium, high, xhigh, max, got 'extreme'"),
                error.getMessage());
    }

    @Test
    void modelAndEffortCanDifferPerPhase() throws IOException {
        Config config = ConfigLoader.load(write(VALID.replace("    model: opus\n",
                "    model: opus\n    effort: medium\n    plan: { effort: max }\n    execute: { model: sonnet }\n")), ENV);
        ConfigException wrongEffort = assertThrows(ConfigException.class,
                () -> ConfigLoader.load(write(VALID.replace("    model: opus\n", "    model: opus\n    execute: { effort: extreme }\n")), ENV));
        ConfigException limitInPhase = assertThrows(ConfigException.class,
                () -> ConfigLoader.load(write(VALID.replace("    model: opus\n", "    model: opus\n    plan: { timeout: 10m }\n")), ENV));

        Config.Project project = config.projects().getFirst();
        assertEquals("opus", project.planModel(), "the project's model where the phase sets none");
        assertEquals("max", project.planEffort());
        assertEquals("sonnet", project.executeModel());
        assertEquals("medium", project.executeEffort());
        assertTrue(wrongEffort.getMessage().contains("projects[0].execute.effort: must be one of low, medium, high, xhigh, max, got 'extreme'"),
                wrongEffort.getMessage());
        assertTrue(limitInPhase.getMessage().contains("timeout"), "limits stay under limits: " + limitInPhase.getMessage());
    }

    @Test
    void missingStateDirIsReported() throws IOException {
        ConfigException error = assertThrows(ConfigException.class,
                () -> ConfigLoader.load(write(VALID), Map.of("TELEGRAM_BOT_TOKEN", "123:abc")));

        assertTrue(error.getMessage().contains("stateDir"), error.getMessage());
    }

    @Test
    void aTeamConfigNeedsAWorkersBlock() throws IOException {
        ConfigException error = assertThrows(ConfigException.class,
                () -> ConfigLoader.load(write(VALID.replace(WORKERS_BLOCK, "")), ENV));

        assertTrue(error.getMessage().contains("workers: required once a team's group has a chat"), error.getMessage());
    }

    @Test
    void aTeamConfigWithWorkersIsLoaded() throws IOException {
        // VALID's group has a chatId and two members, which makes it a team; it already carries a workers block.
        Config config = ConfigLoader.load(write(VALID), ENV);

        assertTrue(config.isTeam());
        assertEquals("https://team.example.com", config.workers().publicUrl());
        assertEquals(7880, config.workers().port());
    }

    @Test
    void aPersonalConfigNeedsNoWorkersAndIsNotATeam() throws IOException {
        String onePersonNoChat = VALID.replace("      chatId: -1001234567890\n", "")
                .replace("        - id: 222333444\n          name: Ali\n", "")
                .replace(WORKERS_BLOCK, "");

        Config config = ConfigLoader.load(write(onePersonNoChat), ENV);

        assertNull(config.workers());
        assertFalse(config.isTeam(), "no group chat, so nothing has to run elsewhere");
    }

    @Test
    void aConfigWithSeveralMembersIsATeamEvenWithoutAGroupChat() throws IOException {
        String noChat = VALID.replace("      chatId: -1001234567890\n", "").replace(WORKERS_BLOCK, "");

        Config config = ConfigLoader.load(write(noChat), ENV);

        assertNull(config.workers(), "no group has a chat, so no workers are needed yet");
        assertTrue(config.isTeam(), "several members share it, whether or not a group has a chat");
    }

    @Test
    void personalMeansExactlyOneMemberAndNoAdminsLikeGroupsSays() {
        Config.Telegram nobody = new Config.Telegram(List.of(), List.of(new Config.Group("g", null, List.of(), List.of("p"))));
        Config.Telegram one = new Config.Telegram(List.of(), List.of(new Config.Group("g", null,
                List.of(new Config.Member(1, "Bold")), List.of("p")), new Config.Group("h", null, List.of(new Config.Member(1, "Bold")),
                List.of("q"))));

        assertFalse(Config.isPersonal(nobody), "no member at all is not a personal bot");
        assertFalse(Config.isTeam(nobody));
        assertEquals(new dispatch.core.Groups(nobody).isPersonal(), Config.isPersonal(nobody));
        assertTrue(Config.isPersonal(one), "one distinct member across groups");
        assertFalse(Config.isTeam(one));
        assertEquals(new dispatch.core.Groups(one).isPersonal(), Config.isPersonal(one));
    }

    @Test
    void aPersonalBotMayHaveGroupChatsWithoutWorkers() throws IOException {
        Config config = ConfigLoader.load(write(PERSONAL_WITH_CHAT), ENV);

        assertFalse(config.isTeam());
        assertEquals(-4883391545L, config.telegram().groups().getFirst().chatId());
        assertNull(config.workers());
    }

    @Test
    void aTeamWithAGroupChatStillNeedsWorkers() throws IOException {
        ConfigException e = assertThrows(ConfigException.class, () -> ConfigLoader.load(write(TEAM_WITH_CHAT_NO_WORKERS), ENV));

        assertTrue(e.getMessage().contains("workers: required"), e.getMessage());
    }

    @Test
    void everyBadWorkersValueIsReported() throws IOException {
        Path file = write(VALID.replace(WORKERS_BLOCK, """
                workers:
                  publicUrl: http://team.example.com
                  port: 70000
                """));

        ConfigException error = assertThrows(ConfigException.class, () -> ConfigLoader.load(file, ENV));

        assertTrue(error.getMessage().contains("workers.publicUrl: must start with https://"), error.getMessage());
        assertTrue(error.getMessage().contains("workers.port: must be from 1 to 65535, got 70000"), error.getMessage());
    }

    @Test
    void plainHttpIsAcceptedOnlyOnTheLoopbackAddress() throws IOException {
        Config config = ConfigLoader.load(write(VALID.replace(WORKERS_BLOCK, """
                workers:
                  publicUrl: http://127.0.0.1:7880
                  port: 7880
                """)), ENV);

        assertEquals("http://127.0.0.1:7880", config.workers().publicUrl(), "tests pair over loopback without TLS");
    }

    @Test
    void workersWithoutAPortIsReportedAsAPortProblem() throws IOException {
        Path file = write(VALID.replace(WORKERS_BLOCK, """
                workers:
                  publicUrl: https://team.example.com
                """));

        ConfigException error = assertThrows(ConfigException.class, () -> ConfigLoader.load(file, ENV));

        assertTrue(error.getMessage().contains("workers.port: must be from 1 to 65535, got 0"), error.getMessage());
    }

    @Test
    void aConfigWithAMiniAppBlockIsLoaded() throws IOException {
        Config config = ConfigLoader.load(write(VALID + MINI_APP_BLOCK), ENV);

        assertEquals("https://dispatch.example.com", config.miniApp().publicUrl());
        assertEquals(7879, config.miniApp().port());
    }

    @Test
    void theMiniAppIsOffWithoutItsBlock() throws IOException {
        Config team = ConfigLoader.load(write(VALID), ENV);
        Config personal = ConfigLoader.load(write(VALID.replace("      chatId: -1001234567890\n", "").replace(WORKERS_BLOCK, "")), ENV);

        assertNull(team.miniApp(), "off by default in a team");
        assertNull(personal.miniApp(), "off by default in personal mode, where it is not required either");
    }

    @Test
    void everyBadMiniAppValueIsReported() throws IOException {
        Path file = write(VALID + """
                miniApp:
                  publicUrl: http://dispatch.example.com
                  port: 70000
                """);

        ConfigException error = assertThrows(ConfigException.class, () -> ConfigLoader.load(file, ENV));

        assertTrue(error.getMessage().contains("miniApp.publicUrl: must start with https://"), error.getMessage());
        assertTrue(error.getMessage().contains("miniApp.port: must be from 1 to 65535, got 70000"), error.getMessage());
    }

    @Test
    void aMiniAppWithoutAPortIsReportedAsAPortProblem() throws IOException {
        Path file = write(VALID + """
                miniApp:
                  publicUrl: https://dispatch.example.com
                """);

        ConfigException error = assertThrows(ConfigException.class, () -> ConfigLoader.load(file, ENV));

        assertTrue(error.getMessage().contains("miniApp.port: must be from 1 to 65535, got 0"), error.getMessage());
    }

    @Test
    void aMiniAppWithoutAPublicUrlIsRefused() throws IOException {
        Path file = write(VALID + """
                miniApp:
                  port: 7879
                """);

        ConfigException error = assertThrows(ConfigException.class, () -> ConfigLoader.load(file, ENV));

        assertTrue(error.getMessage().contains("miniApp.publicUrl: required"), error.getMessage());
    }

    private static final String MINI_APP_BLOCK = """
            miniApp:
              publicUrl: https://dispatch.example.com
              port: 7879
            """;

    private static final String CRM_REPO = "    repo: https://github.com/acme/crm.git\n";

    /** VALID's group has a chat, which makes it a team (ADR 0021); its workers block, ready to strip or replace. */
    private static final String WORKERS_BLOCK = """
            workers:
              publicUrl: https://team.example.com
              port: 7880
            """;

    private static final String TWO_PROJECTS_IN_BACKEND = """
                  projects:
                    - autoland-management
                    - crm
            """;

    private static final String BACKEND_AND_MOBILE = """
                  projects:
                    - autoland-management
                - name: mobile
                  chatId: -1009876543210
                  members:
                    - id: 123456789
                      name: Bold
                  projects:
                    - crm
            """;

    private Path write(String yaml) throws IOException {
        Path file = dir.resolve("backend.yaml");
        Files.writeString(file, yaml);
        return file;
    }

    private static final String VALID = """
            team: backend
            telegram:
              groups:
                - name: backend
                  chatId: -1001234567890
                  members:
                    - id: 123456789
                      name: Bold
                    - id: 222333444
                      name: Ali
                  projects:
                    - autoland-management
                    - crm
            delivery:
              authorName: Dispatch (backend)
              authorEmail: dispatch-backend@users.noreply.github.com
            workers:
              publicUrl: https://team.example.com
              port: 7880
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
                command: /usr/local/bin/claude
            projects:
              - name: autoland-management
                alias: alm
                repo: https://github.com/acme/autoland-management.git
                baseBranch: main
                agent: claude-code
                model: opus
                copyFiles:
                  - ".env"
                limits:
                  plan:
                    timeout: 20m
                  execute:
                    budgetUsd: 15
              - name: crm
                repo: https://github.com/acme/crm.git
                baseBranch: develop
                agent: claude-code
            """;
}
