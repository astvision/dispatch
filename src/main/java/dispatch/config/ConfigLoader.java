package dispatch.config;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonMappingException;
import com.fasterxml.jackson.databind.exc.UnrecognizedPropertyException;
import com.fasterxml.jackson.dataformat.yaml.YAMLMapper;
import java.io.IOException;
import java.math.BigDecimal;
import java.net.URI;
import java.net.URISyntaxException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/** Reads the YAML config and environment, and fails with every problem listed rather than the first one. */
public final class ConfigLoader {

    private static final YAMLMapper YAML = YAMLMapper.builder()
            .enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
            .enable(DeserializationFeature.FAIL_ON_NULL_FOR_PRIMITIVES)
            .build();
    private static final Pattern TEAM = Pattern.compile("[a-z0-9][a-z0-9-]*");
    // "." and ".." are excluded: `dispatch worker init` resolves a project name straight into a path segment
    // (stateDir/repos/<name>), where either would resolve to a directory it does not own.
    private static final Pattern PROJECT_KEY = Pattern.compile("(?!\\.{1,2}$)[A-Za-z0-9._-]+");
    private static final Set<String> SUPPORTED_AGENTS = Set.of("claude-code");
    private static final int MAX_GROUP_NAME = 40;
    /** Claude Code's --effort levels, in its own order. */
    private static final List<String> EFFORT_LEVELS = List.of("low", "medium", "high", "xhigh", "max");
    /** http(s) URLs with any user info (user:token@ or token@); ssh "git@" URLs are fine. */
    private static final Pattern CREDENTIAL_URL = Pattern.compile("^https?://[^/@]*@", Pattern.CASE_INSENSITIVE);
    private static final Pattern SECRET_KEY = Pattern.compile("(?i).*(token|secret|password|passwd|apikey|api_key|credential).*");
    /** Keys of the single-group config that ADR 0012 replaced with telegram.groups. */
    private static final Set<String> SINGLE_GROUP_KEYS = Set.of("groupChatId", "members");

    private ConfigLoader() {
    }

    public static Config load(Path file, Map<String, String> env) {
        ConfigFile raw = read(file);
        List<String> errors = new ArrayList<>();

        if (raw.team() == null || !TEAM.matcher(raw.team()).matches()) {
            errors.add("team: required; lowercase letters, digits and '-' (e.g. backend)");
        }
        Path stateDir = stateDir(raw.stateDir() != null ? raw.stateDir() : env.get("STATE_DIRECTORY"), errors);
        if (raw.scheduler() == null || raw.scheduler().maxConcurrentRuns() < 1) {
            errors.add("scheduler.maxConcurrentRuns: required, at least 1");
        }
        Config.Worktrees worktrees = raw.worktrees() == null ? Config.Worktrees.DEFAULT : raw.worktrees();
        if (worktrees.idleDays() < 1) {
            errors.add("worktrees.idleDays: at least 1");
        }
        validateInstanceLimits(raw.limits(), errors);
        Map<String, Config.Agent> agents = raw.agents() == null ? Map.of() : raw.agents();
        validateAgents(agents, errors);
        List<Config.Project> projects = validateProjects(raw.projects(), agents, errors);
        Config.Telegram telegram = validateTelegram(raw.telegram(), projects, errors);
        Config.Delivery delivery = validateDelivery(raw.delivery(), errors);
        Config.Workers workers = validateWorkers(raw.workers(), telegram, errors);

        String token = env.get("TELEGRAM_BOT_TOKEN");
        if (isBlank(token)) {
            errors.add("TELEGRAM_BOT_TOKEN: required environment variable");
        }
        String ghToken = isBlank(env.get("GH_TOKEN")) ? null : env.get("GH_TOKEN");

        if (!errors.isEmpty()) {
            throw new ConfigException(file + " is invalid:\n  - " + String.join("\n  - ", errors));
        }
        return new Config(raw.team(), stateDir, telegram, raw.scheduler(), worktrees, raw.limits(), Map.copyOf(agents),
                projects, delivery, workers, new Config.Secrets(token, ghToken));
    }

    private static ConfigFile read(Path file) {
        try {
            return YAML.readValue(file.toFile(), ConfigFile.class);
        } catch (UnrecognizedPropertyException e) {
            String hint = SECRET_KEY.matcher(e.getPropertyName()).matches()
                    ? " (secrets never go in this file: put them in the environment file, see deploy/example.env)"
                    : SINGLE_GROUP_KEYS.contains(e.getPropertyName())
                    ? " (the group and its members now go under telegram.groups, each with name, chatId, members and projects;"
                            + " see deploy/example.yaml)"
                    : "";
            throw new ConfigException(file + ": " + path(e.getPath()) + ": " + e.getOriginalMessage() + hint);
        } catch (JsonMappingException e) {
            throw new ConfigException(file + ": " + path(e.getPath()) + ": " + e.getOriginalMessage());
        } catch (JsonProcessingException e) {
            throw new ConfigException(file + ": " + e.getOriginalMessage());
        } catch (IOException e) {
            throw new ConfigException("cannot read config file " + file + ": " + e.getMessage());
        }
    }

