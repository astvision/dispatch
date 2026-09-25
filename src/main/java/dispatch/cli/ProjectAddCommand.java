package dispatch.cli;

import dispatch.config.Config;
import dispatch.config.ConfigFile;
import dispatch.config.ConfigText;
import dispatch.config.ConfigEdit;
import dispatch.config.ConfigException;
import dispatch.workspace.Git;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

/**
 * `dispatch project add <folder>`: adds a clone on this machine as a project (ADR 0014). The name, origin and base branch
 * come from the clone unless given. The config file is only replaced by a version that validates, so a mistake leaves it
 * as it was.
 */
public final class ProjectAddCommand {

    private static final Duration GIT_TIMEOUT = Duration.ofSeconds(30);

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
        ProjectProbe probe = ProjectProbe.of(options.folder(), new Git("git", null, GIT_TIMEOUT)); // before the lock: it runs git
        String name = options.name() != null ? options.name() : probe.defaultName();
        if (probe.originHadCredentials()) {
            terminal.warn("origin's URL holds credentials; it is not copied into the config");
        }
        // base and group depend on the config as ConfigFile.edit reads it under its lock, so they are only known once
        // the edit runs; captured here so the success message below can still report what was actually used.
        AtomicReference<String> baseUsed = new AtomicReference<>();
        AtomicReference<String> groupUsed = new AtomicReference<>();
        AtomicReference<String> agentUsed = new AtomicReference<>();
        try {
            ConfigFile.edit(configFile, environment, text -> {
                Config config = ConfigFile.parse(configFile, text, environment);
                if (taken(config, name)) {
                    throw new CliException("a project named '" + name + "' already exists; choose another name with --name");
                }
                String base = options.base() != null ? options.base() : probe.defaultBranch();
                if (base == null) {
                    throw new CliException(
                            "cannot tell which branch tasks in " + probe.folder() + " should start from; name it with --base");
                }
                String group = options.group() != null ? options.group() : onlyGroup(config);
                String agent = options.agent() != null ? options.agent() : defaultAgent(config);
                baseUsed.set(base);
                groupUsed.set(group);
                agentUsed.set(agent);
                String withAgent = text;
                if (!config.agents().containsKey(agent)) {
                    // Found on PATH by its usual name, as claude is; dispatch check says if it is not there. An agent type
                    // Dispatch does not know is refused by the validation of the edited file (ADR 0026).
                    withAgent = ConfigEdit.set(text, ConfigEdit.At.of("agents", agent, "command"),
                            agent.equals("claude-code") ? "claude" : agent);
                }
                List<String> lines = projectLines(new Project(name, options.alias(), probe.folder(), probe.originUrl(), base,
                        agent, options.model(), options.effort()));
                return ConfigText.addProject(withAgent, group, yaml(name), lines);
            });
        } catch (ConfigException | UncheckedIOException e) {
            throw new CliException(e.getMessage());
        }
        terminal.ok("added " + name + ": " + probe.folder() + " (base " + baseUsed.get() + ", group " + groupUsed.get()
                + ", agent " + agentUsed.get() + ")");
        terminal.say("  A running Dispatch picks it up when restarted.");
    }

    /**
     * What a new project's block says; everything the person typed is quoted where YAML would read it differently.
     *
     * @param plan    the model and effort for planning runs only; null for none
     * @param execute likewise for execution runs
     */
    public record Project(String name, String alias, Path folder, String originUrl, String baseBranch, String agent, String model,
                          String effort, Config.PhaseSettings plan, Config.PhaseSettings execute) {

        public Project(String name, String alias, Path folder, String originUrl, String baseBranch, String agent, String model,
                       String effort) {
            this(name, alias, folder, originUrl, baseBranch, agent, model, effort, null, null);
        }
    }

    public static List<String> projectLines(Project project) {
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
        phaseLines(lines, "plan", project.plan());
        phaseLines(lines, "execute", project.execute());
        return lines;
    }

    private static void phaseLines(List<String> lines, String phase, Config.PhaseSettings settings) {
        if (settings == null || settings.model() == null && settings.effort() == null) {
            return;
        }
        lines.add(phase + ":");
        if (settings.model() != null) {
            lines.add("  model: " + yaml(settings.model()));
        }
        if (settings.effort() != null) {
            lines.add("  effort: " + yaml(settings.effort()));
        }
    }

    static String yaml(String value) {
        return ConfigText.yaml(value);
    }

    static String quoted(String value) {
        return ConfigText.quoted(value);
    }

    /** Only the config's shape matters here; the token and state directory may come later, from secrets or systemd. */
    static Map<String, String> validationEnvironment(Map<String, String> processEnvironment) {
        Map<String, String> environment = new HashMap<>(processEnvironment);
        environment.putIfAbsent("TELEGRAM_BOT_TOKEN", "not-needed-to-validate");
        environment.putIfAbsent("STATE_DIRECTORY", Locations.current().stateDir().toAbsolutePath().toString());
        return environment;
    }

    private static boolean taken(Config config, String name) {
        return config.projects().stream().anyMatch(project -> project.name().equalsIgnoreCase(name)
                || project.alias() != null && project.alias().equalsIgnoreCase(name));
    }

    /** Without --agent: Claude Code when configured, as before other agents existed, else the only agent there is. */
    private static String defaultAgent(Config config) {
        if (config.agents().containsKey("claude-code")) {
            return "claude-code";
        }
        if (config.agents().size() == 1) {
            return config.agents().keySet().iterator().next();
        }
        throw new CliException("the config has several agents (" + String.join(", ", new java.util.TreeSet<>(config.agents().keySet()))
                + "); choose one with --agent");
    }

    private static String onlyGroup(Config config) {
        List<String> names = config.telegram().groups().stream().map(Config.Group::name).toList();
        if (names.size() != 1) {
            throw new CliException("the config has several groups (" + String.join(", ", names) + "); choose one with --group");
        }
        return names.getFirst();
    }
}
