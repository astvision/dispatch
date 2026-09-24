import type { TaskRow, Timeline } from "../api";

/** Bold's own task, still running. */
export const myRunningTask: TaskRow = {
  taskId: 1,
  project: "alm",
  title: "Fix the login timeout",
  state: "running",
  priority: "NORMAL",
  requester: "Bold",
  mine: true,
  startedAt: "2026-09-23T09:00:00Z",
};

/** Bold's own task, finished, so it may be retried. */
export const myFinishedTask: TaskRow = {
  taskId: 2,
  project: "alm",
  title: "Add the export button",
  state: "finished",
  priority: "NORMAL",
  requester: "Bold",
  mine: true,
  phase: "COMPLETED",
  costUsd: "1.20",
  completedAt: "2026-09-23T08:00:00Z",
};

/** Ali's task as an admin sees it: the headline, and no cost (ADR 0020). */
export const someoneElsesTask: TaskRow = {
  taskId: 3,
  project: "alm",
  title: "Rename the settings page",
  state: "queued",
  priority: "LOW",
  requester: "Ali",
  mine: false,
  costUsd: null,
};

export const myTimeline: Timeline = {
  taskId: 2,
  project: "alm",
  title: "Add the export button",
  requester: "Bold",
  phase: "COMPLETED",
  priority: "NORMAL",
  prUrl: "https://github.com/acme/alm/pull/7",
  failureReason: null,
  createdAt: "2026-09-23T07:00:00Z",
  completedAt: "2026-09-23T08:00:00Z",
  costUsd: "1.20",
  runs: [
    {
      seq: 1,
      kind: "PLAN",
      cause: null,
      status: "SUCCEEDED",
      requestedBy: "Bold",
      instruction: null,
      queuedAt: "2026-09-23T07:00:00Z",
      startedAt: "2026-09-23T07:01:00Z",
      finishedAt: "2026-09-23T07:05:00Z",
      costUsd: "0.20",
      failureReason: null,
    },
  ],
};

export const headlineTimeline: Timeline = {
  taskId: 3,
  project: "alm",
  title: "Rename the settings page",
  requester: "Ali",
  phase: "PLANNING",
  priority: "LOW",
  prUrl: null,
  failureReason: null,
  createdAt: "2026-09-23T09:30:00Z",
  completedAt: null,
  costUsd: null,
  headline: true,
};
