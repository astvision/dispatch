package dispatch.agent;

import dispatch.domain.RunKind;
import java.math.BigDecimal;
import java.nio.file.Path;
import java.util.List;
import java.util.UUID;

/**
 * Everything an agent needs for one run.
 *
 * @param sessionId    the task's agent conversation; null for a split, which belongs to no task
 * @param resume       false for a task's first run (starts {@code sessionId}), true to continue that session
 * @param readOnlyDirs extra directories the agent may read, e.g. downloaded attachments
 * @param model        null for the agent's default
 * @param logBase      path prefix for the raw output files; the agent adds its own extensions
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
        Path logBase) {
}