    private static Path stateDir(String value, List<String> errors) {
        if (isBlank(value)) {
            errors.add("stateDir: required in the file or via STATE_DIRECTORY (set by systemd StateDirectory=)");
            return null;
        }
        Path path = Path.of(value);
        if (!path.isAbsolute()) {
            errors.add("stateDir: must be an absolute path, got '" + value + "'");
            return null;
        }
        return path;
    }

    /** True for http(s) URLs with user info, e.g. a token; such URLs never go into a config file. */
    public static boolean hasCredentials(String url) {
        return CREDENTIAL_URL.matcher(url).find();
    }

    private static Config.Telegram validateTelegram(Config.Telegram telegram, List<Config.Project> projects, List<String> errors) {
        List<Long> admins = validateAdmins(telegram == null || telegram.admins() == null ? List.of() : telegram.admins(), errors);
        List<Config.Group> groups = telegram == null || telegram.groups() == null ? List.of() : telegram.groups();
        if (groups.isEmpty()) {
            errors.add("telegram.groups: at least one group is required");
            return new Config.Telegram(admins, List.of());
        }
        Set<String> names = new HashSet<>();
        Set<Long> chats = new HashSet<>();
        Map<String, Integer> listings = new HashMap<>();
        Set<String> projectNames = new HashSet<>();
        projects.forEach(project -> projectNames.add(project.name()));
        List<Config.Group> normalized = new ArrayList<>();
        for (int i = 0; i < groups.size(); i++) {
            Config.Group group = groups.get(i);
            String at = "telegram.groups[" + i + "]";
            if (isBlank(group.name()) || !PROJECT_KEY.matcher(group.name()).matches()) {
                errors.add(at + ".name: required; letters, digits, '.', '_' and '-' only");
            } else if (group.name().length() > MAX_GROUP_NAME) {
                // A join request's button carries the name, and Telegram allows 64 bytes of button data.
                errors.add(at + ".name: at most " + MAX_GROUP_NAME + " characters");
            } else if (!names.add(group.name().toLowerCase())) {
                errors.add(at + ".name: '" + group.name() + "' is used by more than one group");
            }
            if (group.chatId() == null) {
                // A personal bot's group: no chat, so nothing is announced (ADR 0014).
            } else if (group.chatId() >= 0) {
                errors.add(at + ".chatId: must be the (negative) chat id of the group, got " + group.chatId());
            } else if (!chats.add(group.chatId())) {
                errors.add(at + ".chatId: " + group.chatId() + " is used by more than one group");
            }
            List<Config.Member> members = group.members() == null ? List.of() : group.members();
            validateMembers(at, members, errors);
            List<String> owned = group.projects() == null ? List.of() : group.projects();
            if (owned.isEmpty()) {
                errors.add(at + ".projects: at least one project is required");
            }
            for (int p = 0; p < owned.size(); p++) {
                String name = owned.get(p);
                if (!projectNames.contains(name)) {
                    errors.add(at + ".projects[" + p + "]: '" + name + "' is not a configured project");
                } else if (listings.merge(name, 1, Integer::sum) == 2) {
                    errors.add("telegram.groups: '" + name + "' is listed in more than one group; a project belongs to exactly one");
                }
            }
            normalized.add(new Config.Group(group.name(), group.chatId(), List.copyOf(members), List.copyOf(owned)));
        }
        for (int i = 0; i < projects.size(); i++) {
            if (!listings.containsKey(projects.get(i).name())) {
                errors.add("projects[" + i + "]: '" + projects.get(i).name() + "' is not listed in any group");
            }
        }
        return new Config.Telegram(admins, List.copyOf(normalized));
    }

    private static List<Long> validateAdmins(List<Long> admins, List<String> errors) {
        Set<Long> seen = new HashSet<>();
        for (int i = 0; i < admins.size(); i++) {
            Long admin = admins.get(i);
            if (admin == null || admin <= 0) {
                errors.add("telegram.admins[" + i + "]: must be a positive Telegram user id, got " + admin);
            } else if (!seen.add(admin)) {
                errors.add("telegram.admins[" + i + "]: " + admin + " is listed more than once");
            }
        }
        return admins.stream().filter(admin -> admin != null && admin > 0).distinct().toList();
    }

