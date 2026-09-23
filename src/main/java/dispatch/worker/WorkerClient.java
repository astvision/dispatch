package dispatch.worker;

import com.fasterxml.jackson.databind.JsonNode;
import dispatch.Json;
import dispatch.core.Job;
import dispatch.core.JobResult;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.Semaphore;

/**
 * This computer's side of the protocol: one HTTP call per route, with the worker key on every one but pairing.
 *
 * <p>Not {@code final}: {@code WorkerLoopTest} subclasses it to make one call fail on demand, which is the only seam
 * that can pin {@code WorkerLoop.report}'s retry behaviour without a live team machine that can be told to misbehave.
 */
public class WorkerClient {

    /** The long poll answers within 25 s; the read timeout only has to be longer than that. */
    private static final Duration POLL_TIMEOUT = Duration.ofSeconds(40);
    private static final Duration CALL_TIMEOUT = Duration.ofSeconds(30);

    private final HttpClient http;
    private final URI team;
    private final String key;
    /**
     * The server counts every authenticated request against {@link WorkerApi#MAX_IN_FLIGHT_PER_WORKER}, including the
     * long {@code next()} poll — a worker running several jobs at once can otherwise have that poll, each job's 10 s
     * progress tick, an attachment fetch and a result post all outstanding together, well past the limit. This bounds
     * this instance's own outgoing requests to the same number, so a call past it simply waits its turn locally
     * instead of ever being refused with 429 — reachable anyway in a narrow race (this permit releases once the
     * response body is read; the server decrements its own count only after its handler returns), which every caller
     * already treats as transient.
     *
     * <p>Fair: {@link #attachment} holds its permit for a whole download, so with several concurrent runs an unfair
     * semaphore could let a burst of attachment fetches repeatedly cut ahead of a run's own 10 s progress tick toward
     * its 60 s lease, expiring a healthy run's lease as a side effect of someone else's file.
     */
    private final Semaphore inFlight = new Semaphore(WorkerApi.MAX_IN_FLIGHT_PER_WORKER, true);

    public WorkerClient(HttpClient http, URI team, String key) {
        this.http = http;
        this.team = team;
        this.key = key;
    }

    /** The team refused this key: it was revoked, or the team machine was set up again. */
    public static final class RevokedException extends RuntimeException {

        RevokedException(String message) {
            super(message);
        }
    }

    /** The run is no longer this computer's: its worktree stays, the member can retry it. */
    public static final class LeaseExpiredException extends RuntimeException {

        LeaseExpiredException(String message) {
            super(message);
        }
    }

    /**
     * A status no retry can fix: the request itself was wrong (400) or this computer no longer holds this run at all
     * (403 {@code not_your_run}) — unlike a 409, where the lease merely expired, or a 5xx or a network failure, which
     * might clear up on its own.
     */
    public static final class RejectedException extends RuntimeException {

        private final int status;

        RejectedException(int status, String message) {
            super(message);
            this.status = status;
        }

        public int status() {
            return status;
        }
    }

    public record Paired(long workerId, String key, String team) {
    }

