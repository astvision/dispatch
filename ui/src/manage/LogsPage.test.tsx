import { fireEvent, render, screen } from "@testing-library/react";
import { afterEach, expect, test, vi } from "vitest";
import * as api from "../api";
import LogsPage from "./LogsPage";

// The real module, with the calls this file answers itself; ApiError stays the real class.
vi.mock("../api", async (importOriginal) => ({
  ...(await importOriginal<typeof import("../api")>()),
  getLogs: vi.fn(),
}));

afterEach(() => {
  vi.useRealTimers();
  vi.resetAllMocks();
});

const file = "/home/bold/.local/state/dispatch/dispatch.log";
const everything = { lines: 200, level: null, event: null, task: null, text: null };

test("the log is asked for again after each answer, with the chosen level, until the page closes", async () => {
  const logs = vi.mocked(api.getLogs).mockResolvedValue({ file, exists: true, lines: ["ts=1 level=WARN event=telegram.poll_failed"] });

  const page = render(<LogsPage intervalMs={20} />);
  expect(await screen.findByText("telegram.poll_failed")).toBeInTheDocument();
  await vi.waitFor(() => expect(logs.mock.calls.length).toBeGreaterThanOrEqual(2));
  fireEvent.click(screen.getByText("WARN"));
  await vi.waitFor(() => expect(logs).toHaveBeenLastCalledWith({ ...everything, level: "WARN" }));
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

test("each line is a row, newest first: a warning tinted, a stack trace under the line it belongs to", async () => {
  vi.mocked(api.getLogs).mockResolvedValue({ file, exists: true, lines: [
    "ts=2026-09-28T06:25:20.000Z level=INFO event=run.started task=12 run=1",
    'ts=2026-09-28T06:25:24.949Z level=WARN event=outbox.failed task=12 error="Bad Request: x"',
    "\tat dispatch.core.Coordinator.execute(Coordinator.java:60)",
  ] });

  render(<LogsPage intervalMs={1000} />);

  const warning = (await screen.findByText("outbox.failed")).closest(".log-entry")!;
  expect(warning).toHaveClass("amber");
  expect(warning).toHaveTextContent("Bad Request: x");
  expect(warning).toHaveTextContent("Coordinator.java:60");
  expect(screen.getByText("warning")).toHaveClass("sr-only");
  const rows = [...document.querySelectorAll(".log-entry")].map((row) => row.textContent);
  expect(rows[0]).toContain("outbox.failed");
  expect(rows[1]).toContain("run.started");
});

test("a task number, an event and any text narrow what is asked for", async () => {
  const logs = vi.mocked(api.getLogs).mockResolvedValue({ file, exists: true, lines: [] });

  render(<LogsPage intervalMs={1000} />);
  fireEvent.change(await screen.findByLabelText("Task"), { target: { value: "12" } });
  await vi.waitFor(() => expect(logs).toHaveBeenLastCalledWith({ ...everything, task: 12 }));
  fireEvent.change(screen.getByLabelText("Event"), { target: { value: "run.*" } });
  fireEvent.keyDown(screen.getByLabelText("Event"), { key: "Enter", code: "Enter", keyCode: 13 });

  await vi.waitFor(() => expect(logs).toHaveBeenLastCalledWith({ ...everything, task: 12, event: "run." }));
  fireEvent.change(screen.getByLabelText("Search"), { target: { value: "timeout" } });
  fireEvent.keyDown(screen.getByLabelText("Search"), { key: "Enter", code: "Enter", keyCode: 13 });

  await vi.waitFor(() => expect(logs).toHaveBeenLastCalledWith({ ...everything, task: 12, event: "run.", text: "timeout" }));
});

test("with Follow off the log is not read again", async () => {
  vi.useFakeTimers();
  const logs = vi.mocked(api.getLogs).mockResolvedValue({ file, exists: true, lines: [] });

  render(<LogsPage intervalMs={1000} />);
  await vi.advanceTimersByTimeAsync(2500);
  expect(logs.mock.calls.length).toBeGreaterThanOrEqual(2);
  fireEvent.click(screen.getByRole("switch", { name: "Follow" }));
  await vi.advanceTimersByTimeAsync(0);
  const asked = logs.mock.calls.length;
  await vi.advanceTimersByTimeAsync(10_000);

  expect(logs.mock.calls.length).toBe(asked);
});

test("on the desktop a row's task number opens that task's page", async () => {
  vi.mocked(api.getLogs).mockResolvedValue({ file, exists: true, lines: ["ts=2026-09-28T06:25:20.000Z level=INFO event=run.started task=14 run=1"] });
  const navigate = vi.fn();

  render(<LogsPage intervalMs={1000} navigate={navigate} />);
  fireEvent.click(await screen.findByRole("link", { name: "#14" }));

  expect(navigate).toHaveBeenCalledWith("/tasks/14");
});
