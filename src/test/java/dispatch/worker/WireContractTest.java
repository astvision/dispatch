package dispatch.worker;

import static org.junit.jupiter.api.Assertions.assertEquals;

import dispatch.core.Job;
import dispatch.core.JobResult;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.RecordComponent;
import java.lang.reflect.Type;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

/**
 * Pins what a worker and its team machine send each other (ADR 0039). When this fails, the wire changed: raise
 * {@link WorkerProtocol#VERSION}, then put the new description here. A changed wire under the same number is what lets a
 * worker and a team machine on different versions misread each other in silence.
 */
class WireContractTest {

    private static final String WIRE = """
            protocol 4
            worker.Wire$Poll: protocol maxConcurrentRuns readiness
            worker.Wire$Next: protocol job
            worker.RemoteWorkers$Progress: taskId seq worktree baseSha agentStarted steps lastAction loopSteps
            worker.RemoteWorkers$Reply: cancel skipStep deliverNow pauseBeforeReview resume
            worker.Wire$Result: taskId seq result
            worker.Wire$Attachment: taskId fileRef
            worker.Wire$Setup: team authorName authorEmail projects
            worker.Readiness: claude gh projects agents
            core.Job: taskId seq kind project baseBranch baseSha worktree prUrl sessionId resume prompt model effort timeoutMillis budgetUsd attachments commitSubject commitTrailers deliverySummary branch reviewPrompt expectedHead plugins
            worker.RemoteWorkers$Step: n kind round startedAt endedAt outcome detail
            core.JobResult: outcome agent files prUrl failureReason failureDetail verification head
            worker.Wire$Project: name repo baseBranch agent model effort
            worker.Readiness$Check: ok detail
            domain.RunKind = PLAN EXECUTE DELIVER SPLIT ASSISTANT REVIEW
            core.Job$Project: name repo path baseBranch agent copyFiles test loop skills
            domain.Attachment: fileRef name size
            core.JobResult$Outcome = SUCCEEDED FAILED CANCELLED
            agent.AgentResult: outcome exitCode sessionId structuredOutput summary costUsd turns denials error model requestedModel sandbox limit
            domain.FailureReason = SETUP AGENT TIMEOUT BUDGET INTERRUPTED DELIVERY INTERNAL USAGE_LIMIT
            core.Verification: tests testRuns lastRunPassed testTail review findings reviewError stoppedBy
            agent.AgentOutcome = SUCCEEDED FAILED BUDGET_EXCEEDED LIMITED
            agent.SandboxUse: name unsandboxedReason
            agent.UsageLimit: resetsAt type
            core.Verification$Tests = PASSED FAILING UNVERIFIED NO_COMMAND NOT_RUN SKIPPED
            core.Verification$ReviewState = OK FINDINGS FIXED_UNREVIEWED FAILED NOT_RUN SKIPPED
            core.Review$Finding: severity file line text
            agents = claude-code codex gemini""";

    @Test
    void theWireIsWhatThisProtocolVersionSaysItIs() {
        // A job names its project's agent as text: a kind the other side does not know cannot run there either.
        assertEquals(WIRE, "protocol " + WorkerProtocol.VERSION + "\n" + describe(Wire.Poll.class, Wire.Next.class, RemoteWorkers.Progress.class,
                RemoteWorkers.Reply.class, Wire.Result.class, Wire.Attachment.class, Wire.Setup.class)
                + "\nagents = " + String.join(" ", dispatch.domain.AgentKind.ids()));
    }

    /** Every record reachable from {@code roots}, one line each: its name and its fields in order. */
    private static String describe(Class<?>... roots) {
        Set<Class<?>> seen = new LinkedHashSet<>();
        List<Class<?>> queue = new ArrayList<>(List.of(roots));
        List<String> lines = new ArrayList<>();
        while (!queue.isEmpty()) {
            Class<?> type = queue.removeFirst();
            if (type.isEnum() && seen.add(type)) {
                // A constant the other side does not know cannot be read there either.
                List<String> constants = new ArrayList<>();
                for (Object constant : type.getEnumConstants()) {
                    constants.add(constant.toString());
                }
                lines.add(type.getName().replace("dispatch.", "") + " = " + String.join(" ", constants));
                continue;
            }
            if (!type.isRecord() || !seen.add(type)) {
                continue;
            }
            List<String> fields = new ArrayList<>();
            for (RecordComponent component : type.getRecordComponents()) {
                fields.add(component.getName());
                queue.add(component.getType());
                if (component.getGenericType() instanceof ParameterizedType generic) {
                    for (Type argument : generic.getActualTypeArguments()) {
                        if (argument instanceof Class<?> inner) {
                            queue.add(inner);
                        }
                    }
                }
            }
            lines.add(type.getName().replace("dispatch.", "") + ": " + String.join(" ", fields));
        }
        return String.join("\n", lines);
    }
}
