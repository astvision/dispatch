package dispatch.config;

import dispatch.agent.sandbox.SandboxSetting;
import dispatch.Text;
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
import java.util.Optional;
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
    /** The CLIs Dispatch can drive (ADR 0026), in the order error messages name them. */
    private static final List<String> SUPPORTED_AGENTS = List.of("claude-code", "codex", "gemini");
    static final int MAX_GROUP_NAME = 40;
    /** Claude Code's --effort levels, in its own order. */
    private static final List<String> EFFORT_LEVELS = List.of("low", "medium", "high", "xhigh", "max");
    /** Codex's model_reasoning_effort levels that its current models share. */
    private static final List<String> CODEX_EFFORT_LEVELS = List.of("low", "medium", "high", "xhigh");
    /** http(s) URLs with any user info (user:token@ or token@); ssh "git@" URLs are fine. */
    private static final Pattern CREDENTIAL_URL = Pattern.compile("^https?://[^/@]*@", Pattern.CASE_INSENSITIVE);
    private static final Pattern SECRET_KEY = Pattern.compile("(?i).*(token|secret|password|passwd|apikey|api_key|credential).*");
    /**
     * Segments of letters, digits, '.', '_' or '-' separated by single '/', e.g. dispatch/team (M: several instances).
     * Each segment also follows git's own ref rules ({@code git check-ref-format --branch}): no leading '-' (git reads
     * it as an option) and no segment ending in ".lock" (git's own lock file suffix) — either would load here but then
     * fail every PLAN's {@code git worktree add -b} at SETUP, breaking fail-fast at config load.
     */
    private static final String BRANCH_SEGMENT = "(?!-)[A-Za-z0-9_-]+(\\.[A-Za-z0-9_-]+)*(?<!\\.lock)";
    private static final Pattern BRANCH_PREFIX = Pattern.compile(BRANCH_SEGMENT + "(/" + BRANCH_SEGMENT + ")*");
    /** Keys of the single-group config that ADR 0012 replaced with telegram.groups. */
    private static final Set<String> SINGLE_GROUP_KEYS = Set.of("groupChatId", "members");

    private ConfigLoader() {
    }

    public static Config load(Path file, Map<String, String> env) {
        ConfigFile raw = read(file);
        List<Text> errors = new ArrayList<>();

        if (raw.team() == null || !TEAM.matcher(raw.team()).matches()) {
            errors.add(Text.of("config.teamRequired"));
        }
        Path stateDir = stateDir(raw.stateDir() != null ? raw.stateDir() : env.get("STATE_DIRECTORY"), errors);
        if (raw.scheduler() == null || raw.scheduler().maxConcurrentRuns() < 1) {
            errors.add(Text.of("config.schedulerMaxconcurrentruns"));
        }
        Config.Worktrees worktrees = raw.worktrees() == null ? Config.Worktrees.DEFAULT : raw.worktrees();
        if (worktrees.idleDays() < 1) {
            errors.add(Text.of("config.worktreesIdledays"));
        }
        validateInstanceLimits(raw.limits(), errors);
        Map<String, Config.Agent> agents = raw.agents() == null ? Map.of() : raw.agents();
        validateAgents(agents, errors);
        String loop = raw.loop() == null ? "on" : raw.loop();
        if (!loop.equals("on") && !loop.equals("off")) {
            errors.add(Text.of("config.loop", "loop", raw.loop()));
        }
        List<Config.Project> projects = validateProjects(raw.projects(), agents, loop, errors);
        Config.Telegram telegram = validateTelegram(raw.telegram(), projects, errors);
        Config.Delivery delivery = validateDelivery(raw.delivery(), errors);
        Config.Workers workers = validateWorkers(raw.workers(), telegram, errors);
        Config.MiniApp miniApp = validateMiniApp(raw.miniApp(), errors);
        if (raw.branchPrefix() != null && !BRANCH_PREFIX.matcher(raw.branchPrefix()).matches()) {
            errors.add(Text.of("config.branchPrefix", raw.branchPrefix()));
        }

        Optional<SandboxSetting> sandbox = SandboxSetting.fromConfig(raw.sandbox());
        if (sandbox.isEmpty()) {
            errors.add(Text.of("config.sandbox", raw.sandbox()));
        }

        String token = env.get("TELEGRAM_BOT_TOKEN");
        if (isBlank(token)) {
            errors.add(Text.of("config.telegramBot"));
        }
        String ghToken = isBlank(env.get("GH_TOKEN")) ? null : env.get("GH_TOKEN");

        if (!errors.isEmpty()) {
            throw new ConfigException(Text.of("config.invalid", file, Text.joined("\n  - ", errors)));
        }
        return new Config(raw.team(), stateDir, telegram, raw.scheduler(), worktrees, raw.limits(), Map.copyOf(agents),
                projects, delivery, workers, miniApp, raw.branchPrefix(), new Config.Secrets(token, ghToken),
                sandbox.orElseThrow());
    }

    private static ConfigFile read(Path file) {
        try {
            return YAML.readValue(file.toFile(), ConfigFile.class);
        } catch (UnrecognizedPropertyException e) {
            Text hint = SECRET_KEY.matcher(e.getPropertyName()).matches() ? Text.of("config.hintSecrets")
                    : SINGLE_GROUP_KEYS.contains(e.getPropertyName()) ? Text.of("config.hintGroups")
                    : null;
            throw new ConfigException(hint == null ? Text.of("config.at", file, path(e.getPath()), e.getOriginalMessage())
                    : Text.of("config.atWithHint", file, path(e.getPath()), e.getOriginalMessage(), hint));
        } catch (JsonMappingException e) {
            throw new ConfigException(Text.of("config.at", file, path(e.getPath()), e.getOriginalMessage()));
        } catch (JsonProcessingException e) {
            throw new ConfigException(Text.of("config.parse", file, e.getOriginalMessage()));
        } catch (IOException e) {
            throw new ConfigException(Text.of("config.unreadable", file, e.getMessage()));
        }
    }

    private static Path stateDir(String value, List<Text> errors) {
        if (isBlank(value)) {
            errors.add(Text.of("config.statedirRequired"));
            return null;
        }
        Path path = Path.of(value);
        if (!path.isAbsolute()) {
            errors.add(Text.of("config.statedirMust", value));
            return null;
        }
        return path;
    }

    /** True for http(s) URLs with user info, e.g. a token; such URLs never go into a config file. */
    public static boolean hasCredentials(String url) {
        return CREDENTIAL_URL.matcher(url).find();
    }

    private static Config.Telegram validateTelegram(Config.Telegram telegram, List<Config.Project> projects, List<Text> errors) {
        List<Long> admins = validateAdmins(telegram == null || telegram.admins() == null ? List.of() : telegram.admins(), errors);
        List<Config.Group> groups = telegram == null || telegram.groups() == null ? List.of() : telegram.groups();
        if (groups.isEmpty()) {
            errors.add(Text.of("config.telegramGroups"));
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
                errors.add(Text.of("config.nameRequired", at));
            } else if (group.name().length() > MAX_GROUP_NAME) {
                // A join request's button carries the name, and Telegram allows 64 bytes of button data.
                errors.add(Text.of("config.nameAt", at, MAX_GROUP_NAME));
            } else if (group.name().equals("-")) {
                // A join request's deny button carries "-" where an approve button carries the group's name.
                errors.add(Text.of("config.nameDash", at));
            } else if (!names.add(group.name().toLowerCase())) {
                errors.add(Text.of("config.nameIs", at, group.name()));
            }
            if (group.chatId() == null) {
                // A personal bot's group: no chat, so nothing is announced (ADR 0014).
            } else if (group.chatId() >= 0) {
                errors.add(Text.of("config.chatidMust", at, group.chatId()));
            } else if (!chats.add(group.chatId())) {
                errors.add(Text.of("config.chatidIs", at, group.chatId()));
            }
            List<Config.Member> members = group.members() == null ? List.of() : group.members();
            validateMembers(at, members, errors);
            List<String> owned = group.projects() == null ? List.of() : group.projects();
            if (owned.isEmpty()) {
                errors.add(Text.of("config.projectsAt", at));
            }
            // A project may be in several groups, one per chat it is announced in (ADR 0025), but only once in each.
            Set<String> ownedHere = new HashSet<>();
            for (int p = 0; p < owned.size(); p++) {
                String name = owned.get(p);
                if (!projectNames.contains(name)) {
                    errors.add(Text.of("config.projectsIs", at, p, name));
                } else if (!ownedHere.add(name)) {
                    errors.add(Text.of("config.projectsIsListed", at, p, name));
                } else {
                    listings.merge(name, 1, Integer::sum);
                }
            }
            normalized.add(new Config.Group(group.name(), group.chatId(), List.copyOf(members), List.copyOf(owned)));
        }
        for (int i = 0; i < projects.size(); i++) {
            if (!listings.containsKey(projects.get(i).name())) {
                errors.add(Text.of("config.projectsIsNot", i, projects.get(i).name()));
            }
        }
        return new Config.Telegram(admins, List.copyOf(normalized));
    }

    private static List<Long> validateAdmins(List<Long> admins, List<Text> errors) {
        Set<Long> seen = new HashSet<>();
        for (int i = 0; i < admins.size(); i++) {
            Long admin = admins.get(i);
            if (admin == null || admin <= 0) {
                errors.add(Text.of("config.telegramAdmins", i, admin));
            } else if (!seen.add(admin)) {
                errors.add(Text.of("config.telegramAdminsIs", i, admin));
            }
        }
        return admins.stream().filter(admin -> admin != null && admin > 0).distinct().toList();
    }

    private static void validateMembers(String at, List<Config.Member> members, List<Text> errors) {
        if (members.isEmpty()) {
            errors.add(Text.of("config.membersAt", at));
            return;
        }
        Set<Long> seen = new HashSet<>();
        for (int i = 0; i < members.size(); i++) {
            Config.Member member = members.get(i);
            String memberAt = at + ".members[" + i + "]";
            if (member.id() <= 0) {
                errors.add(Text.of("config.idMust", memberAt, member.id()));
            } else if (!seen.add(member.id())) {
                errors.add(Text.of("config.idIs", memberAt, member.id()));
            }
            if (isBlank(member.name())) {
                errors.add(Text.of("config.name2", memberAt));
            }
        }
    }

    private static void validateInstanceLimits(Config.Limits limits, List<Text> errors) {
        validateInstanceLimit("limits.plan", limits == null ? null : limits.plan(), errors);
        validateInstanceLimit("limits.execute", limits == null ? null : limits.execute(), errors);
    }

    private static void validateInstanceLimit(String at, Config.RunLimits limits, List<Text> errors) {
        if (limits == null || limits.timeout() == null || limits.budgetUsd() == null) {
            errors.add(Text.of("config.timeoutAnd", at));
            return;
        }
        validateLimitValues(at, limits, errors);
    }

    private static Config.Delivery validateDelivery(Config.Delivery delivery, List<Text> errors) {
        if (delivery == null || isBlank(delivery.authorName())) {
            errors.add(Text.of("config.deliveryAuthorname"));
        }
        if (delivery == null || isBlank(delivery.authorEmail())) {
            errors.add(Text.of("config.deliveryAuthoremail"));
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
    private static Config.Workers validateWorkers(Config.Workers workers, Config.Telegram telegram, List<Text> errors) {
        if (workers == null) {
            boolean needsWorkers = Config.isTeam(telegram) && telegram.groups().stream().anyMatch(group -> group.chatId() != null);
            if (needsWorkers) {
                errors.add(Text.of("config.workersRequired"));
            }
            return null;
        }
        if (isBlank(workers.publicUrl())) {
            errors.add(Text.of("config.workersPublicurl"));
        } else if (!isWorkerUrl(workers.publicUrl())) {
            errors.add(Text.of("config.workersPublicurlMust", workers.publicUrl()));
        }
        if (workers.port() < 1 || workers.port() > 65535) {
            errors.add(Text.of("config.workersPort", workers.port()));
        }
        return workers;
    }

    /**
     * The Mini App is off unless its block is there, in a team and in personal mode alike (spec: Config): it puts the
     * management pages on the internet, so nobody gets it by upgrading.
     */
    private static Config.MiniApp validateMiniApp(Config.MiniApp miniApp, List<Text> errors) {
        if (miniApp == null) {
            return null;
        }
        if (isBlank(miniApp.publicUrl())) {
            errors.add(Text.of("config.miniappPublicurl"));
        } else if (!isWorkerUrl(miniApp.publicUrl())) {
            errors.add(Text.of("config.miniappPublicurlMust", miniApp.publicUrl()));
        }
        if (miniApp.port() < 1 || miniApp.port() > 65535) {
            errors.add(Text.of("config.miniappPort", miniApp.port()));
        }
        return miniApp;
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

    private static void validateLimitValues(String at, Config.RunLimits limits, List<Text> errors) {
        if (limits.timeout() != null && (limits.timeout().isZero() || limits.timeout().isNegative())) {
            errors.add(Text.of("config.timeoutMust", at));
        }
        if (limits.budgetUsd() != null && limits.budgetUsd().compareTo(BigDecimal.ZERO) <= 0) {
            errors.add(Text.of("config.budgetusdMust", at));
        }
    }

    private static void validateAgents(Map<String, Config.Agent> agents, List<Text> errors) {
        String supported = String.join(", ", SUPPORTED_AGENTS);
        if (agents.isEmpty()) {
            errors.add(Text.of("config.agentsAt", supported));
        }
        agents.forEach((type, agent) -> {
            if (!SUPPORTED_AGENTS.contains(type)) {
                errors.add(Text.of("config.agentsUnsupported", type, supported));
            } else if (agent == null || isBlank(agent.command())) {
                errors.add(Text.of("config.agentsCommand", type));
            }
        });
    }

    /** Each CLI has its own effort levels, or none (Gemini CLI); an unknown agent is reported elsewhere, so Claude's apply. */
    private static void validateEffort(String at, String effort, String agent, List<Text> errors) {
        if (effort == null) {
            return;
        }
        switch (agent == null ? "claude-code" : agent) {
            case "codex" -> {
                if (!CODEX_EFFORT_LEVELS.contains(effort)) {
                    errors.add(Text.of("config.codexTakes", at, effort));
                }
            }
            case "gemini" -> errors.add(Text.of("config.geminiHas", at));
            default -> {
                if (!EFFORT_LEVELS.contains(effort)) {
                    errors.add(Text.of("config.effortLevel", at, String.join(", ", EFFORT_LEVELS), effort));
                }
            }
        }
    }

    private static List<Config.Project> validateProjects(List<Config.Project> projects, Map<String, Config.Agent> agents,
                                                         String instanceLoop, List<Text> errors) {
        if (projects == null || projects.isEmpty()) {
            errors.add(Text.of("config.projectsAtLeast"));
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
                errors.add(Text.of("config.pathMust", at, project.path()));
            }
            if (isBlank(project.repo())) {
                if (project.path() == null) {
                    errors.add(Text.of("config.repoRequired", at));
                }
            } else if (CREDENTIAL_URL.matcher(project.repo()).find()) {
                // The value itself is not echoed: it contains a credential.
                errors.add(Text.of("config.repoMust", at));
            }
            if (isBlank(project.baseBranch())) {
                errors.add(Text.of("config.basebranchRequired", at));
            }
            if (isBlank(project.agent())) {
                errors.add(Text.of("config.agentRequired", at));
            } else if (!agents.containsKey(project.agent())) {
                errors.add(Text.of("config.agentIs", at, project.agent()));
            }
            validateEffort(at + ".effort", project.effort(), project.agent(), errors);
            if (project.plan() != null) {
                validateEffort(at + ".plan.effort", project.plan().effort(), project.agent(), errors);
            }
            if (project.execute() != null) {
                validateEffort(at + ".execute.effort", project.execute().effort(), project.agent(), errors);
            }
            List<String> copyFiles = project.copyFiles() == null ? List.of() : project.copyFiles();
            for (int f = 0; f < copyFiles.size(); f++) {
                if (!isInsideRepository(copyFiles.get(f))) {
                    errors.add(Text.of("config.copyfilesMust", at, f, copyFiles.get(f)));
                }
            }
            if (project.limits() != null && project.limits().plan() != null) {
                validateLimitValues(at + ".limits.plan", project.limits().plan(), errors);
            }
            if (project.limits() != null && project.limits().execute() != null) {
                validateLimitValues(at + ".limits.execute", project.limits().execute(), errors);
            }
            String projectLoop = project.loop() == null ? instanceLoop : project.loop();
            if (!projectLoop.equals("on") && !projectLoop.equals("off")) {
                errors.add(Text.of("config.loop", at + ".loop", project.loop()));
            }
            if (project.test() != null && project.test().isBlank()) {
                errors.add(Text.of("config.testBlank", at + ".test"));
            }
            normalized.add(new Config.Project(project.name(), project.alias(), project.repo(), project.path(), project.baseBranch(),
                    project.agent(), project.model(), project.effort(), List.copyOf(copyFiles), project.limits(), project.plan(),
                    project.execute(), project.test(), projectLoop));
        }
        return List.copyOf(normalized);
    }

    private static void validateKey(String at, String key, boolean required, Map<String, Integer> keys, List<Text> errors) {
        if (key == null) {
            if (required) {
                errors.add(Text.of("config.required2", at));
            }
            return;
        }
        if (!PROJECT_KEY.matcher(key).matches()) {
            errors.add(Text.of("config.lettersDigits", at, key));
            return;
        }
        if (keys.putIfAbsent(key.toLowerCase(), keys.size()) != null) {
            errors.add(Text.of("config.isUsed", at, key));
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
            Config.Workers workers,
            Config.MiniApp miniApp,
            String branchPrefix,
            String sandbox,
            String loop) {
    }
}
