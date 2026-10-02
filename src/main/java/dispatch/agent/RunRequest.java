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
 * @param pluginDirs   Claude Code plugins this run loads (spec: agent skills); empty for none, ignored by Codex and Gemini CLI
 * @param picks        the official plugins the task's plan picked (spec: plugin picks); empty for none, ignored by Codex and
 *                     Gemini CLI
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
        Map<String, String> environment,
        List<Path> pluginDirs,
        List<String> picks) {

    public RunRequest {
        pluginDirs = pluginDirs == null ? List.of() : List.copyOf(pluginDirs);
        picks = picks == null ? List.of() : List.copyOf(picks);
    }

    /** This request with {@code more} plugin directories after its own; the sandbox binds them all read-only. */
    public RunRequest withPluginDirs(List<Path> more) {
        List<Path> dirs = new java.util.ArrayList<>(pluginDirs);
        dirs.addAll(more);
        return new RunRequest(kind, workdir, prompt, sessionId, resume, readOnlyDirs, budgetUsd, model, effort, logBase,
                environment, dirs, picks);
    }

    /** Without picks. */
    public RunRequest(RunKind kind, Path workdir, String prompt, UUID sessionId, boolean resume, List<Path> readOnlyDirs,
                      BigDecimal budgetUsd, String model, String effort, Path logBase, Map<String, String> environment,
                      List<Path> pluginDirs) {
        this(kind, workdir, prompt, sessionId, resume, readOnlyDirs, budgetUsd, model, effort, logBase, environment, pluginDirs,
                List.of());
    }

    public RunRequest(RunKind kind, Path workdir, String prompt, UUID sessionId, boolean resume, List<Path> readOnlyDirs,
                      BigDecimal budgetUsd, String model, String effort, Path logBase, Map<String, String> environment) {
        this(kind, workdir, prompt, sessionId, resume, readOnlyDirs, budgetUsd, model, effort, logBase, environment, List.of(),
                List.of());
    }

    public RunRequest(RunKind kind, Path workdir, String prompt, UUID sessionId, boolean resume, List<Path> readOnlyDirs,
                      BigDecimal budgetUsd, String model, String effort, Path logBase) {
        this(kind, workdir, prompt, sessionId, resume, readOnlyDirs, budgetUsd, model, effort, logBase, Map.of(), List.of(),
                List.of());
    }
}
