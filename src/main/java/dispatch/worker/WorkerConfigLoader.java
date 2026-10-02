package dispatch.worker;

import dispatch.agent.claude.OwnerPlugins;
import dispatch.agent.sandbox.SandboxSetting;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.dataformat.yaml.YAMLMapper;
import dispatch.cli.Locations;
import dispatch.config.ConfigException;
import dispatch.config.ConfigLoader;
import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Pattern;

/** Reads worker.yaml and fails with every problem listed, as the team machine's loader does. */
public final class WorkerConfigLoader {

    private static final YAMLMapper YAML = YAMLMapper.builder()
            .enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
            .build();
    private static final Pattern NAME = Pattern.compile("[A-Za-z0-9._-]{1,40}");

    private WorkerConfigLoader() {
    }

    /** The same name rule this loader enforces, so a wizard can reject a bad name before it is ever written to disk. */
    static boolean isValidName(String name) {
        return name != null && NAME.matcher(name).matches();
    }

    /** The YAML file's shape; the key is never in it — it lives in worker.env. */
    record WorkerFile(String team, String name, Integer maxConcurrentRuns, String claudeCommand, String ghCommand,
                      String stateDir, Map<String, Project> projects, String codexCommand, String geminiCommand, String sandbox,
                      List<String> claudePlugins, List<String> claudeMcpServers, List<String> claudeSkills) {

        record Project(String path, String model, String effort) {
        }
    }

    public static WorkerConfig load(Path file) {
        WorkerFile raw = read(file);
        List<String> errors = new ArrayList<>();
        if (raw.team() == null || !ConfigLoader.isWorkerUrl(raw.team())) {
            errors.add("team: must start with https:// (plain http only for 127.0.0.1), got '" + raw.team() + "'");
        }
        if (!isValidName(raw.name())) {
            errors.add("name: required; letters, digits, '.', '_' and '-', at most 40 characters");
        }
        // No upper bound here: WorkerClient bounds every request this computer sends at once to
        // WorkerApi.MAX_IN_FLIGHT_PER_WORKER structurally (a Semaphore around call() and attachment()), whatever this is
        // set to. This number trades local resource use (parallel agent processes) against throughput, and it is also how
        // many of the team's scheduler.maxConcurrentRuns slots this computer may hold for its member at once.
        int concurrent = raw.maxConcurrentRuns() == null ? 1 : raw.maxConcurrentRuns();
        if (concurrent < 1) {
            errors.add("maxConcurrentRuns: at least 1");
        }
        // Never the same directory a personal `dispatch run` on this machine would use: same worktrees/runs/attachments
        // layout, keyed by task ids from a different database, and a second `dispatch worker run` would kill the
        // first's agents as "orphans" at startup.
        Path stateDir = raw.stateDir() == null ? Locations.current().stateDir().resolve("worker") : Path.of(raw.stateDir());
        if (!stateDir.isAbsolute()) {
            errors.add("stateDir: must be an absolute path, got '" + raw.stateDir() + "'");
        }
        Map<String, WorkerConfig.Project> projects = new LinkedHashMap<>();
        (raw.projects() == null ? Map.<String, WorkerFile.Project>of() : raw.projects()).forEach((name, project) -> {
            if (project == null || project.path() == null || !Path.of(project.path()).isAbsolute()) {
                errors.add("projects." + name + ".path: must be an absolute path to a clone on this computer");
                return;
            }
            projects.put(name, new WorkerConfig.Project(project.path(), project.model(), project.effort()));
        });
        Optional<SandboxSetting> sandbox = SandboxSetting.fromConfig(raw.sandbox());
        if (sandbox.isEmpty()) {
            errors.add("sandbox: auto or off, not " + raw.sandbox());
        }
        boolean blank = java.util.stream.Stream.of(raw.claudePlugins(), raw.claudeMcpServers(), raw.claudeSkills())
                .filter(java.util.Objects::nonNull).flatMap(List::stream).anyMatch(name -> name == null || name.isBlank());
        if (blank) {
            errors.add("claudePlugins, claudeMcpServers and claudeSkills: list names, without blank entries");
        }
        if (raw.claudeSkills() != null) {
            raw.claudeSkills().stream().filter(name -> name != null && !name.isBlank() && !OwnerPlugins.isSkillName(name))
                    .forEach(name -> errors.add("claudeSkills: " + name + " is not a directory name in ~/.claude/skills"));
        }
        if (!errors.isEmpty()) {
            throw new ConfigException(file + " is invalid:\n  - " + String.join("\n  - ", errors));
        }
        return new WorkerConfig(raw.team(), raw.name(), concurrent,
                raw.claudeCommand() == null ? "claude" : raw.claudeCommand(),
                raw.ghCommand() == null ? "gh" : raw.ghCommand(), stateDir, projects, raw.codexCommand(), raw.geminiCommand(),
                sandbox.orElseThrow());
    }

    private static WorkerFile read(Path file) {
        try {
            WorkerFile raw = YAML.readValue(file.toFile(), WorkerFile.class);
            // An empty file, or one that is only "null" or comments, parses to a null document rather than throwing —
            // without this, every raw.xxx() below throws NullPointerException instead of the clear error this loader
            // otherwise guarantees.
            if (raw == null) {
                throw new ConfigException(file + " is empty; it needs at least team and name (run dispatch worker pair)");
            }
            return raw;
        } catch (JsonProcessingException e) {
            throw new ConfigException(file + ": " + e.getOriginalMessage());
        } catch (IOException e) {
            throw new ConfigException("cannot read " + file + ": " + e.getMessage()
                    + " (run dispatch worker pair first)");
        }
    }
}
