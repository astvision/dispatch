package dispatch.core;

import static org.junit.jupiter.api.Assertions.assertEquals;

import dispatch.Json;
import dispatch.agent.AgentOutcome;
import dispatch.agent.AgentResult;
import com.fasterxml.jackson.databind.node.ObjectNode;
import dispatch.domain.Attachment;
import dispatch.domain.FailureReason;
import dispatch.domain.RunKind;
import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/** W-3 sends a job to a worker and its result back over HTTP, so both must survive the project's mapper unchanged. */
class JobJsonTest {

    @Test
    void aJobSurvivesJsonUnchanged() throws Exception {
        Job job = new Job(7, 2, RunKind.EXECUTE,
                new Job.Project("alm", "git@github.com:acme/alm.git", "/home/ann/work/alm", "main", "claude-code", List.of(".env")),
                "main", "6f3030a", "/var/lib/dispatch/worktrees/7", "https://github.com/acme/alm/pull/9",
                UUID.fromString("11111111-2222-3333-4444-555555555555"), true, "Implement the approved plan", "opus", "low",
                1_800_000L, new BigDecimal("2.50"), List.of(new Attachment("photo-id", "1-photo.jpg", 3L)),
                "dispatch #7: Fix the login timeout", List.of("Requested-by: Bold", "Approved-by: Ali"), null);

        assertEquals(job, Json.MAPPER.readValue(Json.write(job), Job.class));
    }

    @Test
    void aDeliveryJobSurvivesJsonUnchanged() throws Exception {
        Job job = new Job(7, 3, RunKind.DELIVER,
                new Job.Project("alm", "git@github.com:acme/alm.git", null, "main", "claude-code", List.of()),
                "main", "6f3030a", "/var/lib/dispatch/worktrees/7", null, null, false, null, null, null, 0L, null, List.of(),
                "dispatch #7: Fix the login timeout", List.of("Requested-by: Bold"), "Raised AUTH_TIMEOUT_SECONDS to 30");

        assertEquals(job, Json.MAPPER.readValue(Json.write(job), Job.class));
    }

    @Test
    void aJobWithListFieldsAbsentFromJsonDeserializesToEmptyLists() throws Exception {
        // W-3's worker sends a job back with only the fields it actually has; a JSON payload that omits a list must
        // not NPE the receiving side's compact constructor.
        Job job = new Job(7, 2, RunKind.EXECUTE,
                new Job.Project("alm", "git@github.com:acme/alm.git", "/home/ann/work/alm", "main", "claude-code", List.of(".env")),
                "main", "6f3030a", "/var/lib/dispatch/worktrees/7", "https://github.com/acme/alm/pull/9",
                UUID.fromString("11111111-2222-3333-4444-555555555555"), true, "Implement the approved plan", "opus", "low",
                1_800_000L, new BigDecimal("2.50"), List.of(new Attachment("photo-id", "1-photo.jpg", 3L)),
                "dispatch #7: Fix the login timeout", List.of("Requested-by: Bold", "Approved-by: Ali"), null);
        ObjectNode node = (ObjectNode) Json.MAPPER.valueToTree(job);
        node.remove("attachments");
        node.remove("commitTrailers");

        Job parsed = Json.MAPPER.treeToValue(node, Job.class);

        assertEquals(List.of(), parsed.attachments());
        assertEquals(List.of(), parsed.commitTrailers());
    }

    @Test
    void aJobResultWithFilesAbsentFromJsonDeserializesToAnEmptyList() throws Exception {
        JobResult result = JobResult.delivered(null, List.of("README.md"), "https://github.com/acme/alm/pull/9");
        ObjectNode node = (ObjectNode) Json.MAPPER.valueToTree(result);
        node.remove("files");

        JobResult parsed = Json.MAPPER.treeToValue(node, JobResult.class);

        assertEquals(List.of(), parsed.files());
    }

    @Test
    void everyJobResultSurvivesJsonUnchanged() throws Exception {
        AgentResult agent = new AgentResult(AgentOutcome.SUCCEEDED, 0, "fake-session", "{\"understanding\":\"the login times out\"}",
                "Raised AUTH_TIMEOUT_SECONDS", new BigDecimal("0.073173"), 12, List.of("Bash: git push"), null,
                "claude-sonnet-5", "opus");

        for (JobResult result : List.of(JobResult.succeeded(agent),
                JobResult.delivered(agent, List.of("README.md"), "https://github.com/acme/alm/pull/9"),
                JobResult.failed(FailureReason.DELIVERY, "git push failed: repository not found", agent),
                JobResult.cancelled(null))) {
            assertEquals(result, Json.MAPPER.readValue(Json.write(result), JobResult.class), result.toString());
        }
    }
}
