package dispatch.worker;

import dispatch.core.Job;
import dispatch.core.JobResult;
import java.util.List;

/**
 * What a worker and its team machine send each other (ADR 0039), one record per message, written and read by the same
 * code on both sides. Together with {@link Job}, {@link JobResult}, {@link Readiness}, {@link RemoteWorkers.Progress} and
 * {@link RemoteWorkers.Reply} this is the whole wire, which WireContractTest pins under {@link WorkerProtocol#VERSION}.
 */
public final class Wire {

    private Wire() {
    }

    /** A worker's poll for work: what it speaks, how many runs it takes at once, and what it can run (ADR 0021). */
    public record Poll(int protocol, int maxConcurrentRuns, Readiness readiness) {
    }

    /** The team machine's answer to a poll: what it speaks, and a job or null. */
    public record Next(int protocol, Job job) {
    }

    /** A finished run, back to the team machine. */
    public record Result(long taskId, int seq, JobResult result) {
    }

    /** One of a leased job's files, asked for by its reference. */
    public record Attachment(long taskId, String fileRef) {
    }

    /** What a worker needs before it can run anything: the team's name, the commit author and its member's projects. */
    public record Setup(String team, String authorName, String authorEmail, List<Project> projects) {

        public Setup {
            projects = List.copyOf(projects);
        }
    }

    /** A project as the team configured it: null model and effort mean the agent's defaults. */
    public record Project(String name, String repo, String baseBranch, String agent, String model, String effort) {
    }
}
