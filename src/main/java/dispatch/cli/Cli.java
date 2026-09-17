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

    private static final Set<String> VALUE_OPTIONS = Set.of("config", "name", "alias", "base", "model", "effort", "group");
    private static final Set<String> SWITCHES = Set.of("force");

    private Cli() {
    }

    public sealed interface Invocation permits Run, Init, Check, ProjectAdd, Help {
    }

    public record Run(Path configFile) implements Invocation {
    }

    public record Check(Path configFile) implements Invocation {
    }

    /** @param force replaces an existing config and secrets file */
    public record Init(Path configFile, boolean force) implements Invocation {
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
                  init     set up your own Dispatch: bot, you, Claude Code, projects
                  run      start the bot (the default)
                  check    check the config, bot token, agent, projects and GitHub CLI
                  project add FOLDER [--name NAME] [--alias ALIAS] [--base BRANCH] [--model MODEL]
                           [--effort low|medium|high|xhigh|max] [--group GROUP]
                           add a git clone on this machine as a project
                  help     show this help

                FILE defaults to %s
                """.formatted(defaults.configFile());
    }

    public static Invocation parse(String[] args, Locations defaults) {
        if (args.length == 0) {
            return new Run(defaults.configFile());
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
            return new Run(Path.of(command));
        }
        Arguments arguments = Arguments.parse(command, List.of(args).subList(1, args.length));
        return switch (command) {
            case "run" -> {
                arguments.allow(0, Set.of("config"));
                yield new Run(arguments.configFile(defaults));
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
                arguments.allow(0, Set.of("config", "force"));
                yield new Init(arguments.configFile(defaults), arguments.switches().contains("force"));
            }
            case "check" -> {
                arguments.allow(0, Set.of("config"));
                yield new Check(arguments.configFile(defaults));
            }
            default -> throw new CliException("unknown command '" + command + "'");
        };
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
