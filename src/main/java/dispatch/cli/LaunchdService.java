package dispatch.cli;

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

    private static final String LABEL = "io.dispatch.agent";

    private final Path plist;
    private final Commands commands;

    LaunchdService(Path home, Commands commands) {
        this.plist = home.resolve("Library").resolve("LaunchAgents").resolve(LABEL + ".plist");
        this.commands = commands;
    }

    @Override
    public String describe() {
        return "launchd agent " + LABEL;
    }

    @Override
    public void install(Spec spec) {
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
                    <string>run</string>
                    <string>--config</string>
                    <string>%s</string>
                    <string>--log-file</string>
                    <string>%s</string>
                  </array>
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
                """.formatted(LABEL, xml(spec.java()), xml(spec.jar()), xml(spec.configFile()), xml(spec.logFile()), xml(spec.path())));
        commands.run(List.of("launchctl", "bootout", domain() + "/" + LABEL));
        Service.required(commands, List.of("launchctl", "bootstrap", domain(), plist.toString()));
    }

    @Override
    public void start() {
        Service.required(commands, List.of("launchctl", "bootstrap", domain(), plist.toString()));
    }

    @Override
    public void stop() {
        Service.required(commands, List.of("launchctl", "bootout", domain() + "/" + LABEL));
    }

    @Override
    public Status status() {
        if (!Files.exists(plist)) {
            return new Status(false, false, "not installed", List.of());
        }
        Git.Result printed = commands.run(List.of("launchctl", "print", domain() + "/" + LABEL));
        if (printed.exitCode() != 0) {
            return new Status(true, false, "not loaded; start it with: dispatch service start", List.of());
        }
        boolean running = printed.stdout().contains("state = running");
        String pid = printed.stdout().lines().map(String::strip).filter(line -> line.startsWith("pid = ")).findFirst().orElse("");
        return new Status(true, running, running ? "running" + (pid.isEmpty() ? "" : ", " + pid) : "loaded, not running", List.of());
    }

    @Override
    public void uninstall() {
        commands.run(List.of("launchctl", "bootout", domain() + "/" + LABEL));
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
