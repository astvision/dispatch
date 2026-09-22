package dispatch.cli;

import dispatch.workspace.Git;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/** A systemd user service. It runs while the user is logged in, or always once lingering is enabled for them. */
final class SystemdService implements Service {

    private static final String UNIT = "dispatch.service";

    private final Path unitFile;
    private final Commands commands;
    private final String user;

    SystemdService(Path home, Commands commands, String user) {
        this.unitFile = home.resolve(".config").resolve("systemd").resolve("user").resolve(UNIT);
        this.commands = commands;
        this.user = user;
    }

    @Override
    public String describe() {
        return "systemd user service " + UNIT;
    }

    @Override
    public void install(Spec spec) {
        Service.write(unitFile, """
                # Written by dispatch service install (ADR 0016).
                [Unit]
                Description=Dispatch
                After=network-online.target

                [Service]
                Type=simple
                ExecStart=%s -jar %s run --config %s --log-file %s
                Environment=%s
                Restart=on-failure
                RestartSec=10
                # SIGTERM reaches Dispatch only, so it stops its agents itself and records their runs as interrupted (ADR 0008).
                KillMode=mixed
                TimeoutStopSec=60
                SuccessExitStatus=143

                [Install]
                WantedBy=default.target
                """.formatted(quoted(spec.java()), quoted(spec.jar()), quoted(spec.configFile()), quoted(spec.logFile()),
                quoted("PATH=" + spec.path())));
        Service.required(commands, List.of("systemctl", "--user", "daemon-reload"));
        Service.required(commands, List.of("systemctl", "--user", "enable", "--now", UNIT));
    }

    @Override
    public void start() {
        Service.required(commands, List.of("systemctl", "--user", "start", UNIT));
    }

    @Override
    public void stop() {
        Service.required(commands, List.of("systemctl", "--user", "stop", UNIT));
    }

    @Override
    public void restart() {
        Service.required(commands, List.of("systemctl", "--user", "restart", UNIT));
    }

    @Override
    public Status status() {
        if (!Files.exists(unitFile)) {
            return new Status(false, false, "not installed", List.of());
        }
        Git.Result active = commands.run(List.of("systemctl", "--user", "is-active", UNIT));
        List<String> notes = new ArrayList<>();
        Git.Result linger = commands.run(List.of("loginctl", "show-user", user, "-p", "Linger"));
        if (!linger.stdout().contains("Linger=yes")) {
            notes.add("it stops when you log out; to keep it running, run: loginctl enable-linger " + user);
        }
        String state = active.stdout().strip();
        return new Status(true, state.equals("active"), state.isEmpty() ? "unknown" : state, notes);
    }

    @Override
    public void uninstall() {
        Service.required(commands, List.of("systemctl", "--user", "disable", "--now", UNIT));
        try {
            Files.deleteIfExists(unitFile);
        } catch (IOException e) {
            throw new CliException("cannot remove " + unitFile + ": " + e.getMessage());
        }
        commands.run(List.of("systemctl", "--user", "daemon-reload"));
    }

    /** A systemd word: double-quoted, with '%' doubled so it is not read as a specifier. */
    private static String quoted(Object value) {
        return "\"" + value.toString().replace("\\", "\\\\").replace("\"", "\\\"").replace("%", "%%") + "\"";
    }
}
