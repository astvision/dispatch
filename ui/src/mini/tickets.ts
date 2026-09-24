import type { TaskRow } from "../api";

/** How many finished tickets the home screen stacks; the full list is one tap away on Миний даалгаврууд. */
export const SERVED_SIZE = 5;

export interface Tickets {
  /** The viewer's own tasks waiting on them, longest waiting first. */
  pass: TaskRow[];
  /** Running, then queued, as the server lists them. */
  rail: TaskRow[];
  /** The latest finished, newest first. */
  served: TaskRow[];
}

export function groupTickets(tasks: TaskRow[]): Tickets {
  const pass = tasks.filter((task) => task.state === "awaitingApproval" && task.mine)
    .sort((a, b) => (a.since ?? "").localeCompare(b.since ?? ""));
  const rail = tasks.filter((task) => task.state === "running" || task.state === "queued");
  const served = tasks.filter((task) => task.state === "finished").slice(0, SERVED_SIZE);
  return { pass, rail, served };
}

/** Short enough to print on one line of a ticket: draft, plan, your answer, the work, the pull request. */
export const STATIONS = ["Ноорог", "Төлөв", "Хариу", "Ажил", "PR"];

/** The 0-based station a ticket stands at on the way from draft to pull request. */
export function stationOf(task: TaskRow): number {
  if (task.state === "awaitingApproval") return 2;
  if (task.state === "finished") return 4;
  return task.kind === "EXECUTE" ? 3 : 1;
}

export type Tone = "you" | "working" | "waiting" | "done" | "failed" | "quiet";

/** The word printed beside a ticket's clock, and its band's colour: never the colour alone. */
export function stateOf(task: TaskRow): { word: string; tone: Tone } {
  switch (task.state) {
    case "awaitingApproval":
      return { word: "таныг хүлээж", tone: "you" };
    case "running":
      return { word: task.kind === "EXECUTE" ? "хийж байна" : "төлөвлөж байна", tone: "working" };
    case "queued":
      return { word: task.waitingForWorker ? "компьютер хүлээж" : "дараалалд", tone: "waiting" };
    case "finished":
      if (task.phase === "COMPLETED") return { word: "хүргэсэн", tone: "done" };
      if (task.phase === "FAILED") return { word: "амжилтгүй", tone: "failed" };
      if (task.phase === "REJECTED") return { word: "татгалзсан", tone: "quiet" };
      return { word: "цуцалсан", tone: "quiet" };
  }
}

/** When the ticket's current wait began: what its clock counts from. */
export function clockStart(task: TaskRow): string | null | undefined {
  switch (task.state) {
    case "awaitingApproval":
      return task.since;
    case "running":
      return task.startedAt;
    case "queued":
      return task.queuedAt;
    case "finished":
      return task.completedAt;
  }
}

const pad = (value: number) => String(value).padStart(2, "0");

/** How long it has been, as a running clock in fixed slots: 4:07, 1:04:07, then whole days. Empty when unknown. */
export function clock(since: string | null | undefined, now: number): string {
  if (!since) return "";
  const seconds = Math.max(0, Math.floor((now - Date.parse(since)) / 1000));
  if (Number.isNaN(seconds)) return "";
  const days = Math.floor(seconds / 86_400);
  if (days > 0) return `${days} өдөр`;
  const hours = Math.floor(seconds / 3600);
  const minutes = Math.floor((seconds % 3600) / 60);
  return hours > 0 ? `${hours}:${pad(minutes)}:${pad(seconds % 60)}` : `${minutes}:${pad(seconds % 60)}`;
}

/** How long ago, for finished tickets that no longer tick: "12 мин", "3 цаг", "2 өдөр". */
export function ago(since: string | null | undefined, now: number): string {
  if (!since) return "";
  const minutes = Math.max(0, Math.floor((now - Date.parse(since)) / 60_000));
  if (Number.isNaN(minutes)) return "";
  if (minutes < 60) return `${minutes} мин`;
  if (minutes < 60 * 24) return `${Math.floor(minutes / 60)} цаг`;
  return `${Math.floor(minutes / 1440)} өдөр`;
}

/** Whether the viewer asked for less motion; read when a motion starts, so a change in settings takes effect at once. */
export function reducedMotion() {
  return typeof window.matchMedia === "function" && window.matchMedia("(prefers-reduced-motion: reduce)").matches;
}
