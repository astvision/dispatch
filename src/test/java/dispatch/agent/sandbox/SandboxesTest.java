package dispatch.agent.sandbox;

import static org.junit.jupiter.api.Assertions.assertEquals;
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
        Sandbox sandbox = Sandboxes.detect(SandboxSetting.AUTO, probe("Linux", "/usr/bin/bwrap", new Probe.Trial(0, "")));

        assertInstanceOf(Bubblewrap.class, sandbox);
        assertEquals(List.of(List.of(Path.of("/usr/bin/bwrap").toString(), "--ro-bind", "/", "/", "--unshare-pid", "--proc", "/proc", "true")), trials);
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

    private Probe probe(String os, String bwrap, Probe.Trial trial) {
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
                return trial;
            }
        };
    }
}
