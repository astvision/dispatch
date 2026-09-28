import { fireEvent, render, screen } from "@testing-library/react";
import { afterEach, expect, test, vi } from "vitest";
import * as api from "../../api";
import GiveTask from "./GiveTask";

vi.mock("../../api", async (importOriginal) => ({ ...(await importOriginal<typeof import("../../api")>()), giveTask: vi.fn() }));

afterEach(() => vi.resetAllMocks());

test("gives the task of its one project, words and priority, once however often it is clicked", async () => {
  let answer: (given: { taskId: number }) => void = () => {};
  vi.mocked(api.giveTask).mockImplementation(() => new Promise((done) => { answer = done; }));
  const given = vi.fn();
  render(<GiveTask projects={["alm"]} onGiven={given} />);

  fireEvent.change(screen.getByRole("textbox", { name: "Task" }), { target: { value: "Fix the login timeout" } });
  fireEvent.click(screen.getByRole("radio", { name: "🔴 urgent" }));
  const give = screen.getByRole("button", { name: "Give" });
  fireEvent.click(give);
  fireEvent.click(give);
  answer({ taskId: 12 });

  await vi.waitFor(() => expect(given).toHaveBeenCalledWith(12));
  expect(api.giveTask).toHaveBeenCalledTimes(1);
  expect(api.giveTask).toHaveBeenCalledWith("alm", "Fix the login timeout", "URGENT");
});

test("a refusal shows in the panel, and nothing opens", async () => {
  vi.mocked(api.giveTask).mockRejectedValue(new api.ApiError("project_unavailable", "the project alm cannot take tasks now: no clone"));
  const given = vi.fn();
  render(<GiveTask projects={["alm"]} onGiven={given} />);

  fireEvent.change(screen.getByRole("textbox", { name: "Task" }), { target: { value: "Fix it" } });
  fireEvent.click(screen.getByRole("button", { name: "Give" }));

  expect(await screen.findByText("the project alm cannot take tasks now: no clone")).toBeInTheDocument();
  expect(given).not.toHaveBeenCalled();
});

test("with no project to give in, the panel says so", () => {
  render(<GiveTask projects={[]} onGiven={vi.fn()} />);

  expect(screen.getByText("None of your groups has a project to give a task in.")).toBeInTheDocument();
});
