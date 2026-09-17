package dispatch.cli;

import dispatch.config.Config;
import dispatch.config.ConfigException;
import dispatch.config.ConfigLoader;
import dispatch.workspace.Git;
import java.io.IOException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * `dispatch project add <folder>`: adds a clone on this machine as a project (ADR 0014). The name, origin and base branch
 * come from the clone unless given. The config file is only replaced by a version that validates, so a mistake leaves it
 * as it was.
 */
public final class ProjectAddCommand {

    private static final Duration GIT_TIMEOUT = Duration.ofSeconds(30);
    private static final Pattern PLAIN = Pattern.compile("[A-Za-z0-9._/-]+");

    private final Terminal terminal;

    public ProjectAddCommand(Terminal terminal) {
        this.terminal = terminal;
    }

    public int run(Cli.ProjectAdd options, Map<String, String> processEnvironment) {
        try {
            add(options, processEnvironment);
            return 0;
        } catch (CliException e) {
            terminal.fail(e.getMessage());
            return 1;
        }
    }

    private void add(Cli.ProjectAdd options, Map<String, String> processEnvironment) {
        Path configFile = options.configFile();
        if (!Files.exists(configFile)) {
            throw new CliException("no config at " + configFile + "; create one with: dispatch init");
        }
        Map<String, String> environment = validationEnvironment(processEnvironment);
        Config config = load(configFile, configFile, environment);
        ProjectProbe probe = ProjectProbe.of(options.folder(), new Git("git", null, GIT_TIMEOUT));
        String name = options.name() != null ? options.name() : probe.defaultName();
        if (taken(config, name)) {
            throw new CliException("a project named '" + name + "' already exists; choose another name with --name");
        }
        String base = options.base() != null ? options.base() : probe.defaultBranch();
        if (base == null) {
            throw new CliException("cannot tell which branch tasks in " + probe.folder() + " should start from; name it with --base");
        }
        String group = options.group() != null ? options.group() : onlyGroup(config);
        if (probe.originHadCredentials()) {
            terminal.warn("origin's URL holds credentials; it is not copied into the config");
        }
        List<String> lines = projectLines(new Project(name, options.alias(), probe.folder(), probe.originUrl(), base,
                config.agents().keySet().iterator().next(), options.model(), options.effort()));
        String edited;
        try {
            edited = ConfigText.addProject(Files.readString(configFile), group, yaml(name), lines);
        } catch (IOException e) {
            throw new CliException("cannot read " + configFile + ": " + e.getMessage());
        }
        replaceValidated(configFile, edited, environment);
        terminal.ok("added " + name + ": " + probe.folder() + " (base " + base + ", group " + group + ")");
        terminal.say("  A running Dispatch picks it up when restarted.");
    }

    /** What a new project's block says; everything the person typed is quoted where YAML would read it differently. */
    record Project(String name, String alias, Path folder, String originUrl, String baseBranch, String agent, String model, String effort) {
    }

    static List<String> projectLines(Project project) {
        List<String> lines = new ArrayList<>(List.of("name: " + yaml(project.name())));
        if (project.alias() != null) {
            lines.add("alias: " + yaml(project.alias()));
        }
        lines.add("path: " + quoted(project.folder().toString()));
        if (project.originUrl() != null) {
            lines.add("repo: " + quoted(project.originUrl()));
        }
        lines.add("baseBranch: " + yaml(project.baseBranch()));
        lines.add("agent: " + yaml(project.agent()));
        if (project.model() != null) {
            lines.add("model: " + yaml(project.model()));
        }
        if (project.effort() != null) {
            lines.add("effort: " + yaml(project.effort()));
        }
        return lines;
    }

    static String yaml(String value) {
        return PLAIN.matcher(value).matches() ? value : quoted(value);
    }

    static String quoted(String value) {
        return "'" + value.replace("'", "''") + "'";
    }

    /** Only the config's shape matters here; the token and state directory may come later, from secrets or systemd. */
    static Map<String, String> validationEnvironment(Map<String, String> processEnvironment) {
        Map<String, String> environment = new HashMap<>(processEnvironment);
        environment.putIfAbsent("TELEGRAM_BOT_TOKEN", "not-needed-to-validate");
        environment.putIfAbsent("STATE_DIRECTORY", Locations.current().stateDir().toAbsolutePath().toString());
        return environment;
    }

    private static Config load(Path file, Path shownAs, Map<String, String> environment) {
        try {
            return ConfigLoader.load(file, environment);
        } catch (ConfigException e) {
            throw new CliException(e.getMessage().replace(file.toString(), shownAs.toString()));
        }
    }

    private static boolean taken(Config config, String name) {
        return config.projects().stream().anyMatch(project -> project.name().equalsIgnoreCase(name)
                || project.alias() != null && project.alias().equalsIgnoreCase(name));
    }

    private static String onlyGroup(Config config) {
        List<String> names = config.telegram().groups().stream().map(Config.Group::name).toList();
        if (names.size() != 1) {
            throw new CliException("the config has several groups (" + String.join(", ", names) + "); choose one with --group");
        }
        return names.getFirst();
    }

    private void replaceValidated(Path configFile, String edited, Map<String, String> environment) {
        Path candidate = null;
        try {
            candidate = Files.createTempFile(configFile.toAbsolutePath().getParent(), ".dispatch-", ".yaml");
            Files.writeString(candidate, edited);
            load(candidate, configFile, environment);
            try {
                Files.move(candidate, configFile, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            } catch (AtomicMoveNotSupportedException e) {
                Files.move(candidate, configFile, StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (IOException e) {
            throw new CliException("cannot write " + configFile + ": " + e.getMessage());
        } finally {
            if (candidate != null) {
                try {
                    Files.deleteIfExists(candidate);
                } catch (IOException e) {
                    terminal.warn("could not remove the draft " + candidate + ": " + e.getMessage());
                }
            }
        }
    }
}
