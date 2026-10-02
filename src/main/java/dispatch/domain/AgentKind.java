package dispatch.domain;

import java.util.Arrays;
import java.util.List;
import java.util.Optional;

/**
 * The agents a project can run on, and what each can do. Config, jobs and the store carry a kind by its {@link #id}; what
 * a kind can do is asked here, never by comparing its name.
 */
public enum AgentKind {
    CLAUDE_CODE("claude-code", "claude"),
    CODEX("codex", "codex"),
    GEMINI("gemini", "gemini");

    private final String id;
    private final String defaultCommand;

    AgentKind(String id, String defaultCommand) {
        this.id = id;
        this.defaultCommand = defaultCommand;
    }

    /** The name in dispatch.yaml, a job and the store. */
    public String id() {
        return id;
    }

    /** The command to run when the configuration names none. */
    public String defaultCommand() {
        return defaultCommand;
    }

    /** Every kind's id, Claude Code first. */
    public static List<String> ids() {
        return Arrays.stream(values()).map(AgentKind::id).toList();
    }

    /**
     * The kind named {@code id}, or empty for a name this version does not know (another machine's newer kind, or a typo
     * the configuration's validation reports). A project that names none (null) runs on Claude Code.
     */
    public static Optional<AgentKind> find(String id) {
        if (id == null) {
            return Optional.of(CLAUDE_CODE);
        }
        return Arrays.stream(values()).filter(kind -> kind.id.equals(id)).findFirst();
    }

    /** As {@link #find}, for a name already validated: an unknown one is a bug, refused by name. */
    public static AgentKind of(String id) {
        return find(id).orElseThrow(() -> new IllegalArgumentException("unsupported agent type: " + id));
    }

    /** Whether its runs load Dispatch's skills, the owner's lists and a plan's picks (ADR 0034, 0036, 0037). */
    public boolean loadsPlugins() {
        return this == CLAUDE_CODE;
    }

    /** Whether a task's session can be continued in a terminal (RM-6). */
    public boolean teleports() {
        return this == CLAUDE_CODE;
    }

    /** Whether a member's computer may set its own model and effort for it; another agent keeps the team's (ADR 0026). */
    public boolean takesAComputersOwnModel() {
        return this == CLAUDE_CODE;
    }
}
