import { fireEvent, render, screen } from "@testing-library/react";
import { afterEach, expect, test, vi } from "vitest";
import * as api from "../../api";
import type { TaskDetail, Timeline } from "../../api";
import TaskPage from "./TaskPage";

vi.mock("../../api", async (importOriginal) => ({
  ...(await importOriginal<typeof import("../../api")>()),
  getTaskDetail: vi.fn(),
  taskTimeline: vi.fn(),
  getLogs: vi.fn(),
}));

afterEach(() => vi.resetAllMocks());

const detail: TaskDetail = { taskId: 14, project: "crm", title: "Fix the login timeout", phase: "COMPLETED",
  prUrl: "https://github.com/acme/crm/pull/88", failureReason: null, createdAt: null, completedAt: null, costUsd: "1.84",
  requester: "Bold", priority: "URGENT", actions: [] };
const timeline: Timeline = { taskId: 14, project: "crm", title: "Fix the login timeout", requester: "Bold", phase: "COMPLETED",
  priority: "URGENT", prUrl: "https://github.com/acme/crm/pull/88", branch: "dispatch/14", baseBranch: "main", failureReason: null,
  createdAt: "2026-09-28T06:22:00Z", completedAt: "2026-09-28T07:02:00Z", costUsd: "1.84",
  runs: [{ seq: 1, kind: "PLAN", cause: "TASK", status: "SUCCEEDED", requestedBy: "Bold", instruction: null,
    queuedAt: "2026-09-28T06:22:00Z", startedAt: "2026-09-28T06:22:10Z", finishedAt: "2026-09-28T06:25:10Z", costUsd: "0.42",
    failureReason: null }] };
const logs = { file: "/s/dispatch.log", exists: true,
  lines: ["ts=2026-09-28T06:25:24.949Z level=INFO event=run.finished task=14 run=1 status=SUCCEEDED cost_usd=0.42"] };

test("a task's page shows it beside its facts, its runs and its own log", async () => {
  vi.mocked(api.getTaskDetail).mockResolvedValue(detail);
  vi.mocked(api.taskTimeline).mockResolvedValue(timeline);
  vi.mocked(api.getLogs).mockResolvedValue(logs);

  render(<TaskPage taskId={14} navigate={vi.fn()} />);

  expect(await screen.findByText("#14 Fix the login timeout")).toBeInTheDocument();
  expect(await screen.findByText("dispatch/14")).toBeInTheDocument();
  expect(screen.getByText("Planning")).toBeInTheDocument();
  expect(screen.getByText("$0.42")).toBeInTheDocument();
  expect(await screen.findByText("run.finished")).toBeInTheDocument();
  expect(api.getLogs).toHaveBeenCalledWith({ lines: 200, task: 14 }, expect.anything());
});

test("without the bot the page says so, and the task's log still reads", async () => {
  vi.mocked(api.getTaskDetail).mockRejectedValue(new api.ApiError("bot_not_running", "The bot is not running"));
  vi.mocked(api.taskTimeline).mockRejectedValue(new api.ApiError("bot_not_running", "The bot is not running"));
  vi.mocked(api.getLogs).mockResolvedValue(logs);

  render(<TaskPage taskId={14} navigate={vi.fn()} />);

  expect(await screen.findByText("The bot is not running: tasks show while it runs.")).toBeInTheDocument();
  expect(await screen.findByText("run.finished")).toBeInTheDocument();
});

test("back leads to the list", async () => {
  vi.mocked(api.getTaskDetail).mockResolvedValue(detail);
  vi.mocked(api.taskTimeline).mockResolvedValue(timeline);
  vi.mocked(api.getLogs).mockResolvedValue(logs);
  const navigate = vi.fn();

  render(<TaskPage taskId={14} navigate={navigate} />);
  fireEvent.click(await screen.findByRole("button", { name: /Tasks/ }));

  expect(navigate).toHaveBeenCalledWith("/tasks");
});

test("with the bot stopped the task's page says so and offers the Overview", async () => {
  vi.mocked(api.getTaskDetail).mockRejectedValue(new api.ApiError("bot_not_running", "The bot is not running"));
  vi.mocked(api.taskTimeline).mockRejectedValue(new api.ApiError("bot_not_running", "The bot is not running"));
  vi.mocked(api.getLogs).mockResolvedValue(logs);
  const navigate = vi.fn();
  render(<TaskPage taskId={14} navigate={navigate} />);

  fireEvent.click(await screen.findByRole("button", { name: "Open the overview" }));

  expect(navigate).toHaveBeenCalledWith("/");
});
