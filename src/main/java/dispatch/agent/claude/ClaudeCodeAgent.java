package dispatch.agent.claude;

import dispatch.agent.Agent;
import dispatch.agent.ProcessRun;
import dispatch.agent.RunHandle;
import dispatch.agent.RunRequest;
import dispatch.agent.Schemas;
import dispatch.agent.sandbox.AgentState;
import dispatch.agent.sandbox.Confinement;
import dispatch.domain.RunKind;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Runs Claude Code headless: {@code claude -p} with stream-json output, the prompt on stdin, and no permission prompts
 * (anything that would ask is denied). Only project/local Claude settings are loaded, and only the plugins and MCP servers
 * the machine's owner lists for Dispatch's runs (spec: owner plugins), so a run does not change with what the owner has
 * enabled for their own sessions.
 */
public final class ClaudeCodeAgent implements Agent {

    private static final String PLAN_SCHEMA = Schemas.PLAN;
    private static final String REVIEW_SCHEMA = Schemas.REVIEW;
    private static final String SPLIT_SCHEMA = Schemas.SPLIT;
    private static final String ASSISTANT_SCHEMA = Schemas.ASSISTANT;
    /** The one command the assistant may run: its view of the member's tasks (A-1). */
    private static final String ASSISTANT_BASH = "Bash(dispatch ask *)";
    /** Replaces Claude Code's coding prompt, which a split does not need: it would multiply the split's cost (ADR 0013). */
    private static final String SPLIT_SYSTEM_PROMPT =
            "You split a developer's chat message into independent development tasks. Answer only through the structured output.";
    /** Runs that do the task's work; a split and an assistant turn keep only what Dispatch gives them. */
    private static final Set<RunKind> OWNER_KINDS = Set.of(RunKind.PLAN, RunKind.EXECUTE, RunKind.REVIEW);

    /**
     * What Claude Code keeps in the owner's home (spec: agent state guard); what persists, where a working dir's project
     * dir is, and what is sourced were probed on 2.1.286.
     */
    public static final AgentState STATE = new AgentState(".claude", true, ".claude/projects",
            List.of(".claude/.credentials.json"),
            List.of(".claude.json"),
            List.of(".claude/settings.json", ".claude/settings.local.json", ".claude/CLAUDE.md", ".claude/agents",
                    ".claude/skills", ".claude/plugins", ".claude/commands", ".claude/output-styles", ".claude/hooks",
                    ".claude/rules", ".claude/local"),
            List.of(".claude/session-env", ".claude/shell-snapshots", ".claude/sessions", ".claude/plugins/store"));

    private final String command;
    private final Map<String, String> environment;
    private final Duration cancelGrace;
    private final Confinement confinement;
    private final OwnerPlugins ownerPlugins;

    /** @param environment the base environment for agent processes, normally {@code System.getenv()} */
    public ClaudeCodeAgent(String command, Map<String, String> environment, Duration cancelGrace) {
        this(command, environment, cancelGrace, Confinement.none("no sandbox configured"));
    }

    public ClaudeCodeAgent(String command, Map<String, String> environment, Duration cancelGrace, Confinement confinement) {
        this(command, environment, cancelGrace, confinement, OwnerPlugins.NONE);
    }

    /** @param ownerPlugins what this machine's owner lists for Claude Code runs (spec: owner plugins) */
    public ClaudeCodeAgent(String command, Map<String, String> environment, Duration cancelGrace, Confinement confinement,
                           OwnerPlugins ownerPlugins) {
        this.command = command;
        this.environment = Map.copyOf(environment);
        this.cancelGrace = cancelGrace;
        this.confinement = confinement;
        this.ownerPlugins = ownerPlugins;
    }

    @Override
    public RunHandle start(RunRequest request) {
        String permissionMode = permissionMode(request.kind());
        // Read and resolved now, on the machine that runs the agent: an edit or an install applies to this run.
        OwnerPlugins.Resolved owner = OWNER_KINDS.contains(request.kind())
                ? ownerPlugins.resolve(command, ProcessRun.agentEnvironment(environment)) : OwnerPlugins.Resolved.NONE;
        return ProcessRun.start("claude-code", commandLine(request, permissionMode, owner), request, environment, request.prompt(),
                new StreamParser(permissionMode, request.model(), request.workdir(), request.logBase()), cancelGrace, confinement, STATE);
    }

