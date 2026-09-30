import { act, fireEvent, render, screen, waitFor } from "@testing-library/react";
import { afterEach, describe, expect, it, vi } from "vitest";
import * as api from "../api";
import type { RunView } from "../api";
import RunMonitor from "./RunMonitor";

vi.mock("../api", async (importOriginal) => ({
  ...(await importOriginal<typeof import("../api")>()),
  getTaskRun: vi.fn(),
  steerRun: vi.fn(),
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

  it("skips the running step and delivers now with one tap each", async () => {
    const idle = { pauseBeforeReview: false, canPause: false, paused: false, pauseEndsAt: null };
    const steerable: RunView = { ...running, controls: { skip: 3, deliverNow: true, deliverNowRequested: false, ...idle } };
    vi.mocked(api.getTaskRun).mockResolvedValue(steerable);
    vi.mocked(api.steerRun).mockResolvedValue({ ...steerable, controls: { skip: null, deliverNow: true, deliverNowRequested: false, ...idle } });

    render(<RunMonitor taskId={15} live />);
    fireEvent.click(await screen.findByRole("button", { name: "⏭ Засвар 1/3-г алгасах" }));

    await waitFor(() => expect(api.steerRun).toHaveBeenCalledWith(15, { action: "skip", step: 3 }));
    await waitFor(() => expect(screen.queryByRole("button", { name: /алгасах/ })).not.toBeInTheDocument());
    vi.mocked(api.steerRun).mockResolvedValue({ ...steerable, controls: { skip: null, deliverNow: false, deliverNowRequested: true, ...idle } });
    fireEvent.click(screen.getByRole("button", { name: "📦 Одоо хүргэх" }));

    await waitFor(() => expect(api.steerRun).toHaveBeenCalledWith(15, { action: "deliverNow" }));
    expect(await screen.findByText("📦 Хүргэхээр зогсоож байна…")).toBeInTheDocument();
  });

  it("offers nothing on a finished run", async () => {
    vi.mocked(api.getTaskRun).mockResolvedValue({ ...running, status: "SUCCEEDED", controls: undefined });

    render(<RunMonitor taskId={15} live={false} />);

    expect(await screen.findByText("Хэрэгжүүлэлт")).toBeInTheDocument();
    expect(screen.queryByRole("button")).not.toBeInTheDocument();
  });

  it("switches the pause on and, once paused, reviews or delivers with the time it goes on by itself", async () => {
    const controls = { skip: null, deliverNow: false, deliverNowRequested: false, pauseBeforeReview: false, canPause: true, paused: false,
      pauseEndsAt: null };
    vi.mocked(api.getTaskRun).mockResolvedValue({ ...running, controls });
    vi.mocked(api.steerRun).mockResolvedValue({ ...running, controls: { ...controls, pauseBeforeReview: true } });

    render(<RunMonitor taskId={15} live />);
    fireEvent.click(await screen.findByRole("switch"));
    await waitFor(() => expect(api.steerRun).toHaveBeenCalledWith(15, { action: "pause" }));

    vi.mocked(api.steerRun).mockResolvedValue({
      ...running,
      steps: [...running.steps.slice(0, 2), { n: 3, kind: "PAUSE", round: 1, startedAt: "2026-09-30T10:05:00Z", endedAt: null, outcome: null }],
      controls: { ...controls, canPause: false, paused: true, deliverNow: true, pauseEndsAt: "2026-09-30T10:20:00Z" },
    });
    fireEvent.click(screen.getByRole("switch"));
    expect(await screen.findByText("Тест дууссан. Review хийх үү?")).toBeInTheDocument();
    expect(screen.getByText("12:30"), "15 min from the pause, on the server's clock").toBeInTheDocument();
    fireEvent.click(screen.getByRole("button", { name: "🔍 Review хийх" }));

    await waitFor(() => expect(api.steerRun).toHaveBeenCalledWith(15, { action: "review" }));
  });
});
