import { act, render, screen } from "@testing-library/react";
import { afterEach, describe, expect, it, vi } from "vitest";
import * as api from "../api";
import type { RunView } from "../api";
import RunMonitor from "./RunMonitor";

vi.mock("../api", async (importOriginal) => ({
  ...(await importOriginal<typeof import("../api")>()),
  getTaskRun: vi.fn(),
}));

const running: RunView = {
  taskId: 15, seq: 2, kind: "EXECUTE", status: "RUNNING", startedAt: "2026-09-30T10:00:00Z", finishedAt: null, costUsd: null,
  now: "2026-09-30T10:07:30Z",
  steps: [
    { n: 1, kind: "IMPLEMENT", round: 1, startedAt: "2026-09-30T10:00:00Z", endedAt: "2026-09-30T10:04:00Z", outcome: "DONE" },
    { n: 2, kind: "TEST", round: 1, startedAt: "2026-09-30T10:04:00Z", endedAt: "2026-09-30T10:04:45Z", outcome: "FAILED",
      detail: { tail: "FAIL LinkTest > brokenAnchor" } },
    { n: 3, kind: "FIX", round: 1, startedAt: "2026-09-30T10:04:45Z", endedAt: null, outcome: null },
  ],
  activity: { steps: 7, lastAction: "Edit docs/links.md" },
};

describe("the run monitor", () => {
  afterEach(() => {
    vi.useRealTimers();
    vi.resetAllMocks();
  });

  it("shows each step with its time, the failing tail folded, and what the agent does now", async () => {
    vi.mocked(api.getTaskRun).mockResolvedValue(running);

    render(<RunMonitor taskId={15} live />);

    expect(await screen.findByText("Хэрэгжүүлэлт")).toBeInTheDocument();
    expect(screen.getByText("4:00")).toBeInTheDocument();
    expect(screen.getByText("Тест 1")).toBeInTheDocument();
    expect(screen.getByText("0:45")).toBeInTheDocument();
    expect(screen.getByText("FAIL LinkTest > brokenAnchor")).toBeInTheDocument();
    expect(screen.getByText("Засвар 1/3")).toBeInTheDocument();
    expect(screen.getByText("Edit docs/links.md")).toBeInTheDocument();
    expect(screen.getByText("2:45"), "the running step's time counts on the server's clock").toBeInTheDocument();
  });

  it("asks again every 2 s while the run is live", async () => {
    vi.useFakeTimers();
    vi.mocked(api.getTaskRun).mockResolvedValue(running);

    render(<RunMonitor taskId={15} live />);
    await act(async () => vi.advanceTimersByTimeAsync(4100));

    expect(vi.mocked(api.getTaskRun).mock.calls.length).toBeGreaterThanOrEqual(3);
  });

  it("lists a review's findings with how serious each is", async () => {
    vi.mocked(api.getTaskRun).mockResolvedValue({
      ...running, status: "SUCCEEDED", finishedAt: "2026-09-30T10:12:00Z", costUsd: "1.05",
      steps: [{ n: 1, kind: "REVIEW", round: 1, startedAt: "2026-09-30T10:08:00Z", endedAt: "2026-09-30T10:11:00Z", outcome: "FINDINGS",
        detail: { findings: [{ severity: "blocking", file: "README.md", line: 12, text: "install command is stale" }] } }],
    });

    render(<RunMonitor taskId={15} live={false} />);

    expect(await screen.findByText(/README.md:12/)).toBeInTheDocument();
    expect(screen.getByText(/install command is stale/)).toBeInTheDocument();
    expect(screen.getByText("$1.05")).toBeInTheDocument();
  });
});
