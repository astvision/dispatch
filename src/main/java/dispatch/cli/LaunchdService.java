package dispatch.cli;

import dispatch.Text;
import dispatch.workspace.Git;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/**
 * A launchd agent in the user's GUI session. It is kept alive after a failure, so stopping it means unloading it; it loads
 * again at the next login.
 */
final class LaunchdService implements Service {

    private final Path plist;
    private final Commands commands;
    private final Kind kind;
    private final String instance;

    LaunchdService(Path home, Commands commands, Kind kind, String instance) {
        this.plist = home.resolve("Library").resolve("LaunchAgents").resolve(kind.launchdLabel(instance) + ".plist");
        this.commands = commands;
        this.kind = kind;
        this.instance = instance;
    }

    @Override
    public Kind kind() {
        return kind;
    }

    @Override
    public String describe() {
        return "launchd agent " + kind.launchdLabel(instance);
    }

    @Override
    public void install(Spec spec) {
        StringBuilder arguments = new StringBuilder();
        for (String argument : spec.arguments(kind)) {
            arguments.append("    <string>").append(xml(argument)).append("</string>\n");
        }
        Service.write(plist, """
                <?xml version="1.0" encoding="UTF-8"?>
                <!DOCTYPE plist PUBLIC "-//Apple//DTD PLIST 1.0//EN" "http://www.apple.com/DTDs/PropertyList-1.0.dtd">
                <!-- Written by dispatch service install (ADR 0016). -->
                <plist version="1.0">
                <dict>
                  <key>Label</key><string>%s</string>
                  <key>ProgramArguments</key>
                  <array>
                    <string>%s</string>
                    <string>-jar</string>
                    <string>%s</string>
                %s  </array>
                  <key>EnvironmentVariables</key>
                  <dict>
                    <key>PATH</key><string>%s</string>
                  </dict>
                  <key>RunAtLoad</key><true/>
                  <key>KeepAlive</key>
                  <dict>
                    <key>SuccessfulExit</key><false/>
                  </dict>
                  <key>ThrottleInterval</key><integer>10</integer>
                </dict>
                </plist>
                """.formatted(kind.launchdLabel(instance), xml(spec.java()), xml(spec.jar()), arguments, xml(spec.path())));
        commands.run(List.of("launchctl", "bootout", domain() + "/" + kind.launchdLabel(instance)));
        Service.required(commands, List.of("launchctl", "bootstrap", domain(), plist.toString()));
    }

    @Override
    public void start() {
        Service.required(commands, List.of("launchctl", "bootstrap", domain(), plist.toString()));
    }

    @Override
    public void stop() {
        Service.required(commands, List.of("launchctl", "bootout", domain() + "/" + kind.launchdLabel(instance)));
    }

    /**
     * {@code kickstart -k} restarts a loaded agent in one step, without the unload/reload gap of the default stop();
     * start(): {@code bootout} returns before teardown finishes, so a bootstrap right after it often fails and leaves
     * the agent stopped. When the agent is installed but not loaded, kickstart fails first, so this falls back to
     * {@link #start} (bootstrap) instead.
     */
    @Override
    public void restart() {
        if (commands.run(List.of("launchctl", "kickstart", "-k", domain() + "/" + kind.launchdLabel(instance))).exitCode() != 0) {
            start();
        }
    }

    @Override
    public Status status() {
        if (!Files.exists(plist)) {
            return new Status(false, false, Text.of("service.notInstalled"), List.of());
        }
        Git.Result printed = commands.run(List.of("launchctl", "print", domain() + "/" + kind.launchdLabel(instance)));
        if (printed.exitCode() != 0) {
            return new Status(true, false, Text.of("service.notLoaded", kind.manageCommand(instance)), List.of());
        }
        boolean running = printed.stdout().contains("state = running");
        String pid = printed.stdout().lines().map(String::strip).filter(line -> line.startsWith("pid = ")).findFirst().orElse("");
        Text detail = !running ? Text.of("service.loadedNotRunning") : pid.isEmpty() ? Text.of("service.running") : Text.of("service.runningWith", pid);
        return new Status(true, running, detail, List.of());
    }

    @Override
    public void uninstall() {
        commands.run(List.of("launchctl", "bootout", domain() + "/" + kind.launchdLabel(instance)));
        try {
            Files.deleteIfExists(plist);
        } catch (IOException e) {
            throw new CliException("cannot remove " + plist + ": " + e.getMessage());
        }
    }

    /** The user's GUI session, where agents run: gui/&lt;uid&gt;. */
    private String domain() {
        return "gui/" + Service.required(commands, List.of("id", "-u")).stdout().strip();
    }

    private static String xml(Object value) {
        return value.toString().replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
    }
}
