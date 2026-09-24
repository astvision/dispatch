package dispatch.agent;

import dispatch.domain.RunKind;
import java.math.BigDecimal;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Everything an agent needs for one run.
 *
 * @param sessionId    the task's agent conversation; null for a split, which belongs to no task
 * @param resume       false for a task's first run (starts {@code sessionId}), true to continue that session
 * @param readOnlyDirs extra directories the agent may read, e.g. downloaded attachments
 * @param model        null for the agent's default
 * @param effort       the agent's effort level, null for its default
 * @param budgetUsd    the run's spending cap; null for none, as the owner chose for the assistant (A-1)
 * @param logBase      path prefix for the raw output files; the agent adds its own extensions
 * @param environment  variables added to the agent's base environment for this run only
 */
public record RunRequest(
        RunKind kind,
        Path workdir,
        String prompt,
        UUID sessionId,
        boolean resume,
        List<Path> readOnlyDirs,
        BigDecimal budgetUsd,
        String model,
        String effort,
        Path logBase,
        Map<String, String> environment) {

    public RunRequest(RunKind kind, Path workdir, String prompt, UUID sessionId, boolean resume, List<Path> readOnlyDirs,
                      BigDecimal budgetUsd, String model, String effort, Path logBase) {
        this(kind, workdir, prompt, sessionId, resume, readOnlyDirs, budgetUsd, model, effort, logBase, Map.of());
    }
}
