import { fireEvent, render, screen, within } from "@testing-library/react";
import { afterEach, beforeEach, expect, test, vi } from "vitest";
import * as api from "../../api";
import { DesktopProviders } from "../status";
import LiveBoard from "./LiveBoard";

vi.mock("../../api", async (importOriginal) => ({
  ...(await importOriginal<typeof import("../../api")>()),
  getOverview: vi.fn(),
  getLive: vi.fn(),
  getSpend: vi.fn(),
  approvePlan: vi.fn(),
  getTaskDetail: vi.fn(),
}));

const overview: api.Overview = {
  configured: true, version: "0.2.0", name: "acme", configFile: "/c", stateDir: "/s",
  service: { name: "systemd user service dispatch.service", installed: true, running: true, detail: "active", notes: [] },
  findings: [],
};
const row = (taskId: number, extra: Partial<api.LiveTask>): api.LiveTask => ({
  taskId, project: "alm", title: `Task ${taskId}`, priority: "NORMAL", requester: "Bold", mine: true, actions: [], ...extra,
});
const live: api.Live = {
  version: "0.2.0", name: "acme", running: 1, queued: 1,
  waitingOnYou: [{ taskId: 14, title: "Task 14" }, { taskId: 15, title: "Task 15" }], waitingOnOthers: 1,
  todayUsd: "3.40", monthUsd: "41.20", maxConcurrent: 2, projects: ["alm"],
  tasks: {
    running: [row(13, { kind: "EXECUTE", startedAt: new Date(Date.now() - 12 * 60_000).toISOString(), mine: false, requester: "Ali" })],
    queued: [row(12, { queuedAt: new Date().toISOString() })],
    awaitingApproval: [
      row(14, { planSeq: 2, actions: ["approve", "correct", "reject"] }),
      row(15, { planSeq: 1, openQuestions: 1, actions: ["answer"] }),
      row(16, { mine: false, requester: "Ali" }),
    ],
  },
};

beforeEach(() => {
  vi.mocked(api.getOverview).mockResolvedValue(overview);
  vi.mocked(api.getSpend).mockResolvedValue({ from: "2026-08-30", to: "2026-09-28", days: [], projects: [], totalUsd: "0.00" });
});
afterEach(() => vi.resetAllMocks());

function renderBoard() {
  render(<DesktopProviders><LiveBoard navigate={vi.fn()} /></DesktopProviders>);
}

test("the counters, what waits on you with its one action, and what runs out of how many at once", async () => {
  vi.mocked(api.getLive).mockResolvedValue(live);
  renderBoard();

  expect(await screen.findByText("$41.20")).toBeInTheDocument();
  expect(screen.getByText("$3.40")).toBeInTheDocument();
  expect(screen.getByRole("button", { name: "Approve" })).toBeInTheDocument();
  expect(screen.getByRole("button", { name: "To answer" })).toBeInTheDocument();
  expect(screen.queryByText(/Task 16/)).not.toBeInTheDocument();
  expect(screen.getByText("1 / 2 at once")).toBeInTheDocument();
  expect(screen.getByText(/Task 12/)).toBeInTheDocument();
});

test("Approve approves the plan the reading showed; a plan replaced since says why and is read again", async () => {
  vi.mocked(api.getLive).mockResolvedValue(live);
  vi.mocked(api.approvePlan).mockRejectedValue(new api.ApiError("stale", "The plan was replaced; read it again"));
  renderBoard();

  fireEvent.click(await screen.findByRole("button", { name: "Approve" }));

  expect(await screen.findByText("The plan was replaced; read it again")).toBeInTheDocument();
  expect(api.approvePlan).toHaveBeenCalledWith(14, 2);
  await vi.waitFor(() => expect(api.getLive).toHaveBeenCalledTimes(2));
});

test("a row opens its task in the side panel", async () => {
  vi.mocked(api.getLive).mockResolvedValue(live);
  vi.mocked(api.getTaskDetail).mockReturnValue(new Promise(() => {}));
  renderBoard();

  fireEvent.click(await screen.findByRole("button", { name: /Task 13/ }));

  expect(within(await screen.findByRole("dialog")).getByText("#13")).toBeInTheDocument();
});

test("a bot of the version before D-2b answers without the lists: the board says a restart runs this one, and the page stays", async () => {
  const { maxConcurrent: _limit, projects: _projects, tasks: _lists, ...older } = live;
  vi.mocked(api.getLive).mockResolvedValue(older as api.Live);
  renderBoard();

  expect(await screen.findByText("Restart the service to run 0.2.0")).toBeInTheDocument();
});

