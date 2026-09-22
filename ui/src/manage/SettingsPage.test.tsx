import { fireEvent, render, screen } from "@testing-library/react";
import { afterEach, expect, test, vi } from "vitest";
import * as api from "../api";
import { saved, teamConfig } from "./fixtures";
import SettingsPage from "./SettingsPage";

// The real module, with the calls this file answers itself; ApiError stays the real class.
vi.mock("../api", async (importOriginal) => ({
  ...(await importOriginal<typeof import("../api")>()),
  getConfig: vi.fn(),
  saveSettings: vi.fn(),
}));

afterEach(() => vi.resetAllMocks());

test("saving sends every setting with the version it was read at, then says to restart", async () => {
  vi.mocked(api.getConfig).mockResolvedValueOnce(teamConfig).mockResolvedValue({ ...teamConfig, version: "v2" });
  const save = vi.mocked(api.saveSettings).mockResolvedValue(saved);

  render(<SettingsPage />);
  fireEvent.change(await screen.findByLabelText("Commit author name"), { target: { value: "Dispatch (backend)" } });
  fireEvent.click(screen.getByRole("button", { name: "Save" }));

  expect(await screen.findByText("Saved. Restart to apply")).toBeInTheDocument();
  expect(save).toHaveBeenCalledWith("v1", { ...teamConfig.settings, authorName: "Dispatch (backend)" });
  expect(screen.getByRole("button", { name: "Restart now" })).toBeInTheDocument();
});

test("a config changed on disk is explained and can be reloaded", async () => {
  vi.mocked(api.getConfig).mockResolvedValue(teamConfig);
  vi.mocked(api.saveSettings).mockRejectedValue(
    new api.ApiError("changed", "the config changed on disk since this page loaded it; reload to see the change"));

  render(<SettingsPage />);
  fireEvent.click(await screen.findByRole("button", { name: "Save" }));

  expect(await screen.findByText(/changed on disk/)).toBeInTheDocument();
  fireEvent.click(screen.getByRole("button", { name: "Reload" }));
  await vi.waitFor(() => expect(screen.queryByText(/changed on disk/)).not.toBeInTheDocument());
  expect(api.getConfig).toHaveBeenCalledTimes(2);
});
