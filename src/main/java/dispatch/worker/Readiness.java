package dispatch.worker;

import dispatch.domain.RunKind;
import java.util.Map;
import java.util.Optional;

/**
 * What a member's computer says about itself (spec: Readiness). It travels on the poll the worker already makes, and
 * the team machine keeps the last one to decide whether a run may be claimed.
 *
 * <p>Pure: it reads no store, runs no process and speaks no language. A blocker is a code and a detail, never a
 * sentence — the bot's words live in messages_mn.properties.
 *
 * @param claude Claude Code's check, which every worker has reported since T-1 and an older team machine gates on
 * @param agents the other agents this computer has, by type ("codex", "gemini"), each held only by its own (ADR 0026)
 */
public record Readiness(Check claude, Check gh, Map<String, Check> projects, Map<String, Check> agents) {

    /** What a worker that reported nothing counts as: ready. A machine upgraded first must not stall its workers. */
    public static final Readiness READY = new Readiness(new Check(true, null), new Check(true, null), Map.of());

    public record Check(boolean ok, String detail) {
    }

    /** @param code one of "version", "claude", "codex", "gemini", "gh", "clone"; the Renderer turns it into words */
    public record Blocker(String code, String detail) {
    }

    public Readiness {
        projects = Map.copyOf(projects);
        agents = Map.copyOf(agents);
    }

    /** A computer that reported Claude Code alone, as every worker did before other agents. */
    public Readiness(Check claude, Check gh, Map<String, Check> projects) {
        this(claude, gh, projects, Map.of());
    }

    /**
     * Why a run of {@code kind} on {@code project}, which runs on {@code agent}, cannot start here, or empty when it can.
     * At most one blocker is reported, the one that holds the most, so a member gets one message rather than three.
     */
    public Optional<Blocker> blocker(String project, String agent, RunKind kind) {
        boolean claudeCode = agent.equals("claude-code");
        Check runs = claudeCode ? claude : agents.get(agent);
        // An agent the worker never reported on is not held, as a project is not: the run fails saying what to add.
        if (runs != null && !runs.ok()) {
            return Optional.of(new Blocker(claudeCode ? "claude" : agent, runs.detail()));
        }
        Check clone = projects.get(project);
        // A project the worker never reported on is not held: better a run that fails saying why than one that waits
        // forever for a report that is never coming.
        if (clone != null && !clone.ok()) {
            return Optional.of(new Blocker("clone", clone.detail()));
        }
        if (!gh.ok() && (kind == RunKind.EXECUTE || kind == RunKind.DELIVER)) {
            return Optional.of(new Blocker("gh", gh.detail()));
        }
        return Optional.empty();
    }
}
