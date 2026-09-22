package dispatch.cli;

import dispatch.workspace.Git;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/**
 * A Task Scheduler task that starts at the user's logon with javaw, so no console window opens. Task Scheduler is asked to
 * restart it after a failure.
 */
final class WindowsTaskService implements Service {

    private static final String TASK = "Dispatch";

    private final Commands commands;
    private final String user;

    WindowsTaskService(Commands commands, String user) {
        this.commands = commands;
        this.user = user;
    }

    @Override
    public String describe() {
        return "Task Scheduler task " + TASK;
    }

    @Override
    public void install(Spec spec) {
        Path javaw = spec.java().resolveSibling("javaw.exe");
        String arguments = "-jar " + quoted(spec.jar()) + " run --config " + quoted(spec.configFile()) + " --log-file " + quoted(spec.logFile());
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
        Path file = spec.stateDir().resolve("dispatch-task.xml");
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
        Service.required(commands, List.of("schtasks", "/Create", "/TN", TASK, "/XML", file.toString(), "/F"));
        Service.required(commands, List.of("schtasks", "/Run", "/TN", TASK));
    }

    @Override
    public void start() {
        Service.required(commands, List.of("schtasks", "/Run", "/TN", TASK));
    }

    @Override
    public void stop() {
        Service.required(commands, List.of("schtasks", "/End", "/TN", TASK));
    }

    /** The default restart's stop() fails (and stops there) when the task is not currently running; restart must still
     * reach /Run in that case, so its own stop step ignores /End's failure. */
    @Override
    public void restart() {
        commands.run(List.of("schtasks", "/End", "/TN", TASK));
        start();
    }

    @Override
    public Status status() {
        Git.Result query = commands.run(List.of("schtasks", "/Query", "/TN", TASK, "/FO", "LIST"));
        if (query.exitCode() != 0) {
            return new Status(false, false, "not installed", List.of());
        }
        String state = query.stdout().lines().map(String::strip).filter(line -> line.startsWith("Status:"))
                .map(line -> line.substring("Status:".length()).strip()).findFirst().orElse("unknown");
        return new Status(true, state.equalsIgnoreCase("Running"), state, List.of());
    }

    @Override
    public void uninstall() {
        commands.run(List.of("schtasks", "/End", "/TN", TASK));
        Service.required(commands, List.of("schtasks", "/Delete", "/TN", TASK, "/F"));
    }

    private static String quoted(Path path) {
        return "\"" + path + "\"";
    }

    private static String xml(Object value) {
        return value.toString().replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;");
    }
}
