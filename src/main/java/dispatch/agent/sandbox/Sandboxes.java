package dispatch.agent.sandbox;

import java.nio.file.Path;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

/** Picks this machine's sandbox once, at startup (spec: "Detection"). */
public final class Sandboxes {

    private Sandboxes() {
    }

    public static Sandbox detect(SandboxSetting setting, Probe probe) {
        if (setting == SandboxSetting.OFF) {
            return new NoSandbox("turned off in config (sandbox: off)");
        }
        String os = probe.osName().toLowerCase(Locale.ROOT);
        if (os.startsWith("windows")) {
            return new NoSandbox("not available on Windows");
        }
        if (os.startsWith("mac")) {
            return new NoSandbox("the macOS sandbox is not built yet");
        }
        if (!os.startsWith("linux")) {
            return new NoSandbox("not available on " + probe.osName());
        }
        Optional<Path> bwrap = probe.find("bwrap");
        if (bwrap.isEmpty()) {
            return new NoSandbox("bubblewrap (bwrap) is not installed");
        }
        // The same namespaces a run needs: Ubuntu 24's AppArmor rule, for one, refuses them to unprivileged users.
        Probe.Trial trial = probe.trial(List.of(bwrap.get().toString(), "--ro-bind", "/", "/", "--unshare-pid", "--proc", "/proc", "true"));
        if (trial.exitCode() != 0) {
            return new NoSandbox("bwrap cannot create a sandbox here: " + trial.output().strip().lines().findFirst().orElse("exit " + trial.exitCode()));
        }
        return new Bubblewrap(bwrap.get().toString());
    }
}
