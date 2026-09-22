import { render, screen } from "@testing-library/react";
import { afterEach, expect, test, vi } from "vitest";
import * as api from "./api";
import App from "./App";

// The real module, with the calls this file answers itself; ApiError stays the real class.
vi.mock("./api", async (importOriginal) => ({
  ...(await importOriginal<typeof import("./api")>()),
  getSetupState: vi.fn(),
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
