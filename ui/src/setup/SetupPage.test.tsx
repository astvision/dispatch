import { render, screen } from "@testing-library/react";
import { afterEach, expect, test, vi } from "vitest";
import * as api from "../api";
import SetupPage from "./SetupPage";

// The real module, with the call this file answers itself; ApiError stays the real class.
vi.mock("../api", async (importOriginal) => ({
  ...(await importOriginal<typeof import("../api")>()),
  getSetupState: vi.fn(),
}));

afterEach(() => vi.resetAllMocks());

test("resumes at the Claude Code step when the server already has a bot and a member", async () => {
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
