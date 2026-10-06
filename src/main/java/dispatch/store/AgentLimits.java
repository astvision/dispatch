package dispatch.store;

import dispatch.agent.UsageLimit;

import java.time.Instant;
import java.util.Optional;
import java.util.Set;

/**
 * The hold a machine's agent is under once its usage limit cut a run short (spec: usage limit): machine 0 is the bot's own
 * computer (personal mode), any other the worker of that id. A new hit replaces the row; a row whose reset has passed holds
 * nothing and is never deleted.
 */
public final class AgentLimits {

    public static final long THIS_MACHINE = 0;

    private AgentLimits() {
    }

    public static void hold(Tx tx, long machine, String agent, UsageLimit limit) {
        tx.update("""
                        INSERT INTO agent_limit (machine, agent, resets_at, type) VALUES (?, ?, ?, ?)
                        ON CONFLICT (machine, agent) DO UPDATE SET resets_at = excluded.resets_at, type = excluded.type""",
                machine, agent, Instant.ofEpochSecond(limit.resetsAt()), limit.type());
    }

    /** When the machine's agent is free again, if it is held at {@code now}. */
    public static Optional<Instant> heldUntil(Tx tx, long machine, String agent, Instant now) {
        return tx.one("SELECT resets_at FROM agent_limit WHERE machine = ? AND agent = ? AND resets_at > ?",
                row -> row.instant("resets_at"), machine, agent, now);
    }

    /** The agents held on the machine at {@code now}. */
    public static Set<String> heldAgents(Tx tx, long machine, Instant now) {
        return Set.copyOf(tx.list("SELECT agent FROM agent_limit WHERE machine = ? AND resets_at > ?",
                row -> row.string("agent"), machine, now));
    }
}
