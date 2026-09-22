package dispatch.ui;

import com.fasterxml.jackson.databind.JsonNode;
import dispatch.cli.CliException;
import dispatch.cli.ProjectAddCommand;
import dispatch.cli.ProjectProbe;
import dispatch.cli.RunCommand;
import dispatch.cli.Service;
import dispatch.config.Config;
import dispatch.config.ConfigEdit;
import dispatch.config.ConfigEdit.At;
import dispatch.config.ConfigException;
import dispatch.config.ConfigFile;
import dispatch.config.ConfigText;
import dispatch.workspace.Git;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFileAttributeView;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.BiFunction;
import java.util.function.Function;

/**
 * The management pages' API (spec: Pages (UI-3a)): the config as the pages show it, and saves of settings, projects and
 * people. A save edits the file in place with {@link ConfigEdit}, so comments and layout stay, keeps the previous text as
 * dispatch.yaml.bak, and replaces the file only with a version that validates. Every read answers the file's version, the
 * SHA-256 of its bytes; a save sends it back and is refused when the file changed on disk since. Secrets never leave here.
 */
public final class ManageApi {

    static final String CHANGED = "the config changed on disk since this page loaded it; reload to see the change";

    public record Phase(String model, String effort) {
    }

    public record ProjectView(String name, String alias, String path, String repo, String baseBranch, String group, String model,
                              String effort, Phase plan, Phase execute) {
    }

    public record MemberView(long id, String name, boolean admin) {
    }

    public record GroupView(String name, Long chatId, List<MemberView> members, List<String> projects) {
    }

    /** @param planTimeout as the config writes it, e.g. "15m"; "1h" for 60 minutes */
    public record Settings(String planTimeout, BigDecimal planBudgetUsd, String executeTimeout, BigDecimal executeBudgetUsd,
                           int maxConcurrentRuns, String authorName, String authorEmail, String claudeCommand, String ghCommand) {
    }

    /** @param personal a personal bot: one member and no admins list */
    public record ConfigView(String version, String configFile, boolean personal, List<Long> admins, OverviewApi.ServiceView service,
                             Settings settings, List<ProjectView> projects, List<GroupView> groups) {
    }

    public record Saved(boolean saved, boolean restartNeeded, String version) {
    }

    private final Path configFile;
    private final Service service;
    private final Map<String, String> processEnvironment;
    private final Git git = new Git("git", null, Duration.ofSeconds(30));

    public ManageApi(Path configFile, Service service, Map<String, String> processEnvironment) {
        this.configFile = configFile.toAbsolutePath();
        this.service = service;
        this.processEnvironment = processEnvironment;
    }

    public Map<String, Function<JsonNode, Object>> routes() {
        return Map.ofEntries(
                Map.entry("/api/manage/config", body -> config()),
                Map.entry("/api/manage/settings", this::settings),
                Map.entry("/api/manage/projects/add", this::addProject),
                Map.entry("/api/manage/projects/edit", this::editProject),
                Map.entry("/api/manage/projects/remove", this::removeProject),
                Map.entry("/api/manage/people/rename", this::renameMember),
                Map.entry("/api/manage/people/remove", this::removeMember),
                Map.entry("/api/manage/people/admin", this::setAdmin));
    }

    ConfigView config() {
        String version = version(read());
        Config config = prepare().config();
        List<Long> admins = config.telegram().admins();
        Config.Limits limits = config.limits();
        Settings settings = new Settings(duration(limits.plan().timeout()), limits.plan().budgetUsd(), duration(limits.execute().timeout()),
                limits.execute().budgetUsd(), config.scheduler().maxConcurrentRuns(), config.delivery().authorName(),
                config.delivery().authorEmail(), config.agents().get("claude-code").command(), config.delivery().ghCommand());
        List<ProjectView> projects = config.projects().stream().map(project -> new ProjectView(project.name(), project.alias(),
                project.path(), project.repo(), project.baseBranch(), groupOf(config, project.name()).name(), project.model(),
                project.effort(), view(project.plan()), view(project.execute()))).toList();
        List<GroupView> groups = config.telegram().groups().stream().map(group -> new GroupView(group.name(), group.chatId(),
                group.members().stream().map(member -> new MemberView(member.id(), member.name(), admins.contains(member.id()))).toList(),
                group.projects())).toList();
        return new ConfigView(version, configFile.toString(), admins.isEmpty(), admins, OverviewApi.serviceView(service), settings,
                projects, groups);
    }

