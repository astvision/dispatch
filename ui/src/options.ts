import type { AgentType, Effort } from "./api";

export const MODELS: { value: string | null; label: string }[] = [
  { value: null, label: "Claude Code's default" }, { value: "sonnet", label: "Sonnet" }, { value: "opus", label: "Opus" },
  { value: "fable", label: "Fable" },
];
export const EFFORTS: { value: Effort | null; label: string }[] = [
  { value: null, label: "Claude Code's default" }, { value: "low", label: "Low" }, { value: "medium", label: "Medium" },
  { value: "high", label: "High" }, { value: "xhigh", label: "Extra high" }, { value: "max", label: "Max" },
];
/** A phase's own choice; the first keeps what the project sets for both phases. */
export const PHASE_MODELS = [{ value: null, label: "Same as above" }, ...MODELS.slice(1)];
export const PHASE_EFFORTS = [{ value: null, label: "Same as above" }, ...EFFORTS.slice(1)];

/** The options, plus the current value when the config names a model the list does not have. */
export function withCurrent(options: { value: string | null; label: string }[], current: string | null) {
  return current && !options.some((option) => option.value === current) ? [...options, { value: current, label: current }] : options;
}

/** The agents a project can run on (ADR 0026). */
export const AGENTS: { value: AgentType; label: string }[] = [
  { value: "claude-code", label: "Claude Code" }, { value: "codex", label: "Codex" }, { value: "gemini", label: "Gemini CLI" },
];

export const agentLabel = (agent: AgentType) => AGENTS.find((option) => option.value === agent)?.label ?? agent;

/** What an agent's own default is called where a project names no model or effort. */
export const agentDefault = (agent: AgentType) => (agent === "claude-code" ? "Claude Code's default" : `${agentLabel(agent)}'s default`);

/** Claude Code's levels; Codex's model_reasoning_effort lacks max; Gemini CLI has no effort at all. */
export function effortsFor(agent: AgentType): { value: Effort | null; label: string }[] {
  if (agent === "gemini") return [];
  if (agent === "codex") return [{ value: null, label: agentDefault(agent) }, ...EFFORTS.slice(1).filter((option) => option.value !== "max")];
  return EFFORTS;
}
