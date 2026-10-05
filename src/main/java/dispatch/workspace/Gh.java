package dispatch.workspace;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import dispatch.Json;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** The GitHub CLI, run like {@link Git}: it never prompts and sees the token only in its environment. */
public final class Gh {

    /** A pull request as its watcher needs it: OPEN, MERGED or CLOSED, and the commit its branch is at. */
    public record PullRequest(String state, String headSha) {
    }

    /** One check on a pull request's head commit. {@code bucket} is gh's own word: pass, fail, pending, skipping or cancel. */
    public record Check(String name, String bucket, String link) {
    }

    /** A check's link when GitHub Actions ran it: its repository and its run. */
    private static final Pattern ACTIONS_RUN = Pattern.compile("^https://github\\.com/([^/]+/[^/]+)/actions/runs/(\\d+)");
    /** How much of a failed log is worth an agent's reading: failures are said at the end. */
    private static final int LOG_TAIL_LINES = 200;
    /** How much of an answer that is not JSON goes into the error. */
    private static final int UNREADABLE_SHOWN = 200;

    private final String command;
    private final String ghToken;
    private final Duration timeout;

    /** @param ghToken null to rely on the OS user's own gh login */
    public Gh(String command, String ghToken, Duration timeout) {
        this.command = command;
        this.ghToken = ghToken;
        this.timeout = timeout;
    }

    /** Opens a draft pull request from {@code head} into {@code base} for the worktree's repository; returns its URL. */
    public String createDraftPullRequest(Path worktree, String base, String head, String title, String body) {
        List<String> commandLine = List.of(command, "pr", "create", "--draft", "--base", base, "--head", head,
                "--title", title, "--body", body);
        Git.Result result = Git.runProcess(commandLine, worktree, ghToken, timeout, "gh pr create");
        if (result.exitCode() != 0) {
            throw failed("gh pr create", result);
        }
        // gh prints the new pull request's URL as its last line.
        List<String> lines = result.stdout().strip().lines().toList();
        String url = lines.isEmpty() ? "" : lines.getLast().strip();
        if (!url.startsWith("https://")) {
            throw new WorkspaceException("gh pr create printed no pull request URL: " + result.stdout().strip());
        }
        return url;
    }

    /** The pull request's state on GitHub: OPEN, MERGED or CLOSED. Run in {@code dir}, outside any clone: the URL names its repository. */
    public String pullRequestState(Path dir, String url) {
        Git.Result result = Git.runProcess(List.of(command, "pr", "view", url, "--json", "state", "--jq", ".state"), dir, ghToken,
                timeout, "gh pr view");
        if (result.exitCode() != 0) {
            throw failed("gh pr view", result);
        }
        return result.stdout().strip();
    }

    /**
     * Marks the pull request ready for review, since a draft cannot be merged, then squash-merges it and deletes its branch
     * on GitHub. Run in {@code dir}, outside any clone, so gh never switches or deletes a checked-out branch. Throws with
     * GitHub's own words when it refuses, e.g. while a required check is still running.
     */
    public void squashMerge(Path dir, String url) {
        // Fails harmlessly on a pull request that is ready already; the merge's own answer is the one that matters.
        Git.runProcess(List.of(command, "pr", "ready", url), dir, ghToken, timeout, "gh pr ready");
        Git.Result merged = Git.runProcess(List.of(command, "pr", "merge", url, "--squash", "--delete-branch"), dir, ghToken, timeout,
                "gh pr merge");
        if (merged.exitCode() != 0) {
            throw failed("gh pr merge", merged);
        }
    }

    /** The pull request's state and head commit. Run in {@code dir}, outside any clone: the URL names its repository. */
    public PullRequest pullRequest(Path dir, String url) {
        Git.Result result = Git.runProcess(List.of(command, "pr", "view", url, "--json", "state,headRefOid"), dir, ghToken, timeout,
                "gh pr view");
        if (result.exitCode() != 0) {
            throw failed("gh pr view", result);
        }
        JsonNode json = json("gh pr view", result.stdout());
        return new PullRequest(json.path("state").asText(), json.path("headRefOid").asText());
    }

    /** Every check on the pull request's head commit; none for a repository that runs none. Run in {@code dir}, outside any clone. */
    public List<Check> checks(Path dir, String url) {
        Git.Result result = Git.runProcess(List.of(command, "pr", "checks", url, "--json", "name,bucket,link"), dir, ghToken, timeout,
                "gh pr checks");
        // gh's exit code is not the verdict: 8 says pending, 1 a failed check or none at all. What it printed is.
        if (result.stdout().isBlank()) {
            if (result.stderr().contains("no checks reported")) {
                return List.of();
            }
            throw failed("gh pr checks", result);
        }
        JsonNode json = json("gh pr checks", result.stdout());
        if (!json.isArray()) {
            throw new WorkspaceException("gh pr checks printed no list: " + shown(result.stdout()));
        }
        List<Check> checks = new ArrayList<>();
        for (JsonNode check : json) {
            checks.add(new Check(check.path("name").asText(), check.path("bucket").asText(), check.path("link").asText()));
        }
        return checks;
    }

    /**
     * The end of what the failed steps of a check's Actions run printed; empty for a check GitHub Actions did not run, or
     * a log gh will not give (expired, still being written). Run in {@code dir}, outside any clone.
     */
    public String failedLog(Path dir, String checkLink) {
        Matcher run = ACTIONS_RUN.matcher(checkLink == null ? "" : checkLink);
        if (!run.find()) {
            return "";
        }
        Git.Result result = Git.runProcess(List.of(command, "run", "view", run.group(2), "--repo", run.group(1), "--log-failed"), dir,
                ghToken, timeout, "gh run view");
        if (result.exitCode() != 0) {
            return "";
        }
        List<String> lines = result.stdout().lines().toList();
        return String.join("\n", lines.subList(Math.max(0, lines.size() - LOG_TAIL_LINES), lines.size()));
    }

    /** gh's answer as JSON. Not {@link Json#read}: that is for JSON this process wrote, and this is someone else's. */
    private static JsonNode json(String what, String stdout) {
        try {
            return Json.MAPPER.readTree(stdout);
        } catch (JsonProcessingException e) {
            throw new WorkspaceException(what + " printed no JSON: " + shown(stdout));
        }
    }

    private static String shown(String output) {
        String text = output.strip();
        return text.length() <= UNREADABLE_SHOWN ? text : text.substring(0, UNREADABLE_SHOWN) + "…";
    }

    private static WorkspaceException failed(String what, Git.Result result) {
        String output = result.stderr().isBlank() ? result.stdout() : result.stderr();
        return new WorkspaceException(what + " failed (exit " + result.exitCode() + "): " + output.strip());
    }
}
