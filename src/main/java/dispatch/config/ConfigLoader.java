package dispatch.config;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonMappingException;
import com.fasterxml.jackson.databind.exc.UnrecognizedPropertyException;
import com.fasterxml.jackson.dataformat.yaml.YAMLMapper;
import java.io.IOException;
import java.math.BigDecimal;
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
    private static final Pattern PROJECT_KEY = Pattern.compile("[A-Za-z0-9._-]+");
    private static final Set<String> SUPPORTED_AGENTS = Set.of("claude-code");
    /** http(s) URLs with any user info (user:token@ or token@); ssh "git@" URLs are fine. */
    private static final Pattern CREDENTIAL_URL = Pattern.compile("^https?://[^/@]*@", Pattern.CASE_INSENSITIVE);
    private static final Pattern SECRET_KEY = Pattern.compile("(?i).*(token|secret|password|passwd|apikey|api_key|credential).*");

    private ConfigLoader() {
    }

    public static Config load(Path file, Map<String, String> env) {
        ConfigFile raw = read(file);
        List<String> errors = new ArrayList<>();

        if (raw.team() == null || !TEAM.matcher(raw.team()).matches()) {
            errors.add("team: required; lowercase letters, digits and '-' (e.g. backend)");
        }
        Path stateDir = stateDir(raw.stateDir() != null ? raw.stateDir() : env.get("STATE_DIRECTORY"), errors);
        validateTelegram(raw.telegram(), errors);
        if (raw.scheduler() == null || raw.scheduler().maxConcurrentRuns() < 1) {
            errors.add("scheduler.maxConcurrentRuns: required, at least 1");
        }
        validateInstanceLimits(raw.limits(), errors);
        Map<String, Config.Agent> agents = raw.agents() == null ? Map.of() : raw.agents();
        validateAgents(agents, errors);
        List<Config.Project> projects = validateProjects(raw.projects(), agents, errors);

        String token = env.get("TELEGRAM_BOT_TOKEN");
        if (isBlank(token)) {
            errors.add("TELEGRAM_BOT_TOKEN: required environment variable");
        }
        String ghToken = isBlank(env.get("GH_TOKEN")) ? null : env.get("GH_TOKEN");

        if (!errors.isEmpty()) {
            throw new ConfigException(file + " is invalid:\n  - " + String.join("\n  - ", errors));
        }
        return new Config(raw.team(), stateDir, raw.telegram(), raw.scheduler(), raw.limits(), Map.copyOf(agents),
                projects, new Config.Secrets(token, ghToken));
    }

    private static ConfigFile read(Path file) {
        try {
            return YAML.readValue(file.toFile(), ConfigFile.class);
        } catch (UnrecognizedPropertyException e) {
            String hint = SECRET_KEY.matcher(e.getPropertyName()).matches()
                    ? " (secrets never go in this file: put them in the environment file, see deploy/example.env)"
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

    private static void validateTelegram(Config.Telegram telegram, List<String> errors) {
        if (telegram == null) {
            errors.add("telegram: required");
            return;
        }
        if (telegram.groupChatId() >= 0) {
            errors.add("telegram.groupChatId: must be the (negative) chat id of the team group, got " + telegram.groupChatId());
        }
        if (telegram.members() == null || telegram.members().isEmpty()) {
            errors.add("telegram.members: at least one member is required");
            return;
        }
        Set<Long> seen = new HashSet<>();
        for (int i = 0; i < telegram.members().size(); i++) {
            Config.Member member = telegram.members().get(i);
            String at = "telegram.members[" + i + "]";
            if (member.id() <= 0) {
                errors.add(at + ".id: must be a positive Telegram user id, got " + member.id());
            } else if (!seen.add(member.id())) {
                errors.add(at + ".id: " + member.id() + " is listed more than once");
            }
            if (isBlank(member.name())) {
                errors.add(at + ".name: required");
            }
        }
    }

    private static void validateInstanceLimits(Config.Limits limits, List<String> errors) {
        Config.RunLimits plan = limits == null ? null : limits.plan();
        if (plan == null || plan.timeout() == null || plan.budgetUsd() == null) {
            errors.add("limits.plan: timeout and budgetUsd are required");
            return;
        }
        validateLimitValues("limits.plan", plan, errors);
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
            validateKey(at + ".alias", project.alias(), false, keys, errors);
            if (isBlank(project.repo())) {
                errors.add(at + ".repo: required");
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
            List<String> copyFiles = project.copyFiles() == null ? List.of() : project.copyFiles();
            for (int f = 0; f < copyFiles.size(); f++) {
                if (!isInsideRepository(copyFiles.get(f))) {
                    errors.add(at + ".copyFiles[" + f + "]: must be a relative path inside the repository, got '" + copyFiles.get(f) + "'");
                }
            }
            if (project.limits() != null && project.limits().plan() != null) {
                validateLimitValues(at + ".limits.plan", project.limits().plan(), errors);
            }
            normalized.add(new Config.Project(project.name(), project.alias(), project.repo(), project.baseBranch(),
                    project.agent(), project.model(), List.copyOf(copyFiles), project.limits()));
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
            Config.Scheduler scheduler,
            Config.Limits limits,
            Map<String, Config.Agent> agents,
            List<Config.Project> projects) {
    }
}
