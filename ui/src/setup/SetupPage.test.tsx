import { render, screen } from "@testing-library/react";
import { afterEach, expect, test, vi } from "vitest";
import * as api from "../api";
import { LanguageProvider } from "../i18n/i18n";
import SetupPage from "./SetupPage";

// The real module, with the calls this file answers itself; ApiError stays the real class.
vi.mock("../api", async (importOriginal) => ({
  ...(await importOriginal<typeof import("../api")>()),
  getSetupState: vi.fn(),
  nextPerson: vi.fn(),
}));

afterEach(() => vi.resetAllMocks());

test("resumes at the Claude Code step when a personal setup already has its one member", async () => {
  vi.mocked(api.getSetupState).mockResolvedValue({
    configExists: false,
    configFile: "/home/bold/.config/dispatch/dispatch.yaml",
    team: false,
    bot: { username: "acme_bot", topicsEnabled: true },
    members: [{ id: 1, name: "Bold Bat" }],
    candidate: null,
    group: null,
    claudeFound: "/usr/local/bin/claude",
    authorEmail: "bold@example.com",
    hints: [],
    hintAfterSeconds: 20,
  });

  render(<SetupPage onDone={vi.fn()} />);

  expect(await screen.findByLabelText("claude command")).toBeInTheDocument();
});

test("resumes at the People step when a team setup already has members, so the team name can be set", async () => {
  vi.mocked(api.getSetupState).mockResolvedValue({
    configExists: false,
    configFile: "/home/bold/.config/dispatch/dispatch.yaml",
    team: true,
    bot: { username: "acme_bot", topicsEnabled: true },
    members: [{ id: 1, name: "Bold Bat" }],
    candidate: null,
    group: null,
    claudeFound: "/usr/local/bin/claude",
    authorEmail: "bold@example.com",
    hints: [],
    hintAfterSeconds: 20,
  });
  vi.mocked(api.nextPerson).mockReturnValue(new Promise(() => {})); // the People step polls; leave it hanging

  render(<SetupPage onDone={vi.fn()} />);

  expect(await screen.findByLabelText("Team name")).toBeInTheDocument();
});

test("in Mongolian the wizard, its steps and its choices are Mongolian", async () => {
  vi.mocked(api.getSetupState).mockResolvedValue({
    configExists: false, configFile: "/home/bold/.config/dispatch/dispatch.yaml", team: false, bot: null, members: [],
    candidate: null, group: null, claudeFound: null, authorEmail: null, hints: [], hintAfterSeconds: 20,
  });

  render(<LanguageProvider storage={{ getItem: () => null, setItem: () => {} }} languages={["mn"]}><SetupPage onDone={vi.fn()} /></LanguageProvider>);

  expect(await screen.findByText("Dispatch-ийг тохируулах")).toBeInTheDocument();
  for (const step of ["Хэн", "Бот", "Та", "Claude Code", "Төслүүд", "Коммит", "Дүгнэлт"]) expect(screen.getByText(step)).toBeInTheDocument();
  expect(screen.getByText("Эцэст нь хураангуйг батлах хүртэл юу ч бичигдэхгүй.")).toBeInTheDocument();
  // The server's refusal for a personal bot names this choice by these words (texts_mn.properties, setup.personalOneMember).
  expect(screen.getByText(/^Миний баг:/)).toBeInTheDocument();
  expect(screen.getByRole("button", { name: "Дараах" })).toBeInTheDocument();
});
