import type { Effort } from "./api";

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