    private Saved settings(JsonNode body) {
        String planTimeout = requiredDuration(body, "planTimeout");
        BigDecimal planBudget = requiredBudget(body, "planBudgetUsd");
        String executeTimeout = requiredDuration(body, "executeTimeout");
        BigDecimal executeBudget = requiredBudget(body, "executeBudgetUsd");
        if (!body.path("maxConcurrentRuns").isIntegralNumber()) {
            throw new CliException("maxConcurrentRuns must be a whole number");
        }
        String runs = String.valueOf(body.path("maxConcurrentRuns").asLong());
        String authorName = SetupApi.text(body, "authorName");
        String authorEmail = SetupApi.text(body, "authorEmail");
        String claude = SetupApi.text(body, "claudeCommand");
        String gh = SetupApi.text(body, "ghCommand");
        return save(body, (text, config) -> {
            Config.Limits limits = config.limits();
            String edited = text;
            edited = change(edited, At.of("limits", "plan", "timeout"), limits.plan().timeout(), Config.RunLimits.parseDuration(planTimeout), planTimeout);
            edited = change(edited, At.of("limits", "plan", "budgetUsd"), limits.plan().budgetUsd(), planBudget, planBudget.toPlainString());
            edited = change(edited, At.of("limits", "execute", "timeout"), limits.execute().timeout(),
                    Config.RunLimits.parseDuration(executeTimeout), executeTimeout);
            edited = change(edited, At.of("limits", "execute", "budgetUsd"), limits.execute().budgetUsd(), executeBudget,
                    executeBudget.toPlainString());
            edited = change(edited, At.of("scheduler", "maxConcurrentRuns"), String.valueOf(config.scheduler().maxConcurrentRuns()), runs, runs);
            edited = change(edited, At.of("delivery", "authorName"), config.delivery().authorName(), authorName, authorName);
            edited = change(edited, At.of("delivery", "authorEmail"), config.delivery().authorEmail(), authorEmail, authorEmail);
            edited = change(edited, At.of("delivery", "ghCommand"), config.delivery().ghCommand(), gh, gh);
            return change(edited, At.of("agents", "claude-code", "command"), config.agents().get("claude-code").command(), claude, claude);
        });
    }

    private Saved addProject(JsonNode body) {
        ProjectProbe probe = probe(SetupApi.text(body, "folder")); // before the lock: it runs git
        String name = SetupApi.text(body, "name");
        String baseBranch = SetupApi.text(body, "baseBranch");
        String alias = SetupApi.optionalText(body, "alias");
        String model = SetupApi.optionalText(body, "model");
        String effort = SetupApi.choice(body, "effort", SetupApi.EFFORTS);
        Config.PhaseSettings plan = phaseSettings(body.path("plan"));
        Config.PhaseSettings execute = phaseSettings(body.path("execute"));
        String requestedGroup = SetupApi.optionalText(body, "group");
        return save(body, (text, config) -> {
            String group = requestedGroup != null ? requestedGroup : onlyGroup(config);
            String agent = config.agents().keySet().iterator().next();
            List<String> lines = ProjectAddCommand.projectLines(new ProjectAddCommand.Project(name, alias, probe.folder(),
                    probe.originUrl(), baseBranch, agent, model, effort, plan, execute));
            return ConfigText.addProject(text, group, ConfigText.yaml(name), lines);
        });
    }

    private Saved editProject(JsonNode body) {
        String name = SetupApi.text(body, "name");
        String baseBranch = SetupApi.text(body, "baseBranch");
        String alias = SetupApi.optionalText(body, "alias");
        String model = SetupApi.optionalText(body, "model");
        String effort = SetupApi.choice(body, "effort", SetupApi.EFFORTS);
        Config.PhaseSettings plan = phaseSettings(body.path("plan"));
        Config.PhaseSettings execute = phaseSettings(body.path("execute"));
        return save(body, (text, config) -> {
            Config.Project project = project(config, name);
            At at = At.of("projects").item("name", name);
            String edited = text;
            edited = change(edited, at.key("baseBranch"), project.baseBranch(), baseBranch, baseBranch);
            edited = change(edited, at.key("alias"), project.alias(), alias, alias);
            edited = change(edited, at.key("model"), project.model(), model, model);
            edited = change(edited, at.key("effort"), project.effort(), effort, effort);
            edited = phase(edited, at.key("plan"), project.plan(), plan);
            return phase(edited, at.key("execute"), project.execute(), execute);
        });
    }

