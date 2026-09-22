import { act, renderHook } from "@testing-library/react";
import { afterEach, expect, test, vi } from "vitest";
import * as api from "./api";
import { useRestart } from "./useRestart";

// The real module, with the calls this file answers itself; ApiError stays the real class.
vi.mock("./api", async (importOriginal) => ({
  ...(await importOriginal<typeof import("./api")>()),
  restartService: vi.fn(),
  getOverview: vi.fn(),
  getLogs: vi.fn(),
}));

afterEach(() => vi.resetAllMocks());

const service = { name: "systemd user service dispatch.service", installed: true, running: true, detail: "active", notes: [] };
const overview = (running: boolean): api.Overview => ({
  version: "1", configFile: "/c", stateDir: "/s", configured: true, service: { ...service, running }, findings: [],
});

test("after a restart it waits until the service runs again; a lost connection meanwhile is expected", async () => {
  vi.mocked(api.restartService).mockResolvedValue(service);
  vi.mocked(api.getOverview).mockRejectedValueOnce(new api.ApiError("unreachable", "gone"))
    .mockResolvedValueOnce(overview(false)).mockResolvedValue(overview(true));
  const { result } = renderHook(() => useRestart(5, 1000));

  await act(() => result.current.restart());

  expect(result.current.phase).toBe("done");
  expect(api.getOverview).toHaveBeenCalledTimes(3);
});

test("a service that does not come back shows the log's last lines", async () => {
  vi.mocked(api.restartService).mockResolvedValue(service);
  vi.mocked(api.getOverview).mockResolvedValue(overview(false));
  vi.mocked(api.getLogs).mockResolvedValue({ file: "/s/dispatch.log", exists: true, lines: ["level=ERROR event=app.failed"] });
  const { result } = renderHook(() => useRestart(5, 30));

  await act(() => result.current.restart());

  expect(result.current.phase).toBe("failed");
  expect(result.current.error).toMatch(/not running again/);
  expect(result.current.lines).toEqual(["level=ERROR event=app.failed"]);
  expect(api.getLogs).toHaveBeenCalledWith({ lines: 20 }, expect.any(AbortSignal));
});

test("a refused restart says why", async () => {
  vi.mocked(api.restartService).mockRejectedValue(new api.ApiError("invalid", "Dispatch does not run as a background service here"));
  const { result } = renderHook(() => useRestart(5, 30));

  await act(() => result.current.restart());

  expect(result.current.phase).toBe("failed");
  expect(result.current.error).toBe("Dispatch does not run as a background service here");
});

test("unmounting mid-restart stops the poll", async () => {
  vi.useFakeTimers();
  try {
    vi.mocked(api.restartService).mockResolvedValue(service);
    vi.mocked(api.getOverview).mockResolvedValue(overview(false));
    const { result, unmount } = renderHook(() => useRestart(10, 10_000));

    let restarting!: Promise<void>;
    act(() => {
      restarting = result.current.restart();
    });
    await act(() => vi.advanceTimersByTimeAsync(10));
    expect(api.getOverview).toHaveBeenCalledTimes(1);

    unmount();
    const callsAtUnmount = vi.mocked(api.getOverview).mock.calls.length;
    await act(() => vi.advanceTimersByTimeAsync(10_000));
    await restarting;

    expect(api.getOverview).toHaveBeenCalledTimes(callsAtUnmount);
  } finally {
    vi.useRealTimers();
  }
});

test("a second restart while one is running does not start another", async () => {
  vi.useFakeTimers();
  try {
    vi.mocked(api.restartService).mockResolvedValue(service);
    vi.mocked(api.getOverview).mockResolvedValue(overview(true));
    const { result } = renderHook(() => useRestart(10, 10_000));

    let first!: Promise<void>;
    let second!: Promise<void>;
    act(() => {
      first = result.current.restart();
      second = result.current.restart();
    });
    await act(() => vi.advanceTimersByTimeAsync(10));
    await Promise.all([first, second]);

    expect(api.restartService).toHaveBeenCalledTimes(1);
  } finally {
    vi.useRealTimers();
  }
});
