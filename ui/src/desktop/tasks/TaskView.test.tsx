import { fireEvent, render, screen } from "@testing-library/react";
import { afterEach, expect, test, vi } from "vitest";
import * as api from "../../api";
import type { TaskDetail } from "../../api";
import TaskView from "./TaskView";

vi.mock("../../api", async (importOriginal) => ({
  ...(await importOriginal<typeof import("../../api")>()),
  getTaskDetail: vi.fn(),
  approvePlan: vi.fn(),
  rejectPlan: vi.fn(),
  answerQuestion: vi.fn(),
  cancelTask: vi.fn(),
  retryTask: vi.fn(),
  correctPlan: vi.fn(),
}));

afterEach(() => vi.resetAllMocks());

const waiting: TaskDetail = {
  taskId: 14, project: "crm", title: "Fix the login timeout", phase: "AWAITING_APPROVAL", prUrl: null, failureReason: null,
  createdAt: "2026-09-28T06:22:00Z", completedAt: null, costUsd: "0.42", requester: "Bold", priority: "URGENT",
  actions: ["approve", "correct", "reject", "priority", "cancel"],
  plan: { planSeq: 1, current: 0, understanding: "Make the timeout configurable", steps: ["Read auth.timeout", "Add a test"],
    risks: [], findings: [], questions: [] },
};

test("your own waiting plan shows its steps and is approved from here", async () => {
  vi.mocked(api.getTaskDetail).mockResolvedValue(waiting);
  vi.mocked(api.approvePlan).mockResolvedValue({ result: "APPROVED" });
  const changed = vi.fn();

  render(<TaskView taskId={14} onChanged={changed} />);
  expect(await screen.findByText("Read auth.timeout")).toBeInTheDocument();
  expect(screen.getByRole("button", { name: "Write a correction" })).toBeInTheDocument();
  expect(screen.getByRole("button", { name: "Reject" })).toBeInTheDocument();
  fireEvent.click(screen.getByRole("button", { name: "Approve" }));

  await vi.waitFor(() => expect(api.approvePlan).toHaveBeenCalledWith(14, 1));
  await vi.waitFor(() => expect(changed).toHaveBeenCalled());
});

test("a correction is written here and sent against the plan it was written for", async () => {
  vi.mocked(api.getTaskDetail).mockResolvedValue(waiting);
  vi.mocked(api.correctPlan).mockResolvedValue({ result: "CORRECTED" });

  render(<TaskView taskId={14} />);
  fireEvent.click(await screen.findByRole("button", { name: "Write a correction" }));
  fireEvent.change(screen.getByLabelText("What should change"), { target: { value: "use 60 s" } });
  fireEvent.click(screen.getByRole("button", { name: "Send" }));

  await vi.waitFor(() => expect(api.correctPlan).toHaveBeenCalledWith(14, 1, "use 60 s"));
});

test("an open question offers its choices, your own answer and 'you decide'", async () => {
  vi.mocked(api.getTaskDetail).mockResolvedValue({ ...waiting, actions: ["correct", "answer", "reject", "priority", "cancel"],
    plan: { ...waiting.plan!, current: 1, questions: [{ index: 1, text: "How many seconds?", options: ["30", "60"], answer: null }] } });
  vi.mocked(api.answerQuestion).mockResolvedValue({ ...waiting, result: "ANSWERED" });

  render(<TaskView taskId={14} />);
  expect(await screen.findByText("How many seconds?")).toBeInTheDocument();
  expect(screen.getByText("Question 1 of 1")).toBeInTheDocument();
  expect(screen.getByRole("button", { name: "You decide" })).toBeInTheDocument();
  expect(screen.getByLabelText("Your own answer")).toBeInTheDocument();
  expect(screen.queryByRole("button", { name: "Approve" })).not.toBeInTheDocument();
  fireEvent.click(screen.getByRole("button", { name: "30" }));

  await vi.waitFor(() => expect(api.answerQuestion).toHaveBeenCalledWith(14, 1, 1, { option: 0 }));
});

test("someone else's running task offers only its cancel", async () => {
  vi.mocked(api.getTaskDetail).mockResolvedValue({ ...waiting, phase: "EXECUTING", requester: "Ali", actions: ["cancel"], plan: undefined });

  render(<TaskView taskId={14} />);

  expect(await screen.findByRole("button", { name: "Cancel the task" })).toBeInTheDocument();
  for (const name of ["Approve", "Write a correction", "Reject", "Retry"]) {
    expect(screen.queryByRole("button", { name })).not.toBeInTheDocument();
  }
});

test("a refusal on a view gone stale says why and reads the task again", async () => {
  vi.mocked(api.getTaskDetail).mockResolvedValue(waiting);
  vi.mocked(api.approvePlan).mockRejectedValue(new api.ApiError("stale", "that plan was replaced by a newer one"));

  render(<TaskView taskId={14} />);
  fireEvent.click(await screen.findByRole("button", { name: "Approve" }));

  expect(await screen.findByText("that plan was replaced by a newer one")).toBeInTheDocument();
  await vi.waitFor(() => expect(api.getTaskDetail).toHaveBeenCalledTimes(2));
});