    private Saved removeProject(JsonNode body) {
        String name = SetupApi.text(body, "name");
        return save(body, (text, config) -> {
            project(config, name);
            if (config.projects().size() == 1) {
                throw new CliException(name + " is the only project; add another before removing it");
            }
            Config.Group group = groupOf(config, name);
            if (group.projects().size() == 1) {
                throw new CliException(name + " is the only project of group " + group.name() + ", and a group needs one; "
                        + "add another to that group first");
            }
            String edited = ConfigEdit.remove(text, At.of("telegram", "groups").item("name", group.name()).key("projects").value(name));
            return ConfigEdit.remove(edited, At.of("projects").item("name", name));
        });
    }

    private Saved renameMember(JsonNode body) {
        long id = SetupApi.requiredLong(body, "id");
        String name = SetupApi.text(body, "name");
        return save(body, (text, config) -> {
            String edited = text;
            boolean found = false;
            for (Config.Group group : config.telegram().groups()) {
                if (group.members().stream().anyMatch(member -> member.id() == id)) {
                    edited = ConfigEdit.set(edited, members(group.name()).item("id", String.valueOf(id)).key("name"), name);
                    found = true;
                }
            }
            if (!found) {
                throw new CliException("nobody with id " + id + " is a member");
            }
            return edited;
        });
    }

    private Saved removeMember(JsonNode body) {
        String groupName = SetupApi.text(body, "group");
        long id = SetupApi.requiredLong(body, "id");
        return save(body, (text, config) -> {
            Config.Group group = group(config, groupName);
            Config.Member member = group.members().stream().filter(candidate -> candidate.id() == id).findFirst()
                    .orElseThrow(() -> new CliException("nobody with id " + id + " is a member of group " + groupName));
            if (group.members().size() == 1) {
                throw new CliException(member.name() + " is the only member of group " + groupName + ", and a group needs one");
            }
            List<Long> admins = config.telegram().admins();
            boolean memberElsewhere = config.telegram().groups().stream()
                    .anyMatch(other -> other != group && other.members().stream().anyMatch(candidate -> candidate.id() == id));
            String edited = ConfigEdit.remove(text, members(groupName).item("id", String.valueOf(id)));
            if (admins.contains(id) && !memberElsewhere) {
                if (admins.size() == 1) {
                    throw new CliException(member.name() + " is the team's only admin; make someone else admin first");
                }
                edited = ConfigEdit.remove(edited, At.of("telegram", "admins").value(String.valueOf(id)));
            }
            return edited;
        });
    }

    private Saved setAdmin(JsonNode body) {
        long id = SetupApi.requiredLong(body, "id");
        boolean admin = SetupApi.requiredBoolean(body, "admin");
        return save(body, (text, config) -> {
            List<Long> admins = config.telegram().admins();
            if (admins.isEmpty()) {
                throw new CliException("a personal bot has no admins: only its one member uses it. For admins, set up a team's bot "
                        + "(dispatch init --force, then My team)");
            }
            Config.Member member = config.telegram().groups().stream().flatMap(group -> group.members().stream())
                    .filter(candidate -> candidate.id() == id).findFirst()
                    .orElseThrow(() -> new CliException("nobody with id " + id + " is a member"));
            if (admin == admins.contains(id)) {
                return text;
            }
            if (admin) {
                return ConfigEdit.append(text, At.of("telegram", "admins"), String.valueOf(id));
            }
            if (admins.size() == 1) {
                throw new CliException(member.name() + " is the team's only admin; make someone else admin first");
            }
            return ConfigEdit.remove(text, At.of("telegram", "admins").value(String.valueOf(id)));
        });
    }

    /** One save at a time: the version check, the edit, dispatch.yaml.bak, then the validated replace. */
    private synchronized Saved save(JsonNode body, BiFunction<String, Config, String> edit) {
        String sent = SetupApi.text(body, "version");
        byte[] current = read();
        if (!version(current).equals(sent)) {
            throw new ApiException(409, "changed", CHANGED);
        }
        RunCommand.Prepared prepared = prepare();
        String edited;
        try {
            edited = edit.apply(new String(current, StandardCharsets.UTF_8), prepared.config());
        } catch (ConfigException e) {
            throw new CliException(e.getMessage());
        }
        backup(current);
        try {
            ConfigFile.replace(configFile, edited, prepared.environment());
        } catch (ConfigException | UncheckedIOException e) {
            throw new CliException(e.getMessage());
        }
        return new Saved(true, true, version(read()));
    }

