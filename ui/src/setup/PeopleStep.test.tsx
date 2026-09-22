import { act, fireEvent, render, screen } from "@testing-library/react";
import { afterEach, expect, test, vi } from "vitest";
import * as api from "../api";
import PeopleStep from "./PeopleStep";
import type { Draft } from "./SetupPage";

// The real module, with the calls this file answers itself; ApiError stays the real class.
vi.mock("../api", async (importOriginal) => ({
  ...(await importOriginal<typeof import("../api")>()),
  nextPerson: vi.fn(),
  answerPerson: vi.fn(),
  nextGroup: vi.fn(),
  stopService: vi.fn(),
}));

const state: api.SetupState = {
  configExists: false, configFile: "/x/dispatch.yaml", team: false, bot: { username: "acme_bot", topicsEnabled: true },
  members: [], candidate: null, group: null, claudeFound: null, authorEmail: null,
  hints: ["one tick on the message means Telegram has not delivered it to the bot"], hintAfterSeconds: 20,
};
const draft: Draft = { claude: "", projects: [], authorName: "", authorEmail: "", teamName: "" };
const props = { draft, update: vi.fn(), next: vi.fn(), back: vi.fn() };

afterEach(() => {
  vi.resetAllMocks();
  vi.useRealTimers();
});

test("whoever writes to the bot is confirmed with a click", async () => {
  vi.mocked(api.nextPerson).mockResolvedValue({ candidate: { id: 100, name: "Bold" } });
  const answer = vi.mocked(api.answerPerson).mockResolvedValue({ ...state, members: [{ id: 100, name: "Bold" }] });
  const refresh = vi.fn().mockResolvedValue(undefined);

  render(<PeopleStep state={state} refresh={refresh} {...props} />);

  expect(await screen.findByText("Is Bold you?")).toBeInTheDocument();
  expect(screen.getByRole("link", { name: /t.me\/acme_bot/ })).toHaveAttribute("href", "https://t.me/acme_bot");
  fireEvent.click(screen.getByRole("button", { name: "That's me" }));
  await vi.waitFor(() => expect(answer).toHaveBeenCalledWith(100, true));
  expect(refresh).toHaveBeenCalled();
});

test("a quiet wait shows what to check", async () => {
  vi.useFakeTimers({ shouldAdvanceTime: true });
  vi.mocked(api.nextPerson).mockReturnValue(new Promise(() => {}));

  render(<PeopleStep state={state} refresh={vi.fn()} {...props} />);
  await act(async () => {
    vi.advanceTimersByTime(20_000);
  });

  expect(screen.getByText(/one tick on the message/)).toBeInTheDocument();
});

test("another reader of the bot offers to stop the background service", async () => {
  vi.mocked(api.nextPerson).mockRejectedValue(new api.ApiError("conflict", "cannot read the bot's messages; stop it first"));
  const stop = vi.mocked(api.stopService).mockResolvedValue({ name: "s", installed: true, running: false, detail: "", notes: [] });

  render(<PeopleStep state={state} refresh={vi.fn()} {...props} />);

  fireEvent.click(await screen.findByRole("button", { name: "Stop the background service" }));
  await vi.waitFor(() => expect(stop).toHaveBeenCalled());
});
