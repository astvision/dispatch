import { fireEvent, render, screen, waitFor } from "@testing-library/react";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import * as api from "../api";
import { headlineTimeline, myFinishedTask, myRunningTask, myTimeline, someoneElsesTask } from "./fixtures";
import TasksPage from "./TasksPage";

vi.mock("../api", async (importOriginal) => ({
  ...(await importOriginal<typeof import("../api")>()),
  listTasks: vi.fn(),
  taskTimeline: vi.fn(),
  cancelTask: vi.fn(),
  retryTask: vi.fn(),
}));

describe("the task pages", () => {
  beforeEach(() => {
    vi.mocked(api.taskTimeline).mockResolvedValue(myTimeline);
  });

  afterEach(() => vi.resetAllMocks());

  it("lists my tasks with their state", async () => {
    vi.mocked(api.listTasks).mockResolvedValue({ tasks: [myRunningTask, myFinishedTask] });

    render(<TasksPage scope="me" />);

    expect(await screen.findByText(/Fix the login timeout/)).toBeInTheDocument();
    expect(screen.getByText("Ажиллаж байна")).toBeInTheDocument();
    expect(screen.getByText("Дууссан")).toBeInTheDocument();
    expect(api.listTasks).toHaveBeenCalledWith("me");
  });

  it("asks before cancelling, then cancels and reloads", async () => {
    vi.mocked(api.listTasks).mockResolvedValue({ tasks: [myRunningTask] });
    vi.mocked(api.cancelTask).mockResolvedValue({ result: "CANCELLED" });

    render(<TasksPage scope="me" />);
    fireEvent.click(await screen.findByRole("button", { name: "Цуцлах 1" }));

    expect(api.cancelTask).not.toHaveBeenCalled();
    fireEvent.click(screen.getByRole("button", { name: "Цуцлах" }));

    await waitFor(() => expect(api.cancelTask).toHaveBeenCalledWith(1));
    await waitFor(() => expect(vi.mocked(api.listTasks).mock.calls.length).toBeGreaterThan(1));
  });

  it("retries a finished task of mine without asking", async () => {
    vi.mocked(api.listTasks).mockResolvedValue({ tasks: [myFinishedTask] });
    vi.mocked(api.retryTask).mockResolvedValue({ result: "RETRIED" });

    render(<TasksPage scope="me" />);
    fireEvent.click(await screen.findByRole("button", { name: "Дахин эхлүүлэх 2" }));

    await waitFor(() => expect(api.retryTask).toHaveBeenCalledWith(2));
  });

  it("lets an admin cancel someone else's task but never retry it", async () => {
    vi.mocked(api.listTasks).mockResolvedValue({ tasks: [someoneElsesTask] });

    render(<TasksPage scope="group" />);

    expect(await screen.findByRole("button", { name: "Цуцлах 3" })).toBeInTheDocument();
    expect(screen.queryByRole("button", { name: "Дахин эхлүүлэх 3" })).not.toBeInTheDocument();
    expect(screen.getByText(/Ali/)).toBeInTheDocument();
    expect(api.listTasks).toHaveBeenCalledWith("group");
  });

  it("opens a task into its runs, and stops at the headline for someone else's", async () => {
    vi.mocked(api.listTasks).mockResolvedValue({ tasks: [myFinishedTask] });

    render(<TasksPage scope="me" />);
    fireEvent.click(await screen.findByText(/Add the export button/));

    expect(await screen.findByText(/#1 · PLAN · SUCCEEDED/)).toBeInTheDocument();
    expect(screen.getByRole("link", { name: "Pull request" })).toBeInTheDocument();
  });

  it("shows someone else's task as a headline and no runs", async () => {
    vi.mocked(api.listTasks).mockResolvedValue({ tasks: [someoneElsesTask] });
    vi.mocked(api.taskTimeline).mockResolvedValue(headlineTimeline);

    render(<TasksPage scope="group" />);
    fireEvent.click(await screen.findByText(/Rename the settings page/));

    expect(await screen.findByText(/гарчгаас цаашгүй/)).toBeInTheDocument();
  });

  it("keeps the list when an action fails and says why", async () => {
    vi.mocked(api.listTasks).mockResolvedValue({ tasks: [myRunningTask] });
    vi.mocked(api.cancelTask).mockRejectedValue(new api.ApiError("not_yours", "only the member who gave this task"));

    render(<TasksPage scope="me" />);
    fireEvent.click(await screen.findByRole("button", { name: "Цуцлах 1" }));
    fireEvent.click(screen.getByRole("button", { name: "Цуцлах" }));

    expect(await screen.findByText(/only the member who gave this task/)).toBeInTheDocument();
    expect(screen.getByText(/Fix the login timeout/)).toBeInTheDocument();
  });

  it("says so when there is nothing to show", async () => {
    vi.mocked(api.listTasks).mockResolvedValue({ tasks: [] });

    render(<TasksPage scope="me" />);

    expect(await screen.findByText("Одоогоор даалгавар алга.")).toBeInTheDocument();
  });
});