    /** @param name what the member's /worker list will call this computer */
    public static Paired pair(HttpClient http, URI team, String code, String name) {
        JsonNode answer = send(http, HttpRequest.newBuilder(team.resolve(WorkerApi.PAIR)).timeout(CALL_TIMEOUT)
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(Json.write(Map.of("code", code, "name", name)))));
        return new Paired(answer.get("workerId").asLong(), answer.get("key").asText(), answer.get("team").asText());
    }

    /** What this computer needs before it can run anything. */
    public record Setup(String team, String authorName, String authorEmail, List<ProjectInfo> projects) {
    }

    public record ProjectInfo(String name, String repo, String baseBranch, String model, String effort) {
    }

    public Setup setup() {
        JsonNode answer = call(WorkerApi.PROJECTS, "{}", CALL_TIMEOUT);
        List<ProjectInfo> projects = new ArrayList<>();
        answer.get("projects").forEach(project -> projects.add(new ProjectInfo(project.path("name").asText(),
                project.path("repo").asText(null), project.path("baseBranch").asText(null),
                project.path("model").asText(null), project.path("effort").asText(null))));
        return new Setup(answer.get("team").asText(), answer.get("authorName").asText(),
                answer.get("authorEmail").asText(), projects);
    }

    /** Waits up to 25 s for work; empty when the member has none. */
    public Optional<Job> next() {
        JsonNode answer = call(WorkerApi.NEXT, "{}", POLL_TIMEOUT);
        if (answer.get("job").isNull()) {
            return Optional.empty();
        }
        try {
            return Optional.of(Json.MAPPER.treeToValue(answer.get("job"), Job.class));
        } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
            throw new IllegalStateException("the team machine sent a job this version cannot read: " + e.getOriginalMessage(), e);
        }
    }

    /** @return true when the member cancelled the task and the agent must stop */
    public boolean progress(RemoteWorkers.Progress progress) {
        return call(WorkerApi.PROGRESS, Json.write(progress), CALL_TIMEOUT).get("cancel").asBoolean();
    }

    public void result(long taskId, int seq, JobResult result) {
        var body = Json.object().put("taskId", taskId).put("seq", seq);
        body.set("result", Json.MAPPER.valueToTree(result));
        call(WorkerApi.RESULT, Json.write(body), CALL_TIMEOUT);
    }

    public void attachment(long taskId, String fileRef, Path target) {
        HttpRequest request = authorized(HttpRequest.newBuilder(team.resolve(WorkerApi.ATTACHMENT)).timeout(CALL_TIMEOUT)
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(
                        Json.write(Map.of("taskId", taskId, "fileRef", fileRef))))).build();
        acquireSlot();
        try {
            HttpResponse<Path> answer = http.send(request, HttpResponse.BodyHandlers.ofFile(target,
                    StandardOpenOption.CREATE, StandardOpenOption.WRITE, StandardOpenOption.TRUNCATE_EXISTING));
            if (answer.statusCode() != 200) {
                // ofFile wrote the error body (JSON, not the file's bytes) to target: read it for the message, as
                // call() reads the same shape from its own String body, then remove it — an attachment call must
                // never leave an error response sitting where the caller expects the file's actual bytes.
                throw failureFor(answer.statusCode(), errorMessage(readAndDelete(target)));
            }
        } catch (IOException e) {
            throw new IllegalStateException(e.getMessage(), e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("interrupted while fetching a file", e);
        } finally {
            inFlight.release();
        }
    }

    private static String readAndDelete(Path target) {
        try {
            return Files.readString(target);
        } catch (IOException e) {
            return "";
        } finally {
            // Whatever happened above, these bytes are an error response sitting where the caller expects a file.
            try {
                Files.deleteIfExists(target);
            } catch (IOException ignored) {
                // best effort: the attachment already failed, and this cleanup must not replace its message
            }
        }
    }

    private JsonNode call(String path, String body, Duration timeout) {
        HttpRequest.Builder request = authorized(HttpRequest.newBuilder(team.resolve(path)).timeout(timeout)
                .header("Content-Type", "application/json").POST(HttpRequest.BodyPublishers.ofString(body)));
        acquireSlot();
        try {
            return send(http, request);
        } finally {
            inFlight.release();
        }
    }

    /** Blocks until one of this instance's {@link WorkerApi#MAX_IN_FLIGHT_PER_WORKER} slots is free. */
    private void acquireSlot() {
        try {
            inFlight.acquire();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("interrupted while waiting for a free connection to the team machine", e);
        }
    }

    private HttpRequest.Builder authorized(HttpRequest.Builder request) {
        return request.header("Authorization", "Bearer " + key);
    }

    private static JsonNode send(HttpClient http, HttpRequest.Builder request) {
        HttpResponse<String> answer;
        try {
            answer = http.send(request.build(), HttpResponse.BodyHandlers.ofString());
        } catch (IOException e) {
            throw new IllegalStateException("cannot reach the team machine: " + e.getMessage(), e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("interrupted while talking to the team machine", e);
        }
        if (answer.statusCode() == 200) {
            return Json.read(answer.body());
        }
        // The status decides what kind of failure this is; the body is read only for its message and, unlike the
        // JSON this class writes itself, is untrusted — a public https team URL behind a reverse proxy can answer a
        // 401 or 409 with that proxy's own HTML error page instead of Dispatch's JSON, and that must not stop the
        // status code from being recognised as what it is.
        throw failureFor(answer.statusCode(), errorMessage(answer.body()));
    }

    /**
     * Maps a non-200 status to the same exception every route answers with, so {@link #attachment}'s own error path
     * (its body comes from a downloaded file, not a String) tells revoked, lease-expired and rejected apart exactly as
     * {@link #call} does, rather than folding all of them into one generic failure.
     */
    private static RuntimeException failureFor(int status, String message) {
        return switch (status) {
            case 401 -> new RevokedException(message);
            case 409 -> new LeaseExpiredException(message);
            // 400 invalid: the request itself was malformed, never fixed by sending the identical bytes again. 403
            // not_your_run: a different computer holds this run's lease now; also never fixed by asking again.
            case 400, 403 -> new RejectedException(status, message);
            default -> new IllegalStateException("the team machine answered " + status + ": " + message);
        };
    }

    private static String errorMessage(String body) {
        try {
            return Json.read(body).path("message").asText(body);
        } catch (RuntimeException e) {
            return body;
        }
    }
}