    /** The previous text, kept as dispatch.yaml.bak with the config's own permissions (spec: Saving). */
    private void backup(byte[] previous) {
        Path backup = configFile.resolveSibling(configFile.getFileName() + ".bak");
        try {
            Files.write(backup, previous);
            if (Files.getFileAttributeView(configFile, PosixFileAttributeView.class) != null) {
                Files.setPosixFilePermissions(backup, Files.getPosixFilePermissions(configFile));
            }
        } catch (IOException e) {
            throw new CliException("cannot keep the previous config as " + backup + " (" + e.getMessage() + "); nothing was changed");
        }
    }

    private byte[] read() {
        try {
            return Files.readAllBytes(configFile);
        } catch (NoSuchFileException e) {
            throw new CliException("no config at " + configFile + "; set Dispatch up first");
        } catch (IOException e) {
            throw new CliException("cannot read " + configFile + ": " + e.getMessage());
        }
    }

    private RunCommand.Prepared prepare() {
        try {
            return RunCommand.prepare(configFile, processEnvironment);
        } catch (ConfigException e) {
            throw new CliException(e.getMessage());
        }
    }

    static String version(byte[] bytes) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("every JDK has SHA-256", e);
        }
    }

    /** 15m, 1h or 90s: the largest unit that says it exactly. */
    static String duration(Duration duration) {
        long seconds = duration.toSeconds();
        if (seconds % 3600 == 0) {
            return seconds / 3600 + "h";
        }
        return seconds % 60 == 0 ? seconds / 60 + "m" : seconds + "s";
    }

    /**
     * Sets or removes the value at {@code at} when {@code wanted} differs from {@code current}; an unchanged value is left
     * exactly as written (e.g. "60m" is not rewritten as "1h").
     *
     * @param written what to write for {@code wanted}; null removes the key
     */
    private static String change(String text, At at, Object current, Object wanted, String written) {
        boolean same = current instanceof BigDecimal a && wanted instanceof BigDecimal b ? a.compareTo(b) == 0 : Objects.equals(current, wanted);
        if (same) {
            return text;
        }
        return written == null ? ConfigEdit.remove(text, at) : ConfigEdit.set(text, at, written);
    }

    /** A project's plan: or execute: block: removed when nothing is left in it, otherwise changed field by field. */
    private static String phase(String text, At at, Config.PhaseSettings current, Config.PhaseSettings wanted) {
        if (wanted == null) {
            return current == null ? text : ConfigEdit.remove(text, at);
        }
        String model = current == null ? null : current.model();
        String effort = current == null ? null : current.effort();
        String edited = change(text, at.key("model"), model, wanted.model(), wanted.model());
        return change(edited, at.key("effort"), effort, wanted.effort(), wanted.effort());
    }

    private static Config.PhaseSettings phaseSettings(JsonNode settings) {
        String model = SetupApi.optionalText(settings, "model");
        String effort = SetupApi.choice(settings, "effort", SetupApi.EFFORTS);
        return model == null && effort == null ? null : new Config.PhaseSettings(model, effort);
    }

    private static Phase view(Config.PhaseSettings settings) {
        return settings == null ? null : new Phase(settings.model(), settings.effort());
    }

    private static String requiredDuration(JsonNode body, String field) {
        String value = SetupApi.duration(body, field);
        if (value == null) {
            throw new CliException(field + " is needed");
        }
        return value;
    }

    private static BigDecimal requiredBudget(JsonNode body, String field) {
        BigDecimal value = SetupApi.budget(body, field);
        if (value == null) {
            throw new CliException(field + " is needed");
        }
        return value;
    }

    private ProjectProbe probe(String folder) {
        try {
            return ProjectProbe.of(Path.of(folder), git);
        } catch (InvalidPathException e) {
            throw new CliException(folder + " is not a folder");
        }
    }

    private static At members(String group) {
        return At.of("telegram", "groups").item("name", group).key("members");
    }

    private static Config.Project project(Config config, String name) {
        return config.projects().stream().filter(project -> project.name().equals(name)).findFirst()
                .orElseThrow(() -> new CliException("no project named " + name));
    }

    private static Config.Group group(Config config, String name) {
        return config.telegram().groups().stream().filter(group -> group.name().equals(name)).findFirst()
                .orElseThrow(() -> new CliException("no group named " + name));
    }

    private static Config.Group groupOf(Config config, String project) {
        return config.telegram().groups().stream().filter(group -> group.projects().contains(project)).findFirst()
                .orElseThrow(() -> new CliException(project + " is not listed in any group"));
    }

    private static String onlyGroup(Config config) {
        List<String> names = config.telegram().groups().stream().map(Config.Group::name).toList();
        if (names.size() != 1) {
            throw new CliException("the config has several groups (" + String.join(", ", names) + "); choose one");
        }
        return names.getFirst();
    }
}
