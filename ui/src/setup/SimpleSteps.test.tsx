import { fireEvent, render, screen } from "@testing-library/react";
import { afterEach, expect, test, vi } from "vitest";
import * as api from "../api";
import BotStep from "./BotStep";
import CommitsStep from "./CommitsStep";
import WhoStep from "./WhoStep";
import type { Draft } from "./SetupPage";

// The real module, with the calls this file answers itself; ApiError stays the real class.
vi.mock("../api", async (importOriginal) => ({
  ...(await importOriginal<typeof import("../api")>()),
  chooseTeam: vi.fn(),
  checkToken: vi.fn(),
}));

const state: api.SetupState = {
  configExists: false,
  configFile: "/home/bold/.config/dispatch/dispatch.yaml",
  team: false,
  bot: null,
  members: [],
  candidate: null,
  group: null,
  claudeFound: "/usr/local/bin/claude",
  authorEmail: "bold@example.com",
  hints: [],
  hintAfterSeconds: 20,
};
const draft: Draft = { claude: "", projects: [], authorName: "", authorEmail: "", teamName: "" };

afterEach(() => vi.resetAllMocks());

test("choosing a team tells the server and moves on", async () => {
  const chooseTeam = vi.mocked(api.chooseTeam).mockResolvedValue({ ...state, team: true });
  const next = vi.fn();

  render(<WhoStep state={state} next={next} />);
  fireEvent.click(screen.getByLabelText(/My team/));
  fireEvent.click(screen.getByRole("button", { name: "Next" }));

  await vi.waitFor(() => expect(next).toHaveBeenCalled());
  expect(chooseTeam).toHaveBeenCalledWith(true);
});

test("a refused token shows why and does not move on", async () => {
  vi.mocked(api.checkToken).mockRejectedValue(new api.ApiError("invalid", "Telegram refused that token or could not be reached (401)"));
  const next = vi.fn();

  render(<BotStep state={state} next={next} back={vi.fn()} />);
  fireEvent.change(screen.getByLabelText("Bot token"), { target: { value: "123:abc" } });
  fireEvent.click(screen.getByRole("button", { name: "Check" }));

  expect(await screen.findByText(/Telegram refused that token/)).toBeInTheDocument();
  expect(next).not.toHaveBeenCalled();
});

test("a checked token shows the bot and the topics tip", async () => {
  vi.mocked(api.checkToken).mockResolvedValue({ username: "acme_bot", topicsEnabled: false });

  render(<BotStep state={state} next={vi.fn()} back={vi.fn()} />);
  fireEvent.change(screen.getByLabelText("Bot token"), { target: { value: "123:abc" } });
  fireEvent.click(screen.getByRole("button", { name: "Check" }));

  expect(await screen.findByText("@acme_bot")).toBeInTheDocument();
  expect(screen.getByText(/turn on topics/)).toBeInTheDocument();
});

test("a bot already checked by the server needs no token to move on", async () => {
  const next = vi.fn();

  render(<BotStep state={{ ...state, bot: { username: "acme_bot", topicsEnabled: true } }} next={next} back={vi.fn()} />);

  expect(await screen.findByText("@acme_bot")).toBeInTheDocument();
  expect(screen.getByRole("button", { name: "Next" })).toBeEnabled();
  expect(api.checkToken).not.toHaveBeenCalled();
});

test("the commit author is prefilled and required", async () => {
  const update = vi.fn();
  const next = vi.fn();

  render(<CommitsStep state={{ ...state, members: [{ id: 100, name: "Bold Bat" }] }} draft={draft} update={update} next={next} back={vi.fn()} />);

  expect(screen.getByLabelText("Author name")).toHaveValue("Dispatch (Bold)");
  expect(screen.getByLabelText("Author email")).toHaveValue("bold@example.com");
  fireEvent.change(screen.getByLabelText("Author email"), { target: { value: "" } });
  fireEvent.click(screen.getByRole("button", { name: "Next" }));
  expect(await screen.findByText("Both are needed.")).toBeInTheDocument();
  expect(next).not.toHaveBeenCalled();
});

test("the commits step's Advanced section is closed, and what is typed there joins the draft", async () => {
  const update = vi.fn();

  render(<CommitsStep state={{ ...state, members: [{ id: 100, name: "Bold Bat" }] }} draft={draft} update={update} next={vi.fn()} back={vi.fn()} />);
  expect(screen.queryByLabelText("Planning timeout per run")).not.toBeInTheDocument();
  fireEvent.click(screen.getByText("Advanced"));
  fireEvent.change(await screen.findByLabelText("Planning timeout per run"), { target: { value: "30m" } });
  fireEvent.change(screen.getByLabelText("GitHub CLI command"), { target: { value: " /opt/gh/bin/gh " } });
  fireEvent.click(screen.getByRole("button", { name: "Next" }));

  await vi.waitFor(() => expect(update).toHaveBeenCalledWith({
    authorName: "Dispatch (Bold)", authorEmail: "bold@example.com", advanced: { planTimeout: "30m", ghCommand: "/opt/gh/bin/gh" },
  }));
});
