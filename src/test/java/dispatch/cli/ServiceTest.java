package dispatch.cli;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dispatch.workspace.Git;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.DisabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;

/** The per-user service on each OS, checked through the files written and the commands run; nothing is registered for real. */
class ServiceTest {

    private static final Path JAVA = Path.of("/opt/java/bin/java");

    @TempDir
    Path dir;

    private final Recorder commands = new Recorder();

    @Test
    @DisabledOnOs(value = OS.WINDOWS, disabledReason = "a systemd unit only ever holds Linux paths; Windows ones get escaped backslashes")
    void linuxGetsASystemdUserUnitThatRestartsAndLogsToAFile() throws IOException {
        Path home = dir.resolve("home bold");
        Service service = Service.forOs("Linux", home, commands, "bold");
        commands.answer("systemctl --user is-active", 0, "active\n");
        commands.answer("loginctl show-user", 0, "Linger=no\n");

        service.install(spec(home));
        Service.Status status = service.status();

        String unit = Files.readString(home.resolve(".config/systemd/user/dispatch.service"));
        assertTrue(unit.contains("ExecStart=\"" + JAVA + "\" -jar \"" + home.resolve("dispatch/dispatch.jar") + "\" run --config \""
                + home.resolve(".config/dispatch/dispatch.yaml") + "\" --log-file \"" + home.resolve("state/dispatch.log") + "\""), unit);
        assertTrue(unit.contains("Environment=\"PATH=/usr/bin:/home/bold/.local/bin\""), unit);
        assertTrue(unit.contains("Restart=on-failure") && unit.contains("KillMode=mixed") && unit.contains("WantedBy=default.target"), unit);
        assertEquals(List.of("systemctl --user daemon-reload", "systemctl --user enable --now dispatch.service"), commands.run.subList(0, 2));
        assertTrue(status.running());
        assertTrue(status.notes().stream().anyMatch(note -> note.contains("loginctl enable-linger bold")), status.notes().toString());

        service.uninstall();

        assertFalse(Files.exists(home.resolve(".config/systemd/user/dispatch.service")));
        assertTrue(commands.run.contains("systemctl --user disable --now dispatch.service"), commands.run.toString());
    }

    @Test
    void macOsGetsALaunchAgentThatIsKeptAlive() throws IOException {
        Path home = dir.resolve("home");
        Service service = Service.forOs("Mac OS X", home, commands, "bold");
        commands.answer("id -u", 0, "501\n");
        commands.answer("launchctl print", 0, "state = running\npid = 4242\n");

        service.install(spec(home));
        Service.Status status = service.status();

        String plist = Files.readString(home.resolve("Library/LaunchAgents/io.dispatch.agent.plist"));
        assertTrue(plist.contains("<string>" + JAVA + "</string>") && plist.contains("<string>--log-file</string>"), plist);
        assertTrue(plist.contains("<key>SuccessfulExit</key>") && plist.contains("<key>RunAtLoad</key>"), plist);
        assertTrue(plist.contains("<string>/usr/bin:/home/bold/.local/bin</string>"), plist);
        assertTrue(commands.run.contains("launchctl bootstrap gui/501 " + home.resolve("Library/LaunchAgents/io.dispatch.agent.plist")),
                commands.run.toString());
        assertTrue(status.running());

        service.stop();

        assertTrue(commands.run.contains("launchctl bootout gui/501/io.dispatch.agent"), "a kept-alive agent is stopped by unloading it");
    }

    @Test
    void windowsGetsAScheduledTaskAtLogonWithoutAConsoleWindow() throws IOException {
        Path home = dir.resolve("home");
        Service service = Service.forOs("Windows 11", home, commands, "ACME\\bold");
        commands.answer("schtasks /Query", 0, "TaskName: \\Dispatch\r\nStatus: Running\r\n");

        Service.Spec spec = new Service.Spec(Path.of("C:/Java/bin/java.exe"), Path.of("C:/Dispatch/dispatch.jar"),
                Path.of("C:/Users/bold/AppData/Roaming/Dispatch/dispatch.yaml"), Path.of("C:/Users/bold/AppData/Local/Dispatch/dispatch.log"),
                "C:\\Windows;C:\\Users\\bold\\.local\\bin", home.resolve("state"));
        service.install(spec);
        Service.Status status = service.status();

        Path xml = home.resolve("state/dispatch-task.xml");
        byte[] bytes = Files.readAllBytes(xml);
        assertEquals((byte) 0xFF, bytes[0], "UTF-16 with a byte order mark, as schtasks expects");
        String task = new String(bytes, StandardCharsets.UTF_16);
        assertTrue(task.contains("javaw.exe") && task.contains("<LogonTrigger>") && task.contains("<UserId>ACME\\bold</UserId>"), task);
        assertTrue(task.contains("<RestartOnFailure>") && task.contains("<ExecutionTimeLimit>PT0S</ExecutionTimeLimit>"), task);
        assertTrue(commands.run.contains("schtasks /Create /TN Dispatch /XML " + xml + " /F"), commands.run.toString());
        assertTrue(commands.run.contains("schtasks /Run /TN Dispatch"), commands.run.toString());
        assertTrue(status.running());
    }

    @Test
    void failingCommandIsReportedWithItsOutput() {
        Service service = Service.forOs("Linux", dir, commands, "bold");
        commands.answer("systemctl --user enable", 1, "Failed to connect to bus: No medium found\n");

        CliException error = org.junit.jupiter.api.Assertions.assertThrows(CliException.class, () -> service.install(spec(dir)));

        assertTrue(error.getMessage().contains("No medium found"), error.getMessage());
    }

    private static Service.Spec spec(Path home) {
        return new Service.Spec(JAVA, home.resolve("dispatch/dispatch.jar"), home.resolve(".config/dispatch/dispatch.yaml"),
                home.resolve("state/dispatch.log"), "/usr/bin:/home/bold/.local/bin", home.resolve("state"));
    }

    /** Runs nothing: records each command line and answers from a script by its start. */
    private static final class Recorder implements Service.Commands {

        final List<String> run = new ArrayList<>();
        private final Map<String, Git.Result> answers = new LinkedHashMap<>();

        void answer(String start, int exitCode, String output) {
            answers.put(start, new Git.Result(exitCode, output, exitCode == 0 ? "" : output));
        }

        @Override
        public Git.Result run(List<String> commandLine) {
            String line = String.join(" ", commandLine);
            run.add(line);
            return answers.entrySet().stream().filter(answer -> line.startsWith(answer.getKey())).map(Map.Entry::getValue).findFirst()
                    .orElse(new Git.Result(0, "", ""));
        }
    }
}
