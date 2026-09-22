package dispatch.worker;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import dispatch.Json;
import dispatch.agent.AgentOutcome;
import dispatch.agent.AgentResult;
import dispatch.config.Config;
import dispatch.core.ActiveRuns;
import dispatch.core.Job;
import dispatch.core.JobEvents;
import dispatch.core.JobResult;
import dispatch.domain.Attachment;
import dispatch.domain.RunKind;
import dispatch.store.Database;
import dispatch.store.Tasks;
import dispatch.testing.TestClock;
import java.math.BigDecimal;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

/** The five authenticated routes over real HTTP: a job out, progress and files in, a result back. */
class WorkerProtocolTest extends WorkerApiFixture {

    @Test
    void nextCarriesTheWholeJobIncludingItsFinishedPrompt() throws Exception {
        String key = pair();
        offer(job("Implement the approved plan"));

        JsonNode answer = Json.read(post(WorkerApi.NEXT, key, "{}").body());

        Job job = Json.MAPPER.treeToValue(answer.get("job"), Job.class);
        assertEquals(7, job.taskId());
        assertEquals("Implement the approved plan", job.prompt(), "a finished prompt, not the inputs to build one");
        assertEquals("alm", job.project().name());
        assertEquals(List.of(new Attachment("photo-id", "1-photo.jpg", 3L)), job.attachments());
    }

    @Test
    void nextAnswersNothingWhenTheMemberHasNoWork() throws Exception {
        String key = pair();

        JsonNode answer = Json.read(post(WorkerApi.NEXT, key, "{}").body());

        assertTrue(answer.get("job").isNull(), answer.toString());
    }

    @Test
    void progressRenewsTheLeaseAndCarriesTheCancel() throws Exception {
        String key = pair();
        AtomicReference<String> worktree = new AtomicReference<>();
        offer(job("Implement the approved plan"), new JobEvents() {
            @Override
            public void worktreeCreated(String path, String baseSha) {
                worktree.set(path);
            }

            @Override
            public void agentStarted(Long pid, Instant processStart) {
            }
        });
        post(WorkerApi.NEXT, key, "{}");

        JsonNode answer = Json.read(post(WorkerApi.PROGRESS, key,
                "{\"taskId\":7,\"seq\":2,\"worktree\":\"/home/ann/alm-7\",\"baseSha\":\"6f3030a\",\"agentStarted\":true,"
                        + "\"steps\":9,\"lastAction\":\"Bash: git status\"}").body());

        assertFalse(answer.get("cancel").asBoolean());
        assertEquals("/home/ann/alm-7", worktree.get());
        control.stop(ActiveRuns.StopReason.CANCELLED);
        assertTrue(Json.read(post(WorkerApi.PROGRESS, key, "{\"taskId\":7,\"seq\":2}").body()).get("cancel").asBoolean());
    }

    @Test
    void aResultEndsTheJobAndALateOneGetsConflict() throws Exception {
        String key = pair();
        offer(job("Implement the approved plan"));
        post(WorkerApi.NEXT, key, "{}");
        AgentResult agent = new AgentResult(AgentOutcome.SUCCEEDED, 0, "fake-session", null, "Raised the timeout",
                new BigDecimal("0.07"), 12, List.of(), null, "claude-sonnet-5", null);
        String body = "{\"taskId\":7,\"seq\":2,\"result\":"
                + Json.write(JobResult.delivered(agent, List.of("README.md"), "https://github.com/acme/alm/pull/9")) + "}";

        HttpResponse<String> first = post(WorkerApi.RESULT, key, body);
        HttpResponse<String> late = post(WorkerApi.RESULT, key, body);

        assertEquals(200, first.statusCode());
        assertEquals(JobResult.Outcome.SUCCEEDED, reported.get().outcome());
        assertEquals("https://github.com/acme/alm/pull/9", reported.get().prUrl());
        assertEquals(409, late.statusCode());
        assertEquals("lease_expired", Json.read(late.body()).get("error").asText());
    }

    @Test
    void anAttachmentIsFetchedOnceAndNoCopyIsKeptHere() throws Exception {
        String key = pair();
        offer(job("Implement the approved plan"));
        post(WorkerApi.NEXT, key, "{}");

        HttpResponse<byte[]> answer = http.send(HttpRequest.newBuilder(uri(WorkerApi.ATTACHMENT))
                        .header("Authorization", "Bearer " + key)
                        .POST(HttpRequest.BodyPublishers.ofString("{\"taskId\":7,\"fileRef\":\"photo-id\"}")).build(),
                HttpResponse.BodyHandlers.ofByteArray());

        assertEquals(200, answer.statusCode());
        assertArrayEquals("hi\n".getBytes(StandardCharsets.UTF_8), answer.body());
        assertEquals("application/octet-stream", answer.headers().firstValue("Content-Type").orElseThrow());
        try (var files = Files.walk(stateDir)) {
            assertTrue(files.noneMatch(path -> path.getFileName().toString().contains("photo")),
                    "the team machine keeps no copy of a member's file");
        }
    }

    @Test
    void aFileTheJobNeverMentionedIsNotServed() throws Exception {
        String key = pair();
        offer(job("Implement the approved plan"));
        post(WorkerApi.NEXT, key, "{}");

        HttpResponse<String> answer = post(WorkerApi.ATTACHMENT, key, "{\"taskId\":7,\"fileRef\":\"someone-elses-file\"}");

        assertEquals(404, answer.statusCode());
        assertEquals("not_found", Json.read(answer.body()).get("error").asText());
    }

    @Test
    void progressAndResultForARunThisComputerDoesNotHoldAreRefused() throws Exception {
        String key = pair();
        offer(job("Implement the approved plan"));

        assertEquals(409, post(WorkerApi.PROGRESS, key, "{\"taskId\":7,\"seq\":2}").statusCode(),
                "nothing was leased to it yet");
        assertEquals(409, post(WorkerApi.ATTACHMENT, key, "{\"taskId\":7,\"fileRef\":\"photo-id\"}").statusCode());
    }

    private Job job(String prompt) {
        return new Job(7, 2, RunKind.EXECUTE,
                new Job.Project("alm", "git@github.com:acme/alm.git", "/srv/alm", "main", "claude-code", List.of(".env")),
                "main", "6f3030a", null, null, UUID.fromString("11111111-2222-3333-4444-555555555555"), false, prompt,
                "opus", "high", 1_800_000L, new BigDecimal("2.50"),
                List.of(new Attachment("photo-id", "1-photo.jpg", 3L)), "dispatch #7: Fix the login timeout",
                List.of("Requested-by: Bold"), null);
    }
}