    private static String permissionMode(RunKind kind) {
        return switch (kind) {
            case PLAN, SPLIT, REVIEW -> "plan";
            case EXECUTE -> "auto";
            // Anything not allowed up front is refused without asking: here, every Bash command but dispatch ask.
            case ASSISTANT -> "dontAsk";
            case DELIVER -> throw new IllegalArgumentException("DELIVER runs do not start an agent");
        };
    }

    private List<String> commandLine(RunRequest request, String permissionMode, OwnerPlugins.Resolved owner) {
        List<String> args = new ArrayList<>(List.of(command, "-p",
                "--output-format", "stream-json", "--verbose",
                "--permission-mode", permissionMode,
                "--permission-prompts", "none",
                // The assistant's home is Dispatch's own directory; nothing local to a person's checkout applies there.
                "--setting-sources", request.kind() == RunKind.ASSISTANT ? "project" : "project,local",
                "--strict-mcp-config"));
        if (owner.mcpConfig() != null) {
            // Inline, as --json-schema is: nothing written to disk. --mcp-config takes every following argument that is
            // not a flag, and a flag always follows here.
            args.addAll(List.of("--mcp-config", owner.mcpConfig()));
        }
        if (request.budgetUsd() != null) {
            args.addAll(List.of("--max-budget-usd", request.budgetUsd().toPlainString()));
        }
        if (request.kind() == RunKind.SPLIT) {
            // Nothing will continue a split: no transcript on disk, no skills listed in its prompt.
            args.addAll(List.of("--no-session-persistence", "--disable-slash-commands"));
        } else if (request.resume()) {
            args.addAll(List.of("--resume", request.sessionId().toString()));
        } else {
            args.addAll(List.of("--session-id", request.sessionId().toString()));
        }
        if (request.model() != null) {
            args.addAll(List.of("--model", request.model()));
        }
        if (request.effort() != null) {
            args.addAll(List.of("--effort", request.effort()));
        }
        for (Path dir : request.readOnlyDirs()) {
            args.addAll(List.of("--add-dir", dir.toString()));
        }
        // The dispatch plugin's vetted skills (spec: agent skills), then the plugins the owner lists (spec: owner plugins);
        // nothing else the owner has enabled (--setting-sources). Also a working directory: a plan or review run may not
        // read a skill's supporting files outside its own, and nobody answers the prompt to allow it (probed on Claude Code
        // 2.1.286). The sandbox keeps it read-only.
        List<Path> plugins = new ArrayList<>(request.pluginDirs());
        plugins.addAll(owner.pluginDirs());
        for (Path dir : plugins) {
            args.addAll(List.of("--plugin-dir", dir.toString(), "--add-dir", dir.toString()));
        }
        // A listed skill cannot be invoked without the Skill tool (probed on Claude Code 2.1.286).
        String skill = plugins.isEmpty() ? "" : ",Skill";
        switch (request.kind()) {
            // Read-only investigation: no subagents or schedulers, just reading files and read-only shell commands.
            case PLAN -> args.addAll(List.of("--tools", "Read,Bash" + skill, "--json-schema", PLAN_SCHEMA));
            // Delivery is Dispatch's job (ADR 0007); the deny rules are a guardrail, not a boundary (ADR 0009).
            // --disallowedTools takes every following argument that is not a flag, so it stays last.
            case EXECUTE -> args.addAll(List.of("--tools", "Read,Edit,Write,Bash" + skill,
                    "--disallowedTools", "Bash(git commit *)", "Bash(git push *)", "Bash(gh *)"));
            // The verify loop's reviewer: read-only like a plan, its own schema (spec: verify loop).
            case REVIEW -> args.addAll(List.of("--tools", "Read,Bash" + skill, "--json-schema", REVIEW_SCHEMA));
            case SPLIT -> args.addAll(List.of("--tools", "", "--json-schema", SPLIT_SCHEMA, "--system-prompt", SPLIT_SYSTEM_PROMPT));
            // Reads code, asks for tasks and loads its taskmanager skill (A-1), nothing else; --allowedTools takes every following argument too, so it is last.
            case ASSISTANT -> args.addAll(List.of("--tools", "Read,Grep,Glob,Bash,Skill", "--json-schema", ASSISTANT_SCHEMA,
                    "--allowedTools", ASSISTANT_BASH));
            case DELIVER -> throw new IllegalArgumentException("DELIVER runs do not start an agent");
        }
        return args;
    }
}
