import { act, fireEvent, render, screen, waitFor, within } from "@testing-library/react";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import * as api from "../api";
import {
  detailOf, myFinishedTask, myRunningTask, planWithQuestions, waitingOnApproval, waitingOnQuestion,
} from "./fixtures";
import HomePage from "./HomePage";
import { ago, clock, groupTickets, SERVED_SIZE } from "./tickets";

vi.mock("../api", async (importOriginal) => ({
  ...(await importOriginal<typeof import("../api")>()),
  listTasks: vi.fn(),
  getTaskDetail: vi.fn(),
  answerQuestion: vi.fn(),
  approvePlan: vi.fn(),
  rejectPlan: vi.fn(),
}));

const ME: api.Me = { ref: "telegram:100", name: "Bold", admin: false, bot: "dispatch_task_bot" };
const queued: api.TaskRow = { ...myRunningTask, taskId: 4, state: "queued", kind: "EXECUTE", queuedAt: "2026-09-23T09:55:00Z" };
const planning: api.TaskRow = { ...myRunningTask, taskId: 5, kind: "PLAN", lastAction: "Read src/auth.ts", steps: 7 };

type Bridge = {
  TelegramWebviewProxy?: { postEvent: (type: string, data: string) => void };
  Telegram: { WebView: { receiveEvent: (type: string, data: unknown) => void } };
};
const bridge = window as unknown as Bridge;

function renderHome(tasks: api.TaskRow[]) {
  vi.mocked(api.listTasks).mockResolvedValue({ tasks });
  return render(<HomePage me={ME} navigate={() => {}} />);
}

const dialog = () => screen.findByRole("dialog");

describe("grouping tickets", () => {
  it("puts my waiting tasks at the pass, longest waiting first; running and queued on the rail; the latest finished served", () => {
    const finished = Array.from({ length: SERVED_SIZE + 2 }, (_, index) => ({ ...myFinishedTask, taskId: 100 + index }));
    const theirs = { ...waitingOnApproval, taskId: 12, mine: false };

    const { pass, rail, served } = groupTickets([waitingOnApproval, myRunningTask, theirs, waitingOnQuestion, queued, ...finished]);

    expect(pass.map((task) => task.taskId)).toEqual([10, 11]);
    expect(rail.map((task) => task.taskId)).toEqual([1, 4]);
    expect(served.map((task) => task.taskId)).toEqual([100, 101, 102, 103, 104]);
  });
});

describe("clocks", () => {
  it("read as how long, in the same words", () => {
    const now = Date.parse("2026-09-23T10:00:00Z");
    expect(clock("2026-09-23T09:55:53Z", now)).toBe("4:07");
    expect(clock("2026-09-23T08:55:53Z", now)).toBe("1:04:07");
    expect(clock("2026-09-21T09:00:00Z", now)).toBe("2 өдөр");
    expect(ago("2026-09-21T09:00:00Z", now)).toBe("2 өдөр");
  });
});

