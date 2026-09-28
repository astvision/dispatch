import type { AgentType, Effort } from "./api";
import type { Translate } from "./i18n/i18n";

export interface Option<T> {
  value: T | null;
  label: string;
}

/** The agents a project can run on (ADR 0026); their names are their own in every language. */
export const AGENTS: { value: AgentType; label: string }[] = [
  { value: "claude-code", label: "Claude Code" }, { value: "codex", label: "Codex" }, { value: "gemini", label: "Gemini CLI" },
];

export const agentLabel = (agent: AgentType) => AGENTS.find((option) => option.value === agent)?.label ?? agent;

/** What an agent's own default is called where a project names no model or effort. */
export const agentDefault = (t: Translate, agent: AgentType) => t("options.agentDefault", { agent: agentLabel(agent) });

export const models = (t: Translate): Option<string>[] => [
  { value: null, label: agentDefault(t, "claude-code") }, { value: "sonnet", label: "Sonnet" }, { value: "opus", label: "Opus" },
  { value: "fable", label: "Fable" },
];

export const efforts = (t: Translate): Option<Effort>[] => [
  { value: null, label: agentDefault(t, "claude-code") }, { value: "low", label: t("options.low") },
  { value: "medium", label: t("options.medium") }, { value: "high", label: t("options.high") },
  { value: "xhigh", label: t("options.xhigh") }, { value: "max", label: t("options.max") },
];

/** A phase's own choice; the first keeps what the project sets for both phases. */
export const phaseModels = (t: Translate): Option<string>[] => [{ value: null, label: t("options.same") }, ...models(t).slice(1)];
export const phaseEfforts = (t: Translate): Option<Effort>[] => [{ value: null, label: t("options.same") }, ...efforts(t).slice(1)];

/** The options, plus the current value when the config names a model the list does not have. */
export function withCurrent(options: Option<string>[], current: string | null) {
  return current && !options.some((option) => option.value === current) ? [...options, { value: current, label: current }] : options;
}

/** Claude Code's levels; Codex's model_reasoning_effort lacks max; Gemini CLI has no effort at all. */
export function effortsFor(t: Translate, agent: AgentType): Option<Effort>[] {
  if (agent === "gemini") return [];
  if (agent === "codex") {
    return [{ value: null, label: agentDefault(t, agent) }, ...efforts(t).slice(1).filter((option) => option.value !== "max")];
  }
  return efforts(t);
}
