import type { TaskRow } from "../../api";

export type TaskGroup = "waitingOnYou" | "running" | "queued" | "waitingOnOthers" | "finished";
export const GROUP_ORDER: TaskGroup[] = ["waitingOnYou", "running", "queued", "waitingOnOthers", "finished"];

export interface Filter {
  project: string | null;
  person: string | null;
  text: string;
}

const MONTH_MS = 30 * 24 * 60 * 60 * 1000;

export function groupOf(row: TaskRow): TaskGroup {
  if (row.state === "awaitingApproval") return row.mine ? "waitingOnYou" : "waitingOnOthers";
  if (row.state === "running") return "running";
  if (row.state === "queued") return "queued";
  return "finished";
}

function matches(row: TaskRow, filter: Filter) {
  if (filter.project && row.project !== filter.project) return false;
  if (filter.person && row.requester !== filter.person) return false;
  const text = filter.text.trim().toLowerCase();
  if (!text) return true;
  if (/^#?\d+$/.test(text)) return row.taskId === Number(text.replace("#", ""));
  return row.title.toLowerCase().includes(text);
}

/** The groups in page order, each with its matching rows; finished ones are the last 30 days'. Empty groups are left out. */
export function grouped(rows: TaskRow[], filter: Filter, now: Date): [TaskGroup, TaskRow[]][] {
  const recent = (row: TaskRow) => row.state !== "finished" || !row.completedAt
    || now.getTime() - new Date(row.completedAt).getTime() <= MONTH_MS;
  return GROUP_ORDER
    .map((group): [TaskGroup, TaskRow[]] => [group, rows.filter((row) => groupOf(row) === group && recent(row) && matches(row, filter))])
    .filter(([, list]) => list.length > 0);
}

/** How long ago, in the largest whole unit: what a row shows beside its state. Null without a time. */
export function age(iso: string | null | undefined, now: Date): { unit: "minutes" | "hours" | "days"; n: number } | null {
  if (!iso) return null;
  const minutes = Math.max(0, Math.floor((now.getTime() - new Date(iso).getTime()) / 60_000));
  if (Number.isNaN(minutes)) return null;
  if (minutes < 60) return { unit: "minutes", n: minutes };
  if (minutes < 60 * 24) return { unit: "hours", n: Math.floor(minutes / 60) };
  return { unit: "days", n: Math.floor(minutes / (60 * 24)) };
}
