package dispatch.cli;

import dispatch.Text;
import dispatch.workspace.Git;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;

/**
 * A Task Scheduler task that starts at the user's logon with javaw, so no console window opens. Task Scheduler is asked to
 * restart it after a failure.
 */
final class WindowsTaskService implements Service {

    private final Commands commands;
    private final String user;
    private final Kind kind;
    private final String instance;

    WindowsTaskService(Commands commands, String user, Kind kind, String instance) {
        this.commands = commands;
        this.user = user;
        this.kind = kind;
        this.instance = instance;
    }

    @Override
    public Kind kind() {
        return kind;
    }

    @Override
    public String describe() {
        return "Task Scheduler task " + kind.windowsTask(instance);
    }

    @Override
    public void install(Spec spec) {
        Path javaw = spec.java().resolveSibling("javaw.exe");
        String line = Service.argumentsLine(spec, kind, WindowsTaskService::quoted);
        String arguments = "-jar " + quoted(spec.jar()) + " " + line;
        String task = """
                <?xml version="1.0" encoding="UTF-16"?>
                <!-- Written by dispatch service install (ADR 0016). -->
                <Task version="1.2" xmlns="http://schemas.microsoft.com/windows/2004/02/mit/task">
                  <Triggers>
                    <LogonTrigger>
                      <UserId>%s</UserId>
                    </LogonTrigger>
                  </Triggers>
                  <Principals>
                    <Principal id="Author">
                      <UserId>%s</UserId>
                      <LogonType>InteractiveToken</LogonType>
                      <RunLevel>LeastPrivilege</RunLevel>
                    </Principal>
                  </Principals>
                  <Settings>
                    <MultipleInstancesPolicy>IgnoreNew</MultipleInstancesPolicy>
                    <DisallowStartIfOnBatteries>false</DisallowStartIfOnBatteries>
                    <StopIfGoingOnBatteries>false</StopIfGoingOnBatteries>
                    <ExecutionTimeLimit>PT0S</ExecutionTimeLimit>
                    <RestartOnFailure>
                      <Interval>PT1M</Interval>
                      <Count>10</Count>
                    </RestartOnFailure>
                  </Settings>
                  <Actions Context="Author">
                    <Exec>
                      <Command>%s</Command>
                      <Arguments>%s</Arguments>
                    </Exec>
                  </Actions>
                </Task>
                """.formatted(xml(user), xml(user), xml(javaw), xml(arguments));
        Path file = spec.stateDir().resolve(kind.windowsTask(instance).toLowerCase(Locale.ROOT) + "-task.xml");
        try {
            Files.createDirectories(file.getParent());
            // schtasks reads task XML as UTF-16LE, marked by its byte order mark.
            byte[] text = task.getBytes(StandardCharsets.UTF_16LE);
            byte[] withMark = new byte[text.length + 2];
            withMark[0] = (byte) 0xFF;
            withMark[1] = (byte) 0xFE;
            System.arraycopy(text, 0, withMark, 2, text.length);
            Files.write(file, withMark);
        } catch (IOException e) {
            throw new CliException("cannot write " + file + ": " + e.getMessage());
        }
        Service.required(commands, List.of("schtasks", "/Create", "/TN", kind.windowsTask(instance), "/XML", file.toString(), "/F"));
        Service.required(commands, List.of("schtasks", "/Run", "/TN", kind.windowsTask(instance)));
    }

    @Override
    public void start() {
        Service.required(commands, List.of("schtasks", "/Run", "/TN", kind.windowsTask(instance)));
    }

    @Override
    public void stop() {
        Service.required(commands, List.of("schtasks", "/End", "/TN", kind.windowsTask(instance)));
    }

    /** The default restart's stop() fails (and stops there) when the task is not currently running; restart must still
     * reach /Run in that case, so its own stop step ignores /End's failure. */
    @Override
    public void restart() {
        commands.run(List.of("schtasks", "/End", "/TN", kind.windowsTask(instance)));
        start();
    }

    @Override
    public Status status() {
        Git.Result query = commands.run(List.of("schtasks", "/Query", "/TN", kind.windowsTask(instance), "/FO", "LIST"));
        if (query.exitCode() != 0) {
            return new Status(false, false, Text.of("service.notInstalled"), List.of());
        }
        java.util.Optional<String> state = query.stdout().lines().map(String::strip).filter(line -> line.startsWith("Status:"))
                .map(line -> line.substring("Status:".length()).strip()).findFirst();
        return new Status(true, state.map(word -> word.equalsIgnoreCase("Running")).orElse(false),
                state.<Text>map(Text::raw).orElse(Text.of("service.unknown")), List.of());
    }

    @Override
    public void uninstall() {
        commands.run(List.of("schtasks", "/End", "/TN", kind.windowsTask(instance)));
        Service.required(commands, List.of("schtasks", "/Delete", "/TN", kind.windowsTask(instance), "/F"));
    }

    private static String quoted(Object path) {
        return "\"" + path + "\"";
    }

    private static String xml(Object value) {
        return value.toString().replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;");
    }
}
