package dispatch.worker;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import dispatch.Json;
import dispatch.agent.AgentOutcome;
import dispatch.agent.AgentResult;
import dispatch.core.ActiveRuns;
import dispatch.core.AttachmentSource;
import dispatch.core.Job;
import dispatch.core.JobEvents;
import dispatch.core.JobResult;
import dispatch.domain.Attachment;
import dispatch.domain.RunKind;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.math.BigDecimal;
import java.net.URI;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.time.Duration;
import java.time.Instant;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
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

        // /next set the lease to now+60s (RemoteWorkers.LEASE). Advance 50s (still inside it) and post progress: if
        // that renews the lease, a second post at +100s total — past the original 60s — must still be accepted.
        clock.advance(Duration.ofSeconds(50));
        JsonNode first = Json.read(post(WorkerApi.PROGRESS, key,
                "{\"taskId\":7,\"seq\":2,\"worktree\":\"/home/ann/alm-7\",\"baseSha\":\"6f3030a\",\"agentStarted\":true,"
                        + "\"steps\":9,\"lastAction\":\"Bash: git status\"}").body());
        assertFalse(first.get("cancel").asBoolean());
        assertEquals("/home/ann/alm-7", worktree.get());

        clock.advance(Duration.ofSeconds(50));
        HttpResponse<String> renewed = post(WorkerApi.PROGRESS, key,
                "{\"taskId\":7,\"seq\":2,\"steps\":10,\"lastAction\":\"Bash: git status\"}");
        assertEquals(200, renewed.statusCode(),
                "the first progress post should have renewed the lease past the original 60s");
        assertFalse(Json.read(renewed.body()).get("cancel").asBoolean());

        control.stop(ActiveRuns.StopReason.CANCELLED);
        assertTrue(Json.read(post(WorkerApi.PROGRESS, key, "{\"taskId\":7,\"seq\":2}").body()).get("cancel").asBoolean());
    }

    @Test
    void aResultEndsTheJobAndALateOneGetsConflict() throws Exception {
        String key = pair();
        offer(job("Implement the approved plan"));
        post(WorkerApi.NEXT, key, "{}");
        String body = resultBody(7, 2);

        HttpResponse<String> first = post(WorkerApi.RESULT, key, body);
        HttpResponse<String> late = post(WorkerApi.RESULT, key, body);

        assertEquals(200, first.statusCode());
        assertEquals(JobResult.Outcome.SUCCEEDED, reported.get().outcome());
        assertEquals("https://github.com/acme/alm/pull/9", reported.get().prUrl());
        assertEquals(409, late.statusCode());
        assertEquals("lease_expired", Json.read(late.body()).get("error").asText());
    }

    @Test
    void aResultWithAnUnknownFieldIsRefusedAsInvalid() throws Exception {
        String key = pair();
        offer(job("Implement the approved plan"));
        post(WorkerApi.NEXT, key, "{}");

        HttpResponse<String> answer = post(WorkerApi.RESULT, key,
                "{\"taskId\":7,\"seq\":2,\"result\":{\"outcome\":\"SUCCEEDED\",\"unexpectedField\":true}}");

        assertEquals(400, answer.statusCode());
        assertEquals("invalid", Json.read(answer.body()).get("error").asText());
    }

    @Test
    void aResultWithAWrongFieldTypeIsRefusedAsInvalid() throws Exception {
        String key = pair();
        offer(job("Implement the approved plan"));
        post(WorkerApi.NEXT, key, "{}");

        HttpResponse<String> answer = post(WorkerApi.RESULT, key,
                "{\"taskId\":7,\"seq\":2,\"result\":{\"outcome\":\"SUCCEEDED\",\"files\":\"not-a-list\"}}");

        assertEquals(400, answer.statusCode());
        assertEquals("invalid", Json.read(answer.body()).get("error").asText());
    }

    @Test
    void aNonNumericTaskIdOrSeqIsRefusedAsInvalidNotMisreadAsZero() throws Exception {
        String key = pair();
        offer(job("Implement the approved plan"));
        post(WorkerApi.NEXT, key, "{}");

        HttpResponse<String> badTaskId = post(WorkerApi.PROGRESS, key, "{\"taskId\":\"seven\",\"seq\":2}");
        HttpResponse<String> badSeq = post(WorkerApi.PROGRESS, key, "{\"taskId\":7,\"seq\":\"two\"}");

        assertEquals(400, badTaskId.statusCode());
        assertEquals("invalid", Json.read(badTaskId.body()).get("error").asText());
        assertEquals(400, badSeq.statusCode());
        assertEquals("invalid", Json.read(badSeq.body()).get("error").asText());
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
        var outgoing = stateDir.resolve("outgoing");
        try (var files = Files.list(outgoing)) {
            assertTrue(files.noneMatch(Files::isRegularFile), "the team machine keeps no copy of a member's file");
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
    void aDownloadFailureIsLoggedNotEchoedAndAnswers500() throws Exception {
        String key = pair();
        offer(job("Implement the approved plan"));
        post(WorkerApi.NEXT, key, "{}");
        // A real Telegram-backed AttachmentSource's exception message routinely carries the download URL, which
        // carries the bot token as a query parameter — that must never reach the worker's HTTP response.
        AttachmentSource failing = (fileRef, target) -> {
            throw new RuntimeException("https://api.telegram.org/botSECRET-TOKEN/getFile?file_id=" + fileRef);
        };

        try (WorkerApi failingApi = WorkerApi.start(config(), groups(), keys, remote, failing)) {
            HttpResponse<String> answer = http.send(HttpRequest.newBuilder(
                            URI.create("http://127.0.0.1:" + failingApi.port() + WorkerApi.ATTACHMENT))
                            .header("Authorization", "Bearer " + key)
                            .POST(HttpRequest.BodyPublishers.ofString("{\"taskId\":7,\"fileRef\":\"photo-id\"}")).build(),
                    HttpResponse.BodyHandlers.ofString());

            assertEquals(500, answer.statusCode());
            assertEquals("internal", Json.read(answer.body()).get("error").asText());
            assertFalse(answer.body().contains("SECRET-TOKEN"), answer.body());
        }
    }

    @Test
    void progressAndResultForARunThisComputerDoesNotHoldAreRefused() throws Exception {
        String key = pair();
        offer(job("Implement the approved plan"));

        assertEquals(409, post(WorkerApi.PROGRESS, key, "{\"taskId\":7,\"seq\":2}").statusCode(),
                "nothing was leased to it yet");
        assertEquals(409, post(WorkerApi.ATTACHMENT, key, "{\"taskId\":7,\"fileRef\":\"photo-id\"}").statusCode());
        assertEquals(409, post(WorkerApi.RESULT, key, resultBody(7, 2)).statusCode());
    }

    @Test
    void aRunAlreadyHeldByOneComputerRefusesAnyOtherComputerWithNotYourRun() throws Exception {
        String key = pair();
        String sameMemberOtherComputer = pair(BOLD, "ann-desktop");
        String otherMemberKey = pair(ALI, "bob-laptop");
        offer(job("Implement the approved plan"));
        post(WorkerApi.NEXT, key, "{}");

        for (String foreignKey : List.of(sameMemberOtherComputer, otherMemberKey)) {
            HttpResponse<String> progress = post(WorkerApi.PROGRESS, foreignKey, "{\"taskId\":7,\"seq\":2}");
            HttpResponse<String> result = post(WorkerApi.RESULT, foreignKey, resultBody(7, 2));

            assertEquals(403, progress.statusCode(), foreignKey);
            assertEquals("not_your_run", Json.read(progress.body()).get("error").asText());
            assertEquals(403, result.statusCode(), foreignKey);
            assertEquals("not_your_run", Json.read(result.body()).get("error").asText());
        }
    }

    @Test
    void aWorkerFloodingConcurrentRequestsIsRefusedBeyondTheLimit() throws Exception {
        String key = pair();
        offer(job("Implement the approved plan"));
        post(WorkerApi.NEXT, key, "{}");

        int cap = WorkerApi.MAX_IN_FLIGHT_PER_WORKER;
        int attempts = cap + 2;
        // Exactly `cap` requests can ever be admitted at once; this latch proves that many reached the (blocked)
        // download, at which point every other concurrent request must already have been refused by the limiter —
        // deterministic, no sleep needed.
        CountDownLatch insideDownload = new CountDownLatch(cap);
        CountDownLatch releaseDownloads = new CountDownLatch(1);
        AttachmentSource slow = (fileRef, target) -> {
            insideDownload.countDown();
            try {
                releaseDownloads.await(10, TimeUnit.SECONDS);
                Files.writeString(target, "hi\n");
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
        };

        try (WorkerApi slowApi = WorkerApi.start(config(), groups(), keys, remote, slow)) {
            URI attachmentUri = URI.create("http://127.0.0.1:" + slowApi.port() + WorkerApi.ATTACHMENT);
            int[] statuses = new int[attempts];
            Exception[] failures = new Exception[attempts];
            Thread[] callers = new Thread[attempts];
            for (int i = 0; i < attempts; i++) {
                int index = i;
                callers[i] = Thread.ofVirtual().start(() -> {
                    try {
                        HttpResponse<String> answer = http.send(HttpRequest.newBuilder(attachmentUri)
                                        .header("Authorization", "Bearer " + key)
                                        .POST(HttpRequest.BodyPublishers.ofString("{\"taskId\":7,\"fileRef\":\"photo-id\"}"))
                                        .build(),
                                HttpResponse.BodyHandlers.ofString());
                        statuses[index] = answer.statusCode();
                    } catch (Exception e) {
                        failures[index] = e;
                    }
                });
            }
            assertTrue(insideDownload.await(10, TimeUnit.SECONDS), "expected exactly " + cap + " requests to reach the"
                    + " download; statuses so far: " + Arrays.toString(statuses) + ", failures: " + Arrays.toString(failures));
            releaseDownloads.countDown();
            for (Thread caller : callers) {
                assertTrue(caller.join(Duration.ofSeconds(10)));
            }

            for (Exception failure : failures) {
                assertNull(failure, "no request should have thrown: " + Arrays.toString(failures));
            }
            int ok = 0;
            int refused = 0;
            for (int status : statuses) {
                if (status == 200) {
                    ok++;
                } else if (status == 429) {
                    refused++;
                }
            }
            assertEquals(cap, ok, "statuses: " + Arrays.toString(statuses));
            assertEquals(attempts - cap, refused, "statuses: " + Arrays.toString(statuses));
        }
    }

    private static String resultBody(long taskId, int seq) {
        AgentResult agent = new AgentResult(AgentOutcome.SUCCEEDED, 0, "fake-session", null, "Raised the timeout",
                new BigDecimal("0.07"), 12, List.of(), null, "claude-sonnet-5", null);
        return "{\"taskId\":" + taskId + ",\"seq\":" + seq + ",\"result\":"
                + Json.write(JobResult.delivered(agent, List.of("README.md"), "https://github.com/acme/alm/pull/9")) + "}";
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