    private static void validateMembers(String at, List<Config.Member> members, List<String> errors) {
        if (members.isEmpty()) {
            errors.add(at + ".members: at least one member is required");
            return;
        }
        Set<Long> seen = new HashSet<>();
        for (int i = 0; i < members.size(); i++) {
            Config.Member member = members.get(i);
            String memberAt = at + ".members[" + i + "]";
            if (member.id() <= 0) {
                errors.add(memberAt + ".id: must be a positive Telegram user id, got " + member.id());
            } else if (!seen.add(member.id())) {
                errors.add(memberAt + ".id: " + member.id() + " is listed more than once");
            }
            if (isBlank(member.name())) {
                errors.add(memberAt + ".name: required");
            }
        }
    }

    private static void validateInstanceLimits(Config.Limits limits, List<String> errors) {
        validateInstanceLimit("limits.plan", limits == null ? null : limits.plan(), errors);
        validateInstanceLimit("limits.execute", limits == null ? null : limits.execute(), errors);
    }

    private static void validateInstanceLimit(String at, Config.RunLimits limits, List<String> errors) {
        if (limits == null || limits.timeout() == null || limits.budgetUsd() == null) {
            errors.add(at + ": timeout and budgetUsd are required");
            return;
        }
        validateLimitValues(at, limits, errors);
    }

    private static Config.Delivery validateDelivery(Config.Delivery delivery, List<String> errors) {
        if (delivery == null || isBlank(delivery.authorName())) {
            errors.add("delivery.authorName: required, the git author of delivery commits (e.g. Dispatch (backend))");
        }
        if (delivery == null || isBlank(delivery.authorEmail())) {
            errors.add("delivery.authorEmail: required (e.g. dispatch-backend@users.noreply.github.com)");
        }
        if (delivery == null) {
            return null;
        }
        String ghCommand = isBlank(delivery.ghCommand()) ? "gh" : delivery.ghCommand();
        return new Config.Delivery(delivery.authorName(), delivery.authorEmail(), ghCommand);
    }

    /**
     * In a team every member's tasks run on their own computer, so the team machine must be reachable by their workers
     * (spec: Configuration). A personal Dispatch needs none and runs its jobs in this process.
     */
    private static Config.Workers validateWorkers(Config.Workers workers, Config.Telegram telegram, List<String> errors) {
        if (workers == null) {
            if (Config.isTeam(telegram)) {
                errors.add("workers: required once a group has a chat; each member's tasks then run on their own computer "
                        + "(publicUrl and port, see deploy/example.yaml)");
            }
            return null;
        }
        if (isBlank(workers.publicUrl())) {
            errors.add("workers.publicUrl: required, the https URL members' workers reach this machine on");
        } else if (!isWorkerUrl(workers.publicUrl())) {
            errors.add("workers.publicUrl: must start with https:// (plain http only for 127.0.0.1), got '"
                    + workers.publicUrl() + "'");
        }
        if (workers.port() < 1 || workers.port() > 65535) {
            errors.add("workers.port: must be from 1 to 65535, got " + workers.port());
        }
        return workers;
    }

    /** Worker keys travel on every request: https everywhere, except loopback, which tests pair over. */
    public static boolean isWorkerUrl(String url) {
        try {
            URI uri = new URI(url);
            if ("https".equalsIgnoreCase(uri.getScheme())) {
                return uri.getHost() != null;
            }
            return "http".equalsIgnoreCase(uri.getScheme()) && "127.0.0.1".equals(uri.getHost());
        } catch (URISyntaxException e) {
            return false;
        }
    }

    private static void validateLimitValues(String at, Config.RunLimits limits, List<String> errors) {
        if (limits.timeout() != null && (limits.timeout().isZero() || limits.timeout().isNegative())) {
            errors.add(at + ".timeout: must be positive");
        }
        if (limits.budgetUsd() != null && limits.budgetUsd().compareTo(BigDecimal.ZERO) <= 0) {
            errors.add(at + ".budgetUsd: must be positive");
        }
    }

    private static void validateAgents(Map<String, Config.Agent> agents, List<String> errors) {
        if (agents.isEmpty()) {
            errors.add("agents: at least one agent is required (supported: " + SUPPORTED_AGENTS + ")");
        }
        agents.forEach((type, agent) -> {
            if (!SUPPORTED_AGENTS.contains(type)) {
                errors.add("agents." + type + ": unsupported agent type (supported: " + SUPPORTED_AGENTS + ")");
            } else if (agent == null || isBlank(agent.command())) {
                errors.add("agents." + type + ".command: required");
            }
        });
    }

    private static void validateEffort(String at, String effort, List<String> errors) {
        if (effort != null && !EFFORT_LEVELS.contains(effort)) {
            errors.add(at + ": must be one of " + String.join(", ", EFFORT_LEVELS) + ", got '" + effort + "'");
        }
    }

