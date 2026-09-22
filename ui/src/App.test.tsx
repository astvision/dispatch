import { fireEvent, render, screen } from "@testing-library/react";
import { afterEach, expect, test, vi } from "vitest";
import * as api from "./api";
import App from "./App";
import { teamConfig } from "./manage/fixtures";

// The real module, with the calls this file answers itself; ApiError stays the real class.
vi.mock("./api", async (importOriginal) => ({
  ...(await importOriginal<typeof import("./api")>()),
  getSetupState: vi.fn(),
  getOverview: vi.fn(),
  getConfig: vi.fn(),
}));

afterEach(() => vi.resetAllMocks());

test("without a config the page starts setup", async () => {
  vi.mocked(api.getSetupState).mockResolvedValue({
    configExists: false, configFile: "/x/dispatch.yaml", team: false, bot: null, members: [], candidate: null, group: null,
    claudeFound: null, authorEmail: null, hints: [], hintAfterSeconds: 20,
  });

  render(<App />);

  expect(await screen.findByText("Set up Dispatch")).toBeInTheDocument();
});

test("with a config the menu opens each management page at its own path", async () => {
  vi.mocked(api.getSetupState).mockResolvedValue({
    configExists: true, configFile: "/x/dispatch.yaml", team: false, bot: null, members: [], candidate: null, group: null,
    claudeFound: null, authorEmail: null, hints: [], hintAfterSeconds: 20,
  });
  vi.mocked(api.getOverview).mockReturnValue(new Promise(() => {}));
  vi.mocked(api.getConfig).mockResolvedValue(teamConfig);

  render(<App />);
  fireEvent.click(await screen.findByRole("menuitem", { name: "Settings" }));

  expect(await screen.findByLabelText("Planning timeout per run")).toHaveValue("15m");
  expect(window.location.pathname).toBe("/settings");
  window.history.pushState(null, "", "/");
});
