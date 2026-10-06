package dispatch.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;

import dispatch.Json;
import dispatch.agent.AgentOutcome;
import dispatch.agent.AgentResult;
import dispatch.agent.UsageLimit;
import dispatch.agent.SandboxUse;
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
                new Job.Project("alm", "git@github.com:acme/alm.git", "/home/ann/work/alm", "main", "claude-code", List.of(".env"), null, null, null),
                "main", "6f3030a", "/var/lib/dispatch/worktrees/7", "https://github.com/acme/alm/pull/9",
                UUID.fromString("11111111-2222-3333-4444-555555555555"), true, "Implement the approved plan", "opus", "low",
                1_800_000L, new BigDecimal("2.50"), List.of(new Attachment("photo-id", "1-photo.jpg", 3L)),
                "dispatch #7: Fix the login timeout", List.of("Requested-by: Bold", "Approved-by: Ali"), null, null, null, null, null);

        assertEquals(job, Json.MAPPER.readValue(Json.write(job), Job.class));
    }

    @Test
    void aResultWithItsVerificationSurvivesJsonUnchanged() throws Exception {
        JobResult result = JobResult.delivered(null, List.of("A.java"), "https://github.com/acme/alm/pull/9",
                new Verification(Verification.Tests.FAILING, 4, "FooTest", Verification.ReviewState.FINDINGS,
                        List.of(new Review.Finding("minor", "A.java", 3, "name")), null, "budget"));

        assertEquals(result, Json.MAPPER.readValue(Json.write(result), JobResult.class));
    }

    @Test
    void aDeliveryJobSurvivesJsonUnchanged() throws Exception {
        Job job = new Job(7, 3, RunKind.DELIVER,
                new Job.Project("alm", "git@github.com:acme/alm.git", null, "main", "claude-code", List.of(), null, null, null),
                "main", "6f3030a", "/var/lib/dispatch/worktrees/7", null, null, false, null, null, null, 0L, null, List.of(),
                "dispatch #7: Fix the login timeout", List.of("Requested-by: Bold"), "Raised AUTH_TIMEOUT_SECONDS to 30", null, null, null, null);

        assertEquals(job, Json.MAPPER.readValue(Json.write(job), Job.class));
    }

    @Test
    void aJobWithListFieldsAbsentFromJsonDeserializesToEmptyLists() throws Exception {
        // W-3's worker sends a job back with only the fields it actually has; a JSON payload that omits a list must
        // not NPE the receiving side's compact constructor.
        Job job = new Job(7, 2, RunKind.EXECUTE,
                new Job.Project("alm", "git@github.com:acme/alm.git", "/home/ann/work/alm", "main", "claude-code", List.of(".env"), null, null, null),
                "main", "6f3030a", "/var/lib/dispatch/worktrees/7", "https://github.com/acme/alm/pull/9",
                UUID.fromString("11111111-2222-3333-4444-555555555555"), true, "Implement the approved plan", "opus", "low",
                1_800_000L, new BigDecimal("2.50"), List.of(new Attachment("photo-id", "1-photo.jpg", 3L)),
                "dispatch #7: Fix the login timeout", List.of("Requested-by: Bold", "Approved-by: Ali"), null, null, null, null, null);
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
    void aTeamsOwnPrefixTravelsWithTheJob() throws Exception {
        Job job = new Job(7, 1, RunKind.PLAN, new Job.Project("alm", "r", null, "main", "claude-code", List.of(), null, null, null), "main",
                null, null, null, null, false, "p", null, null, 1000, BigDecimal.ONE, List.of(), "s", List.of(), null,
                "dispatch/team/7", null, null, null);

        assertEquals("dispatch/team/7", Json.MAPPER.readValue(Json.write(job), Job.class).branchName());
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

    @Test
    void aResultWithItsSandboxSurvivesJsonUnchanged() throws Exception {
        AgentResult agent = new AgentResult(AgentOutcome.SUCCEEDED, 0, "s1", null, "done", new BigDecimal("0.12"), 3,
                List.of(), null, "claude-sonnet-5", null).withSandbox(new SandboxUse("none", "not available on Windows"));
        JobResult result = JobResult.succeeded(agent);

        assertEquals(result, Json.MAPPER.readValue(Json.write(result), JobResult.class));
    }

    @Test
    void aResultWithItsUsageLimitSurvivesJsonUnchangedAndOneWithoutStillReads() throws Exception {
        AgentResult agent = new AgentResult(AgentOutcome.LIMITED, 1, "s1", null, null, null, 1, List.of(),
                "You've hit your weekly limit", null, null).withLimit(new UsageLimit(1790600400L, "seven_day"));
        JobResult result = JobResult.failed(FailureReason.USAGE_LIMIT, "You've hit your weekly limit", agent);

        assertEquals(result, Json.MAPPER.readValue(Json.write(result), JobResult.class));
        ObjectNode json = (ObjectNode) Json.MAPPER.readTree(Json.write(result));
        ((ObjectNode) json.get("agent")).remove("limit");
        assertNull(Json.MAPPER.treeToValue(json, JobResult.class).agent().limit(), "an older worker sends no limit");
    }

    @Test
    void aResultWithoutSandboxStillReads() throws Exception {
        // A worker from before the sandbox sends no "sandbox" field; the team machine must still read its results.
        ObjectNode json = (ObjectNode) Json.MAPPER.readTree(Json.write(JobResult.succeeded(new AgentResult(AgentOutcome.SUCCEEDED, 0,
                "s1", null, "done", null, null, List.of(), null, null, null))));
        ((ObjectNode) json.get("agent")).remove("sandbox");

        JobResult read = Json.MAPPER.treeToValue(json, JobResult.class);

        assertEquals(null, read.agent().sandbox());
    }

    @Test
    void aJobWithTheLoopSurvivesJsonUnchanged() throws Exception {
        Job job = new Job(7, 2, RunKind.EXECUTE,
                new Job.Project("alm", "git@github.com:acme/alm.git", "/home/ann/work/alm", "main", "claude-code", List.of(),
                        "./mvnw -q test", true, null),
                "main", "6f3030a", "/var/lib/dispatch/worktrees/7", null, UUID.fromString("11111111-2222-3333-4444-555555555555"),
                false, "Implement the approved plan", "opus", "low", 1_800_000L, new BigDecimal("2.50"), List.of(),
                "dispatch #7: Fix the login timeout", List.of("Requested-by: Bold"), null, null, "Review this change", null, null);

        assertEquals(job, Json.MAPPER.readValue(Json.write(job), Job.class));
    }

    @Test
    void aJobsSkillsSurviveJsonAndAJobWithoutThemHasThemOff() throws Exception {
        Job on = new Job(7, 2, RunKind.EXECUTE,
                new Job.Project("alm", "git@github.com:acme/alm.git", null, "main", "claude-code", List.of(), null, null, true),
                "main", "6f3030a", "/w/7", null, UUID.randomUUID(), false, "p", null, null, 1L, null, List.of(), "s", List.of(),
                null, null, null, null, null);
        assertEquals(on, Json.MAPPER.readValue(Json.write(on), Job.class));

        Job older = new Job(7, 2, RunKind.EXECUTE,
                new Job.Project("alm", "git@github.com:acme/alm.git", null, "main", "claude-code", List.of(), null, null, null),
                "main", "6f3030a", "/w/7", null, UUID.randomUUID(), false, "p", null, null, 1L, null, List.of(), "s", List.of(),
                null, null, null, null, null);
        assertFalse(Json.MAPPER.readValue(Json.write(older), Job.class).project().skillsOn());
    }

    @Test
    void aJobWithAnExpectedHeadAndAResultWithAHeadSurviveJsonUnchanged() throws Exception {
        Job job = new Job(7, 3, RunKind.DELIVER,
                new Job.Project("alm", "git@github.com:acme/alm.git", null, "main", "claude-code", List.of(), null, null, null),
                "main", "6f3030a", "/w/7", null, null, false, null, null, null, 0L, null, List.of(), "s", List.of(), "summary",
                null, null, "9c1e2d4", null);
        JobResult result = JobResult.failed(FailureReason.DELIVERY, "git push failed", null).withHead("b7a0f11");

        assertEquals(job, Json.MAPPER.readValue(Json.write(job), Job.class));
        assertEquals(result, Json.MAPPER.readValue(Json.write(result), JobResult.class));
    }

    /** Since ADR 0039 both sides speak one protocol: a job's fields travel as they are, absent ones included. */
    @Test
    void aJobWithPicksSurvivesJsonUnchanged() throws Exception {
        Job.Project project = new Job.Project("alm", "git@github.com:acme/alm.git", null, "main", "claude-code", List.of(), null, null, null);
        Job job = new Job(7, 2, RunKind.EXECUTE, project, "main", "6f3030a", "/w/7", null, UUID.fromString("11111111-2222-3333-4444-555555555555"),
                false, "Implement", null, null, 1000L, new BigDecimal("2"), List.of(), "dispatch #7: x", List.of(), null, null, null, null,
                List.of("playwright"));

        assertEquals(job, Json.MAPPER.readValue(Json.write(job), Job.class));
        assertEquals(List.of("playwright"), job.picks());
    }
}