    private static List<Config.Project> validateProjects(List<Config.Project> projects, Map<String, Config.Agent> agents,
                                                         List<String> errors) {
        if (projects == null || projects.isEmpty()) {
            errors.add("projects: at least one project is required");
            return List.of();
        }
        Map<String, Integer> keys = new HashMap<>();
        List<Config.Project> normalized = new ArrayList<>();
        for (int i = 0; i < projects.size(); i++) {
            Config.Project project = projects.get(i);
            String at = "projects[" + i + "]";
            validateKey(at + ".name", project.name(), true, keys, errors);
            if (project.alias() == null || !project.alias().equalsIgnoreCase(project.name())) {
                validateKey(at + ".alias", project.alias(), false, keys, errors);
            }
            if (project.path() != null && !Path.of(project.path()).isAbsolute()) {
                errors.add(at + ".path: must be an absolute path to a git clone, got '" + project.path() + "'");
            }
            if (isBlank(project.repo())) {
                if (project.path() == null) {
                    errors.add(at + ".repo: required unless path is set");
                }
            } else if (CREDENTIAL_URL.matcher(project.repo()).find()) {
                // The value itself is not echoed: it contains a credential.
                errors.add(at + ".repo: must not contain credentials; use the plain URL and set GH_TOKEN in the environment file");
            }
            if (isBlank(project.baseBranch())) {
                errors.add(at + ".baseBranch: required");
            }
            if (isBlank(project.agent())) {
                errors.add(at + ".agent: required");
            } else if (!agents.containsKey(project.agent())) {
                errors.add(at + ".agent: '" + project.agent() + "' is not configured under agents");
            }
            validateEffort(at + ".effort", project.effort(), errors);
            if (project.plan() != null) {
                validateEffort(at + ".plan.effort", project.plan().effort(), errors);
            }
            if (project.execute() != null) {
                validateEffort(at + ".execute.effort", project.execute().effort(), errors);
            }
            List<String> copyFiles = project.copyFiles() == null ? List.of() : project.copyFiles();
            for (int f = 0; f < copyFiles.size(); f++) {
                if (!isInsideRepository(copyFiles.get(f))) {
                    errors.add(at + ".copyFiles[" + f + "]: must be a relative path inside the repository, got '" + copyFiles.get(f) + "'");
                }
            }
            if (project.limits() != null && project.limits().plan() != null) {
                validateLimitValues(at + ".limits.plan", project.limits().plan(), errors);
            }
            if (project.limits() != null && project.limits().execute() != null) {
                validateLimitValues(at + ".limits.execute", project.limits().execute(), errors);
            }
            normalized.add(new Config.Project(project.name(), project.alias(), project.repo(), project.path(), project.baseBranch(),
                    project.agent(), project.model(), project.effort(), List.copyOf(copyFiles), project.limits(), project.plan(),
                    project.execute()));
        }
        return List.copyOf(normalized);
    }

    private static void validateKey(String at, String key, boolean required, Map<String, Integer> keys, List<String> errors) {
        if (key == null) {
            if (required) {
                errors.add(at + ": required");
            }
            return;
        }
        if (!PROJECT_KEY.matcher(key).matches()) {
            errors.add(at + ": letters, digits, '.', '_' and '-' only, got '" + key + "'");
            return;
        }
        if (keys.putIfAbsent(key.toLowerCase(), keys.size()) != null) {
            errors.add(at + ": '" + key + "' is used by more than one project");
        }
    }

    private static boolean isInsideRepository(String file) {
        if (isBlank(file)) {
            return false;
        }
        Path path = Path.of(file).normalize();
        return !path.isAbsolute() && !path.startsWith("..") && !path.toString().isEmpty();
    }

    private static String path(List<JsonMappingException.Reference> references) {
        StringBuilder out = new StringBuilder();
        for (JsonMappingException.Reference reference : references) {
            if (reference.getFieldName() != null) {
                if (!out.isEmpty()) {
                    out.append('.');
                }
                out.append(reference.getFieldName());
            } else if (reference.getIndex() >= 0) {
                out.append('[').append(reference.getIndex()).append(']');
            }
        }
        return out.isEmpty() ? "(root)" : out.toString();
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }

    /** The YAML file's shape; secrets never come from the file. */
    record ConfigFile(
            String team,
            String stateDir,
            Config.Telegram telegram,
            Config.Delivery delivery,
            Config.Scheduler scheduler,
            Config.Worktrees worktrees,
            Config.Limits limits,
            Map<String, Config.Agent> agents,
            List<Config.Project> projects,
            Config.Workers workers) {
    }
}