describe("the home screen", () => {
  beforeEach(() => {
    bridge.TelegramWebviewProxy = { postEvent: vi.fn() };
    vi.mocked(api.getTaskDetail).mockResolvedValue(detailOf(waitingOnQuestion, planWithQuestions));
  });

  afterEach(() => {
    delete bridge.TelegramWebviewProxy;
    vi.resetAllMocks();
  });

  it("lists what waits on me, what is moving and what finished, each task with its state in words", async () => {
    renderHome([waitingOnQuestion, waitingOnApproval, planning, queued, myFinishedTask]);

    const waiting = await screen.findByRole("region", { name: "Таны шийдвэр" });
    expect(await within(waiting).findByRole("button", { name: /^Make the login timeout configurable/ })).toHaveTextContent("2 асуулт: Which environments?");
    expect(within(waiting).getByRole("button", { name: /^Add the CSV export/ })).toHaveTextContent("Төлөвлөгөө бэлэн");
    const moving = screen.getByRole("region", { name: "Явж байна" });
    expect(within(moving).getByText("7 · Read src/auth.ts")).toBeInTheDocument();
    expect(within(moving).getByText("дараалалд")).toBeInTheDocument();
    expect(within(screen.getByRole("region", { name: "Дууссан" })).getByText("хүргэсэн")).toBeInTheDocument();
    expect(screen.getByLabelText("2 даалгавар таныг хүлээж байна")).toBeInTheDocument();
    expect(api.listTasks).toHaveBeenCalledWith("me");
  });

  it("finds a task by its title, project or number", async () => {
    renderHome([waitingOnQuestion, waitingOnApproval]);
    await screen.findByRole("button", { name: /^Add the CSV export/ });

    fireEvent.change(screen.getByRole("textbox", { name: "Даалгавар хайх" }), { target: { value: "#10" } });

    expect(screen.queryByRole("button", { name: /^Add the CSV export/ })).not.toBeInTheDocument();
    expect(screen.getByRole("button", { name: /^Make the login timeout configurable/ })).toBeInTheDocument();
  });

  it("says in one line when nothing waits on me, and when nothing moves or was served", async () => {
    renderHome([]);

    expect(await screen.findByText("Таны шийдвэр хүлээсэн зүйл алга")).toBeInTheDocument();
    expect(screen.getByText("Одоо ажиллаж буй даалгавар алга")).toBeInTheDocument();
    expect(screen.getByText("Дууссан даалгавар алга")).toBeInTheDocument();
    expect(screen.getByLabelText("0 даалгавар таныг хүлээж байна")).toBeInTheDocument();
  });

  it("answers the questions in the sheet one at a time; the last answer closes the sheet and asks for the list again", async () => {
    const afterFirst = detailOf(waitingOnQuestion, {
      ...planWithQuestions,
      questions: [{ ...planWithQuestions.questions[0], answer: "prod" }, planWithQuestions.questions[1]],
    });
    vi.mocked(api.answerQuestion)
      .mockResolvedValueOnce({ ...afterFirst, result: "ANSWERED" })
      .mockResolvedValueOnce({ ...afterFirst, phase: "PLANNING", result: "ANSWERED" });
    renderHome([waitingOnQuestion]);

    fireEvent.click(await screen.findByRole("button", { name: /^Make the login timeout configurable/ }));
    const sheet = await dialog();
    expect(await within(sheet).findByText("Make the timeout a setting")).toBeInTheDocument();
    expect(within(sheet).getByRole("button", { name: "Зөвшөөрөх" })).toBeDisabled();
    fireEvent.click(within(sheet).getByRole("button", { name: "prod" }));

    await waitFor(() => expect(api.answerQuestion).toHaveBeenCalledWith(10, 1, 1, { option: 1 }));
    expect(await within(sheet).findByText("prod", { selector: "b" })).toBeInTheDocument();
    fireEvent.click(within(sheet).getByRole("button", { name: "🤷 Та шийд" }));

    await waitFor(() => expect(api.answerQuestion).toHaveBeenLastCalledWith(10, 1, 2, { decide: true }));
    await waitFor(() => expect(screen.queryByRole("dialog")).not.toBeInTheDocument());
    expect(bridge.TelegramWebviewProxy!.postEvent).toHaveBeenCalledWith("web_app_trigger_haptic_feedback",
      JSON.stringify({ type: "notification", notification_type: "success" }));
    await waitFor(() => expect(vi.mocked(api.listTasks).mock.calls.length).toBeGreaterThan(1));
  });

  it("sends my own words as the answer", async () => {
    vi.mocked(api.answerQuestion).mockResolvedValue({ ...detailOf(waitingOnQuestion, planWithQuestions), result: "ANSWERED" });
    renderHome([waitingOnQuestion]);

    fireEvent.click(await screen.findByRole("button", { name: /^Make the login timeout configurable/ }));
    const sheet = await dialog();
    fireEvent.click(await within(sheet).findByRole("button", { name: "✍️ Өөрөөр" }));
    fireEvent.change(within(sheet).getByRole("textbox", { name: "Өөрийн хариулт" }), { target: { value: " only staging " } });
    fireEvent.click(within(sheet).getByRole("button", { name: "Илгээх" }));

    await waitFor(() => expect(api.answerQuestion).toHaveBeenCalledWith(10, 1, 1, { text: "only staging" }));
  });

  it("approves a plan with no open questions", async () => {
    vi.mocked(api.getTaskDetail).mockResolvedValue(detailOf(waitingOnApproval, { ...planWithQuestions, planSeq: 2, questions: [] }));
    vi.mocked(api.approvePlan).mockResolvedValue({ result: "APPROVED" });
    renderHome([waitingOnApproval]);

    fireEvent.click(await screen.findByRole("button", { name: /^Add the CSV export/ }));
    const sheet = await dialog();
    await within(sheet).findByText("Default to 30 minutes");
    fireEvent.click(within(sheet).getByRole("button", { name: "Зөвшөөрөх" }));

    await waitFor(() => expect(api.approvePlan).toHaveBeenCalledWith(11, 2));
    await waitFor(() => expect(screen.queryByRole("dialog")).not.toBeInTheDocument());
  });

  it("rejects only after one more tap", async () => {
    vi.mocked(api.getTaskDetail).mockResolvedValue(detailOf(waitingOnApproval, { ...planWithQuestions, planSeq: 2, questions: [] }));
    vi.mocked(api.rejectPlan).mockResolvedValue({ result: "REJECTED" });
    renderHome([waitingOnApproval]);

    fireEvent.click(await screen.findByRole("button", { name: /^Add the CSV export/ }));
    const sheet = await dialog();
    fireEvent.click(await within(sheet).findByRole("button", { name: "Татгалзах" }));
    expect(api.rejectPlan).not.toHaveBeenCalled();
    fireEvent.click(within(sheet).getByRole("button", { name: "Тийм, татгалзах" }));

    await waitFor(() => expect(api.rejectPlan).toHaveBeenCalledWith(11, 2));
  });

  it("says why an action was refused and keeps the sheet open", async () => {
    vi.mocked(api.answerQuestion).mockRejectedValue(new api.ApiError("stale", "this plan was replaced by a newer one"));
    renderHome([waitingOnQuestion]);

    fireEvent.click(await screen.findByRole("button", { name: /^Make the login timeout configurable/ }));
    const sheet = await dialog();
    fireEvent.click(await within(sheet).findByRole("button", { name: "staging" }));

    expect(await within(sheet).findByRole("alert")).toHaveTextContent("replaced by a newer one");
    expect(screen.getByRole("dialog")).toBeInTheDocument();
  });

  it("closes the sheet with Telegram's Back button", async () => {
    renderHome([waitingOnQuestion]);
    fireEvent.click(await screen.findByRole("button", { name: /^Make the login timeout configurable/ }));
    await dialog();

    act(() => bridge.Telegram.WebView.receiveEvent("back_button_pressed", null));

    await waitFor(() => expect(screen.queryByRole("dialog")).not.toBeInTheDocument());
    expect(screen.getByRole("button", { name: /^Make the login timeout configurable/ })).toBeInTheDocument();
  });
});
