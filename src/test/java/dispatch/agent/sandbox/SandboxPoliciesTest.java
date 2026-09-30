package dispatch.agent.sandbox;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dispatch.agent.AgentStartException;
import dispatch.agent.RunRequest;
import dispatch.domain.RunKind;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class SandboxPoliciesTest {

    @TempDir
    Path root;
    Path home;
    Path stateDir;
    Path configDir;

    @BeforeEach
    void setUp() throws IOException {
        home = Files.createDirectories(root.resolve("home"));
        stateDir = Files.createDirectories(root.resolve("state"));
        configDir = Files.createDirectories(home.resolve(".config/dispatch"));
        Files.createDirectories(home.resolve(".ssh"));
        Files.createDirectories(home.resolve(".config/gh"));
    }

    @Test
    void executeRunWritesItsWorktreeGitDirAgentStateAndCachesAndSeesNoSecrets() throws IOException {
        Path gitDir = Files.createDirectories(stateDir.resolve("repos/alm/.git/worktrees/7"));
        Path worktree = Files.createDirectories(stateDir.resolve("worktrees/7"));
        Files.writeString(worktree.resolve(".git"), "gitdir: " + gitDir + "\n");
        Path attachments = Files.createDirectories(stateDir.resolve("attachments/7"));
        SandboxPolicies policies = new SandboxPolicies(home, stateDir, List.of(configDir, stateDir));

        SandboxPolicy policy = policies.forRun(request(RunKind.EXECUTE, worktree, List.of(attachments)),
                List.of(".claude", ".claude.json"), Map.of("XDG_RUNTIME_DIR", root.resolve("no-runtime-dir").toString()));

        assertEquals(worktree, policy.workdir());
        assertEquals(stateDir.resolve("repos/alm/.git"), policy.gitCommonDir());
        Path common = stateDir.resolve("repos/alm/.git");
        assertEquals(List.of(attachments, worktree.resolve(".git"), common.resolve("config"), common.resolve("hooks"),
                common.resolve("info"), gitDir.resolve("config.worktree"), gitDir.resolve("commondir")), policy.readOnly());
        assertEquals(List.of(home.resolve(".claude"), home.resolve(".claude.json"), home.resolve(".m2"),
                home.resolve(".gradle"), home.resolve(".npm"), home.resolve(".cache")), policy.writable());
        assertEquals(List.of(home.resolve(".ssh"), home.resolve(".config/gh"), configDir, stateDir), policy.hidden());
    }

    @Test
    void missingSecretDirsAreNotHidden() {
        // bwrap cannot create a mount point on the read-only root, so hiding ~/.gnupg that does not exist would fail the run.
        SandboxPolicies policies = new SandboxPolicies(home, stateDir, List.of(root.resolve("no-such-clone")));

        SandboxPolicy policy = policies.forRun(request(RunKind.PLAN, stateDir, List.of()), List.of(".codex"));

        assertFalse(policy.hidden().contains(home.resolve(".gnupg")));
        assertFalse(policy.hidden().contains(root.resolve("no-such-clone")));
        assertTrue(policy.hidden().contains(home.resolve(".ssh")));
    }

    @Test
    void gitCommonDirOfAMembersOwnCloneIsMountedBackThoughTheCloneIsHidden() throws IOException {
        Path clone = Files.createDirectories(home.resolve("code/alm"));
        Path gitDir = Files.createDirectories(clone.resolve(".git/worktrees/9"));
        Path worktree = Files.createDirectories(stateDir.resolve("worktrees/9"));
        Files.writeString(worktree.resolve(".git"), "gitdir: " + gitDir);
        SandboxPolicies policies = new SandboxPolicies(home, stateDir, List.of(configDir, stateDir, clone), List.of(clone));

        SandboxPolicy policy = policies.forRun(request(RunKind.EXECUTE, worktree, List.of()), List.of(".claude"));

        assertTrue(policy.hidden().contains(clone));
        assertEquals(clone.resolve(".git"), policy.gitCommonDir());
    }

    @Test
    void aRelativeGitdirResolvesAgainstTheWorktree() throws IOException {
        Path worktree = Files.createDirectories(stateDir.resolve("worktrees/4"));
        Files.createDirectories(stateDir.resolve("repos/alm/.git/worktrees/4"));
        Files.writeString(worktree.resolve(".git"), "gitdir: ../../repos/alm/.git/worktrees/4");

        assertEquals(stateDir.resolve("repos/alm/.git"), new SandboxPolicies(home, stateDir, List.of()).gitCommonDir(worktree));
    }

    @Test
    void aSplitHasNoGitDir() {
        assertNull(new SandboxPolicies(home, stateDir, List.of()).gitCommonDir(stateDir));
    }

    @Test
    void aWorktreeGitFilePointingAtHomeIsRefused() throws IOException {
        // The agent can rewrite its worktree's .git between runs; home must never come back read-write.
        Path worktree = Files.createDirectories(stateDir.resolve("worktrees/7"));
        Files.writeString(worktree.resolve(".git"), "gitdir: " + home);

        AgentStartException refused = assertThrows(AgentStartException.class, () ->
                new SandboxPolicies(home, stateDir, List.of(stateDir)).forRun(request(RunKind.EXECUTE, worktree, List.of()), List.of()));

        assertTrue(refused.getMessage().contains(worktree.resolve(".git").toString()), refused.getMessage());
        assertTrue(refused.getMessage().contains(home.toString()), refused.getMessage());
    }

    @Test
    void aWorktreeGitFilePointingAtAnUnconfiguredRepositoryIsRefused() throws IOException {
        Path other = Files.createDirectories(home.resolve("code/other/.git/worktrees/7"));
        Path worktree = Files.createDirectories(stateDir.resolve("worktrees/7"));
        Files.writeString(worktree.resolve(".git"), "gitdir: " + other);
        SandboxPolicies policies = new SandboxPolicies(home, stateDir, List.of(stateDir), List.of(home.resolve("code/alm")));

        assertThrows(AgentStartException.class, () -> policies.forRun(request(RunKind.EXECUTE, worktree, List.of()), List.of()));
    }

    @Test
    void aGitdirAtTheRootIsRefusedNotAnError() throws IOException {
        Path worktree = Files.createDirectories(stateDir.resolve("worktrees/7"));
        Files.writeString(worktree.resolve(".git"), "gitdir: /x");

        assertThrows(AgentStartException.class, () ->
                new SandboxPolicies(home, stateDir, List.of()).forRun(request(RunKind.EXECUTE, worktree, List.of()), List.of()));
    }

    @Test
    void theWorktreesGitFileIsMountedBackReadOnly() throws IOException {
        Path gitDir = Files.createDirectories(stateDir.resolve("repos/alm/.git/worktrees/7"));
        Path worktree = Files.createDirectories(stateDir.resolve("worktrees/7"));
        Files.writeString(worktree.resolve(".git"), "gitdir: " + gitDir + "\n");

        SandboxPolicy policy = new SandboxPolicies(home, stateDir, List.of(stateDir))
                .forRun(request(RunKind.EXECUTE, worktree, List.of()), List.of());

        assertTrue(policy.readOnly().contains(worktree.resolve(".git")), policy.readOnly().toString());
    }

    @Test
    void assistantRunGetsTheStateFileAndItsCommandBack() throws IOException {
        Path assistantHome = Files.createDirectories(stateDir.resolve("assistant"));
        SandboxPolicies policies = new SandboxPolicies(home, stateDir, List.of(configDir, stateDir));

        SandboxPolicy policy = policies.forRun(request(RunKind.ASSISTANT, assistantHome, List.of()), List.of(".claude"));

        // dispatch ask reads the state file; SQLite needs -wal and -shm writable even to read.
        assertTrue(policy.writable().containsAll(List.of(stateDir.resolve("dispatch.db"),
                stateDir.resolve("dispatch.db-wal"), stateDir.resolve("dispatch.db-shm"))));
        assertTrue(policy.readOnly().contains(stateDir.resolve("assistant-bin")));
        assertTrue(policy.hidden().contains(stateDir));
    }

    @Test
    void onlyTheAssistantGetsTheStateFile() throws IOException {
        Path worktree = Files.createDirectories(stateDir.resolve("worktrees/7"));
        SandboxPolicies policies = new SandboxPolicies(home, stateDir, List.of(stateDir));

        SandboxPolicy policy = policies.forRun(request(RunKind.PLAN, worktree, List.of()), List.of(".claude"));

        assertFalse(policy.writable().contains(stateDir.resolve("dispatch.db")));
        assertFalse(policy.readOnly().contains(stateDir.resolve("assistant-bin")));
    }

    @Test
    void theAgentsRuntimeDirIsHidden() throws IOException {
        // Its sockets (systemd --user, ssh-agent, gpg-agent, the Secret Service) would run things outside the sandbox.
        Path runtime = Files.createDirectories(root.resolve("run-user"));

        SandboxPolicy policy = new SandboxPolicies(home, stateDir, List.of())
                .forRun(request(RunKind.PLAN, stateDir, List.of()), List.of(), Map.of("XDG_RUNTIME_DIR", runtime.toString()));

        assertTrue(policy.hidden().contains(runtime), policy.hidden().toString());
    }

    @Test
    void withoutXdgRuntimeDirTheUsersRunUserDirIsHidden() throws IOException {
        Path runUser = Path.of("/run/user/" + Files.getAttribute(home, "unix:uid"));
        org.junit.jupiter.api.Assumptions.assumeTrue(Files.isDirectory(runUser), "no " + runUser + " here");

        SandboxPolicy policy = new SandboxPolicies(home, stateDir, List.of())
                .forRun(request(RunKind.PLAN, stateDir, List.of()), List.of(), Map.of());

        assertTrue(policy.hidden().contains(runUser), policy.hidden().toString());
    }

    @Test
    void theGitConfigHooksAndWorktreeAdminFilesAreMountedBackReadOnlyAfterTheGitDir() throws IOException {
        // Dispatch's own git later runs outside the sandbox with GH_TOKEN; config and hooks there must stay Dispatch's.
        Path common = stateDir.resolve("repos/alm/.git");
        Path gitDir = Files.createDirectories(common.resolve("worktrees/7"));
        Path worktree = Files.createDirectories(stateDir.resolve("worktrees/7"));
        Files.writeString(worktree.resolve(".git"), "gitdir: " + gitDir);

        SandboxPolicy policy = new SandboxPolicies(home, stateDir, List.of(stateDir))
                .forRun(request(RunKind.EXECUTE, worktree, List.of()), List.of());

        assertTrue(policy.readOnly().containsAll(List.of(common.resolve("config"), common.resolve("hooks"), common.resolve("info"),
                gitDir.resolve("config.worktree"), gitDir.resolve("commondir"))), policy.readOnly().toString());
    }

    private static RunRequest request(RunKind kind, Path workdir, List<Path> readOnlyDirs) {
        return new RunRequest(kind, workdir, "prompt", UUID.randomUUID(), false, readOnlyDirs, null, null, null,
                workdir.resolve("run"));
    }
}
