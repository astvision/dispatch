package dispatch.cli;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** The dispatch command line: what was asked for, parsed without running anything. */
public final class Cli {

    private static final Set<String> VALUE_OPTIONS = Set.of("config", "name", "alias", "base", "model", "effort", "group", "log-file",
            "port");
    private static final List<String> SERVICE_ACTIONS = List.of("install", "start", "stop", "status", "uninstall");
    private static final Set<String> SWITCHES = Set.of("force", "no-browser", "advanced");

    private Cli() {
    }

    public sealed interface Invocation permits Run, Init, Check, ProjectAdd, Service, Ui, Help {
    }

    /** @param logFile where output goes instead of the terminal, as a background service runs it; null for the terminal */
    public record Run(Path configFile, Path logFile) implements Invocation {
    }

    /** @param action one of install, start, stop, status, uninstall */
    public record Service(Path configFile, String action) implements Invocation {
    }

    public record Check(Path configFile) implements Invocation {
    }

    /** @param openBrowser false with --no-browser, e.g. over SSH where the link is opened on another computer */
    public record Ui(Path configFile, int port, boolean openBrowser) implements Invocation {
    }

    /**
     * @param force    replaces an existing config and secrets file
     * @param advanced also asks for aliases, per-phase model and effort, limits, concurrency, state directory and gh
     */
    public record Init(Path configFile, boolean force, boolean advanced) implements Invocation {

        public Init(Path configFile, boolean force) {
            this(configFile, force, false);
        }
    }

    /** Options left out are null: they come from the clone, or the config's only group. */
    public record ProjectAdd(Path configFile, Path folder, String name, String alias, String base, String model, String effort,
                             String group) implements Invocation {
    }

    public record Help() implements Invocation {
    }

    public static String usage(Locations defaults) {
        return """
                usage: dispatch [command] [--config FILE]

                commands:
                  init [--advanced]
                           set up your own Dispatch: bot, you, Claude Code, projects;
                           --advanced also asks for limits, per-phase model and effort, and more
                  run      start the bot (the default)
                  check    check the config, bot token, agent, projects and GitHub CLI
                  service install|start|stop|status|uninstall
                           keep Dispatch running in the background (systemd, launchd or Task Scheduler)
                  project add FOLDER [--name NAME] [--alias ALIAS] [--base BRANCH] [--model MODEL]
                           [--effort low|medium|high|xhigh|max] [--group GROUP]
                           add a git clone on this machine as a project
                  ui [--port 7878] [--no-browser]
                           manage Dispatch in your browser; on a server: ssh -L 7878:localhost:7878 SERVER
                  help     show this help

                FILE defaults to %s
                """.formatted(defaults.configFile());
    }

    public static Invocation parse(String[] args, Locations defaults) {
        if (args.length == 0) {
            return new Run(defaults.configFile(), null);
        }
        String command = args[0];
        if (Set.of("help", "--help", "-h").contains(command)) {
            return new Help();
        }
        if (command.endsWith(".yaml") || command.endsWith(".yml")) {
            // How the systemd unit starts a team instance: dispatch.jar /etc/dispatch/<team>.yaml
            if (args.length > 1) {
                throw new CliException("unexpected '" + args[1] + "' after the config file");
            }
            return new Run(Path.of(command), null);
        }
        Arguments arguments = Arguments.parse(command, List.of(args).subList(1, args.length));
        return switch (command) {
            case "run" -> {
                arguments.allow(0, Set.of("config", "log-file"));
                yield new Run(arguments.configFile(defaults), arguments.values().containsKey("log-file")
                        ? Path.of(arguments.values().get("log-file")) : null);
            }
            case "service" -> {
                if (arguments.positional().isEmpty() || !SERVICE_ACTIONS.contains(arguments.positional().getFirst())) {
                    throw new CliException("service needs one of: " + String.join(", ", SERVICE_ACTIONS));
                }
                arguments.allow(1, Set.of("config"));
                yield new Service(arguments.configFile(defaults), arguments.positional().getFirst());
            }
            case "project" -> {
                if (arguments.positional().isEmpty() || !arguments.positional().getFirst().equals("add")) {
                    String sub = arguments.positional().isEmpty() ? "" : " " + arguments.positional().getFirst();
                    throw new CliException("unknown command 'project" + sub + "'");
                }
                if (arguments.positional().size() < 2) {
                    throw new CliException("project add needs the folder of a git clone");
                }
                arguments.allow(2, Set.of("config", "name", "alias", "base", "model", "effort", "group"));
                yield new ProjectAdd(arguments.configFile(defaults), Path.of(arguments.positional().get(1)), arguments.values().get("name"),
                        arguments.values().get("alias"), arguments.values().get("base"), arguments.values().get("model"),
                        arguments.values().get("effort"), arguments.values().get("group"));
            }
            case "init" -> {
                arguments.allow(0, Set.of("config", "force", "advanced"));
                yield new Init(arguments.configFile(defaults), arguments.switches().contains("force"),
                        arguments.switches().contains("advanced"));
            }
            case "check" -> {
                arguments.allow(0, Set.of("config"));
                yield new Check(arguments.configFile(defaults));
            }
            case "ui" -> {
                arguments.allow(0, Set.of("config", "port", "no-browser"));
                yield new Ui(arguments.configFile(defaults), port(arguments.values().getOrDefault("port", "7878")),
                        !arguments.switches().contains("no-browser"));
            }
            default -> throw new CliException("unknown command '" + command + "'");
        };
    }

    private static int port(String value) {
        try {
            int port = Integer.parseInt(value);
            if (port >= 1 && port <= 65535) {
                return port;
            }
        } catch (NumberFormatException e) {
            // falls through to the message below
        }
        throw new CliException("--port needs a number from 1 to 65535, not " + value);
    }

    /** A command's positional arguments, --name value options and --switches. */
    private record Arguments(String command, List<String> positional, Map<String, String> values, Set<String> switches) {

        static Arguments parse(String command, List<String> args) {
            List<String> positional = new ArrayList<>();
            Map<String, String> values = new HashMap<>();
            Set<String> switches = new HashSet<>();
            for (int i = 0; i < args.size(); i++) {
                String arg = args.get(i);
                if (!arg.startsWith("--")) {
                    positional.add(arg);
                    continue;
                }
                String name = arg.substring(2);
                if (SWITCHES.contains(name)) {
                    switches.add(name);
                } else if (VALUE_OPTIONS.contains(name)) {
                    if (i + 1 >= args.size() || args.get(i + 1).startsWith("--")) {
                        throw new CliException(arg + " needs a value");
                    }
                    values.put(name, args.get(++i));
                } else {
                    throw new CliException(command + " does not take " + arg);
                }
            }
            return new Arguments(command, positional, values, switches);
        }

        void allow(int positionalCount, Set<String> options) {
            if (positional.size() > positionalCount) {
                throw new CliException(command + " does not take '" + positional.get(positionalCount) + "'");
            }
            for (String name : values.keySet()) {
                if (!options.contains(name)) {
                    throw new CliException(command + " does not take --" + name);
                }
            }
            for (String name : switches) {
                if (!options.contains(name)) {
                    throw new CliException(command + " does not take --" + name);
                }
            }
        }

        Path configFile(Locations defaults) {
            return values.containsKey("config") ? Path.of(values.get("config")) : defaults.configFile();
        }
    }
}
