package dispatch.agent.sandbox;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class SandboxesTest {

    private final List<List<String>> trials = new ArrayList<>();

    @Test
    void linuxWithAWorkingBwrapIsSandboxed() {
        Sandbox sandbox = Sandboxes.detect(SandboxSetting.AUTO, probe("Linux", "/usr/bin/bwrap", new Probe.Trial(0, ""), new Probe.Trial(0, "")));

        assertInstanceOf(Bubblewrap.class, sandbox);
        assertEquals(List.of(Path.of("/usr/bin/bwrap").toString(), "--ro-bind", "/", "/", "--unshare-pid", "--proc", "/proc", "true"),
                trials.getFirst());
    }

    @Test
    void aSecondTrialFindsOutWhetherOverlaysWork() {
        Sandbox with = Sandboxes.detect(SandboxSetting.AUTO, probe("Linux", "/usr/bin/bwrap", new Probe.Trial(0, ""), new Probe.Trial(0, "")));
        assertTrue(with.copyOnWrite());
        assertEquals(List.of(Path.of("/usr/bin/bwrap").toString(), "--ro-bind", "/", "/", "--unshare-pid", "--proc", "/proc",
                "--overlay-src", "/etc", "--tmp-overlay", "/etc", "true"), trials.get(1));

        Sandbox without = Sandboxes.detect(SandboxSetting.AUTO, probe("Linux", "/usr/bin/bwrap", new Probe.Trial(0, ""),
                new Probe.Trial(1, "bwrap: Unknown option --overlay-src")));
        assertInstanceOf(Bubblewrap.class, without);
        assertFalse(without.copyOnWrite());
    }

    @Test
    void offInConfigIsNeverSandboxedAndNothingIsTried() {
        Sandbox sandbox = Sandboxes.detect(SandboxSetting.OFF, probe("Linux", "/usr/bin/bwrap", new Probe.Trial(0, "")));

        assertEquals(new NoSandbox("turned off in config (sandbox: off)"), sandbox);
        assertEquals(List.of(), trials);
    }

    @Test
    void linuxWithoutBwrapSaysToInstallIt() {
        assertEquals(new NoSandbox("bubblewrap (bwrap) is not installed"),
                Sandboxes.detect(SandboxSetting.AUTO, probe("Linux", null, null)));
    }

    @Test
    void aBwrapThatCannotCreateASandboxGivesItsOwnFirstLine() {
        Probe.Trial blocked = new Probe.Trial(1, "bwrap: setting up uid map: Permission denied\nmore detail");

        assertEquals(new NoSandbox("bwrap cannot create a sandbox here: bwrap: setting up uid map: Permission denied"),
                Sandboxes.detect(SandboxSetting.AUTO, probe("Linux", "/usr/bin/bwrap", blocked)));
    }

    @Test
    void macOsAndWindowsFallBack() {
        assertEquals(new NoSandbox("the macOS sandbox is not built yet"),
                Sandboxes.detect(SandboxSetting.AUTO, probe("Mac OS X", null, null)));
        assertEquals(new NoSandbox("not available on Windows"),
                Sandboxes.detect(SandboxSetting.AUTO, probe("Windows 11", null, null)));
    }

    @Test
    void settingReadsAutoOffOrNothing() {
        assertEquals(Optional.of(SandboxSetting.AUTO), SandboxSetting.fromConfig(null));
        assertEquals(Optional.of(SandboxSetting.AUTO), SandboxSetting.fromConfig("auto"));
        assertEquals(Optional.of(SandboxSetting.OFF), SandboxSetting.fromConfig("off"));
        assertEquals(Optional.empty(), SandboxSetting.fromConfig("on"));
        assertEquals(Optional.empty(), SandboxSetting.fromConfig("OFF"));
    }

    /** Each trial gets the next answer; a null answer (no bwrap to try) is skipped. */
    private Probe probe(String os, String bwrap, Probe.Trial... answers) {
        java.util.Deque<Probe.Trial> left = new java.util.ArrayDeque<>();
        if (answers != null) {
            for (Probe.Trial answer : answers) {
                if (answer != null) {
                    left.add(answer);
                }
            }
        }
        return new Probe() {
            @Override
            public String osName() {
                return os;
            }

            @Override
            public Optional<Path> find(String command) {
                return "bwrap".equals(command) && bwrap != null ? Optional.of(Path.of(bwrap)) : Optional.empty();
            }

            @Override
            public Trial trial(List<String> commandLine) {
                trials.add(commandLine);
                return left.isEmpty() ? new Trial(1, "no answer") : left.pop();
            }
        };
    }
}
