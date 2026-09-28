import { fireEvent, render, screen, within } from "@testing-library/react";
import { resetWarned } from "@rc-component/util";
import { afterEach, expect, test, vi } from "vitest";
import * as api from "../../api";
import type { TaskDetail, TaskRow } from "../../api";
import { LanguageProvider } from "../../i18n/i18n";
import TasksPage from "./TasksPage";

vi.mock("../../api", async (importOriginal) => ({
  ...(await importOriginal<typeof import("../../api")>()),
  listTasks: vi.fn(),
  getTaskDetail: vi.fn(),
}));

afterEach(() => vi.resetAllMocks());

const row = (over: Partial<TaskRow>): TaskRow => ({ taskId: 1, project: "crm", title: "Fix it", state: "running",
  priority: "NORMAL", requester: "Bold", mine: true, actions: [], ...over });
const rows: TaskRow[] = [
  row({ taskId: 14, title: "Fix the login timeout", state: "awaitingApproval", since: "2026-09-28T06:25:00Z" }),
  row({ taskId: 13, title: "PDF export", project: "alm", requester: "Ali", mine: false, startedAt: "2026-09-28T06:10:00Z" }),
  row({ taskId: 12, title: "README", state: "queued", queuedAt: "2026-09-28T06:00:00Z" }),
  row({ taskId: 15, title: "Excel import", state: "awaitingApproval", mine: false, requester: "Ali" }),
  row({ taskId: 11, title: "Reset e-mail", state: "finished", phase: "COMPLETED", completedAt: new Date().toISOString() }),
];
const detail: TaskDetail = { taskId: 14, project: "crm", title: "Fix the login timeout", phase: "AWAITING_APPROVAL", prUrl: null,
  failureReason: null, createdAt: null, completedAt: null, costUsd: null, actions: ["cancel"] };

test("the tasks come grouped by what they need, in order", async () => {
  vi.mocked(api.listTasks).mockResolvedValue({ tasks: rows });

  render(<TasksPage navigate={vi.fn()} />);

  expect(await screen.findByText("Fix the login timeout")).toBeInTheDocument();
  const headings = screen.getAllByRole("heading", { level: 5 }).map((heading) => heading.textContent);
  expect(headings).toEqual(["Waiting on you", "Running", "Queued", "Waiting on someone else", "Finished, last 30 days"]);
});

test("a row opens the task in the side panel, whose Details opens its page", async () => {
  vi.mocked(api.listTasks).mockResolvedValue({ tasks: rows });
  vi.mocked(api.getTaskDetail).mockResolvedValue(detail);
  const navigate = vi.fn();

  render(<TasksPage navigate={navigate} />);
  fireEvent.click(await screen.findByText("Fix the login timeout"));
  const panel = await screen.findByRole("dialog");
  expect(await within(panel).findByText("#14 Fix the login timeout")).toBeInTheDocument();
  fireEvent.click(within(panel).getByRole("button", { name: /Details/ }));

  expect(navigate).toHaveBeenCalledWith("/tasks/14");
});

test("in Mongolian the groups are Mongolian", async () => {
  vi.mocked(api.listTasks).mockResolvedValue({ tasks: rows });

  render(<LanguageProvider storage={{ getItem: () => null, setItem: () => {} }} languages={["mn"]}><TasksPage navigate={vi.fn()} /></LanguageProvider>);

  for (const group of ["Таныг хүлээж", "Явж байна", "Дараалалд", "Бусдыг хүлээж"]) {
    expect(await screen.findByRole("heading", { name: group })).toBeInTheDocument();
  }
});

test("without the bot the page says so and leads to the overview", async () => {
  vi.mocked(api.listTasks).mockRejectedValue(new api.ApiError("bot_not_running", "The bot is not running"));
  const navigate = vi.fn();

  render(<TasksPage navigate={navigate} />);
  expect(await screen.findByText("The bot is not running: tasks show while it runs.")).toBeInTheDocument();
  fireEvent.click(screen.getByRole("button", { name: "Open the overview" }));

  expect(navigate).toHaveBeenCalledWith("/");
});

test("the list's filters give antd nothing to warn about", async () => {
  // antd warns once per message: the earlier tests' pages would have used it up.
  resetWarned();
  const warned = vi.spyOn(console, "error").mockImplementation(() => {});
  vi.mocked(api.listTasks).mockResolvedValue({ tasks: rows });
  render(<TasksPage navigate={vi.fn()} />);
  await screen.findAllByRole("button", { name: /#\d+/ });

  expect(warned.mock.calls.flat().join(" ")).not.toMatch(/should not be `null`/);
  warned.mockRestore();
});
