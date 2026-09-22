import { fireEvent, render, screen } from "@testing-library/react";
import { afterEach, expect, test, vi } from "vitest";
import * as api from "../api";
import LogsPage from "./LogsPage";

// The real module, with the calls this file answers itself; ApiError stays the real class.
vi.mock("../api", async (importOriginal) => ({
  ...(await importOriginal<typeof import("../api")>()),
  getLogs: vi.fn(),
}));

afterEach(() => vi.resetAllMocks());

const file = "/home/bold/.local/state/dispatch/dispatch.log";

test("the log is asked for again after each answer, with the chosen level, until the page closes", async () => {
  const logs = vi.mocked(api.getLogs).mockResolvedValue({ file, exists: true, lines: ["ts=1 level=WARN event=telegram.poll_failed"] });

  const page = render(<LogsPage intervalMs={20} />);
  expect(await screen.findByText("ts=1 level=WARN event=telegram.poll_failed")).toBeInTheDocument();
  await vi.waitFor(() => expect(logs.mock.calls.length).toBeGreaterThanOrEqual(2));
  fireEvent.click(screen.getByText("WARN"));
  await vi.waitFor(() => expect(logs).toHaveBeenLastCalledWith({ lines: 200, level: "WARN", event: null }));
  page.unmount();
  const asked = logs.mock.calls.length;
  await new Promise((resolve) => setTimeout(resolve, 100));

  expect(logs.mock.calls.length).toBe(asked);
});

test("before the service has written its log, the page says so", async () => {
  vi.mocked(api.getLogs).mockResolvedValue({ file, exists: false, lines: [] });

  render(<LogsPage intervalMs={1000} />);

  expect(await screen.findByText(/No log yet/)).toBeInTheDocument();
});
