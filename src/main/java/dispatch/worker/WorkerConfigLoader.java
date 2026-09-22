package dispatch.worker;

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
import java.util.regex.Pattern;

/** Reads worker.yaml and fails with every problem listed, as the team machine's loader does. */
public final class WorkerConfigLoader {

    private static final YAMLMapper YAML = YAMLMapper.builder()
            .enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
            .build();
    private static final Pattern NAME = Pattern.compile("[A-Za-z0-9._-]{1,40}");
    /**
     * Each active run keeps roughly one request in flight for its progress tick; the next()-poll, an attachment fetch
     * and a result post each add one more, briefly. Staying at most this many below {@link WorkerApi#MAX_IN_FLIGHT_PER_WORKER}
     * leaves that headroom, so a worker run at its configured limit is never the one that trips the server's 429.
     */
    static final int MAX_RECOMMENDED_CONCURRENT_RUNS = WorkerApi.MAX_IN_FLIGHT_PER_WORKER - 1;

    private WorkerConfigLoader() {
    }

    /** The YAML file's shape; the key is never in it — it lives in worker.env. */
    record WorkerFile(String team, String name, Integer maxConcurrentRuns, String claudeCommand, String ghCommand,
                      String stateDir, Map<String, Project> projects) {

        record Project(String path, String model, String effort) {
        }
    }

    public static WorkerConfig load(Path file) {
        WorkerFile raw = read(file);
        List<String> errors = new ArrayList<>();
        if (raw.team() == null || !ConfigLoader.isWorkerUrl(raw.team())) {
            errors.add("team: must start with https:// (plain http only for 127.0.0.1), got '" + raw.team() + "'");
        }
        if (raw.name() == null || !NAME.matcher(raw.name()).matches()) {
            errors.add("name: required; letters, digits, '.', '_' and '-', at most 40 characters");
        }
        int concurrent = raw.maxConcurrentRuns() == null ? 1 : raw.maxConcurrentRuns();
        if (concurrent < 1) {
            errors.add("maxConcurrentRuns: at least 1");
        } else if (concurrent > MAX_RECOMMENDED_CONCURRENT_RUNS) {
            errors.add("maxConcurrentRuns: at most " + MAX_RECOMMENDED_CONCURRENT_RUNS
                    + "; each run's progress, attachment and result calls share the team machine's "
                    + WorkerApi.MAX_IN_FLIGHT_PER_WORKER + "-requests-in-flight limit per worker");
        }
        Path stateDir = raw.stateDir() == null ? Locations.current().stateDir() : Path.of(raw.stateDir());
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
        if (!errors.isEmpty()) {
            throw new ConfigException(file + " is invalid:\n  - " + String.join("\n  - ", errors));
        }
        return new WorkerConfig(raw.team(), raw.name(), concurrent,
                raw.claudeCommand() == null ? "claude" : raw.claudeCommand(),
                raw.ghCommand() == null ? "gh" : raw.ghCommand(), stateDir, projects);
    }

    private static WorkerFile read(Path file) {
        try {
            return YAML.readValue(file.toFile(), WorkerFile.class);
        } catch (JsonProcessingException e) {
            throw new ConfigException(file + ": " + e.getOriginalMessage());
        } catch (IOException e) {
            throw new ConfigException("cannot read " + file + ": " + e.getMessage()
                    + " (run dispatch worker pair first)");
        }
    }
}
