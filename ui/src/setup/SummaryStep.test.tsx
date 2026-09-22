import { fireEvent, render, screen } from "@testing-library/react";
import { afterEach, expect, test, vi } from "vitest";
import * as api from "../api";
import SummaryStep from "./SummaryStep";
import type { Draft } from "./SetupPage";

// The real module, with the calls this file answers itself; ApiError stays the real class.
vi.mock("../api", async (importOriginal) => ({
  ...(await importOriginal<typeof import("../api")>()),
  writeSetup: vi.fn(),
  installService: vi.fn(),
}));

const state: api.SetupState = {
  configExists: false, configFile: "/home/bold/.config/dispatch/dispatch.yaml", team: false,
  bot: { username: "acme_bot", topicsEnabled: true }, members: [{ id: 100, name: "Bold" }], candidate: null, group: null,
  claudeFound: null, authorEmail: null, hints: [], hintAfterSeconds: 20,
};
const draft: Draft = {
  claude: "/usr/local/bin/claude", authorName: "Dispatch (Bold)", authorEmail: "bold@example.com", teamName: "",
  projects: [{ folder: "/home/bold/alm", name: "alm", baseBranch: "main", model: "opus", effort: null }],
};

afterEach(() => vi.resetAllMocks());

test("writing sends every answer and then offers the background service", async () => {
  const write = vi.mocked(api.writeSetup).mockResolvedValue({
    configFile: state.configFile, secretsFile: "/home/bold/.config/dispatch/dispatch.env",
  });
  const install = vi.mocked(api.installService).mockResolvedValue({
    name: "systemd user service dispatch.service", installed: true, running: true, detail: "active (running)", notes: [],
  });
  const onDone = vi.fn();

  render(<SummaryStep state={state} draft={draft} back={vi.fn()} onDone={onDone} />);
  fireEvent.click(screen.getByRole("button", { name: "Write this setup" }));

  expect(await screen.findByText(/dispatch.env/)).toBeInTheDocument();
  expect(write).toHaveBeenCalledWith({
    teamName: null, claude: "/usr/local/bin/claude", authorName: "Dispatch (Bold)", authorEmail: "bold@example.com",
    projects: draft.projects,
  });
  fireEvent.click(screen.getByRole("button", { name: "Keep it running in the background" }));
  expect(await screen.findByText(/active \(running\)/)).toBeInTheDocument();
  expect(install).toHaveBeenCalled();
  fireEvent.click(screen.getByRole("button", { name: "Open the overview" }));
  expect(onDone).toHaveBeenCalled();
});
