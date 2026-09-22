package dispatch.core;

import dispatch.domain.Attachment;
import dispatch.domain.RunKind;
import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

/**
 * One run as the machine work needs it: everything the Coordinator read from the store and the config when the run was
 * claimed, so a worker touches neither. Plain JSON types only — W-3 sends this over HTTP (no Duration, Instant or Path).
 *
 * @param project         the project as the team configured it; a remote worker replaces {@code path} with its own clone
 * @param baseBranch      the branch the task was given with, which its delivery targets
 * @param baseSha         where the task's branch started; null before its first planning run made the worktree
 * @param worktree        the task's worktree as recorded; null before its first planning run
 * @param prUrl           the task's pull request, null until something was delivered
 * @param sessionId       the agent session: the planning session for PLAN, the building session for EXECUTE, null for DELIVER
 * @param resume          whether an earlier run of this kind already started that session's agent
 * @param prompt          what the agent is told, without the attachments note the worker adds; null for DELIVER
 * @param model           this phase's model, null for the agent's default
 * @param effort          this phase's effort, null for the agent's default
 * @param timeoutMillis   how long the agent may run; 0 for DELIVER, which runs none
 * @param budgetUsd       this phase's budget; null for DELIVER
 * @param attachments     the task's files the agent may read; empty for DELIVER
 * @param commitSubject   the delivery commit's subject
 * @param commitTrailers  the delivery commit's trailers: who asked and who approved
 * @param deliverySummary DELIVER only: the failed run's summary, which becomes the commit body
 */
public record Job(
        long taskId,
        int seq,
        RunKind kind,
        Project project,
        String baseBranch,
        String baseSha,
        String worktree,
        String prUrl,
        UUID sessionId,
        boolean resume,
        String prompt,
        String model,
        String effort,
        long timeoutMillis,
        BigDecimal budgetUsd,
        List<Attachment> attachments,
        String commitSubject,
        List<String> commitTrailers,
        String deliverySummary) {

    public Job {
        attachments = attachments == null ? List.of() : List.copyOf(attachments);
        commitTrailers = commitTrailers == null ? List.of() : List.copyOf(commitTrailers);
    }

    /** @param path the clone the run works from, null when the worker keeps its own under {@code repos/<name>} */
    public record Project(String name, String repo, String path, String baseBranch, String agent, List<String> copyFiles) {

        public Project {
            copyFiles = copyFiles == null ? List.of() : List.copyOf(copyFiles);
        }
    }
}
