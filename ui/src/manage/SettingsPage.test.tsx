import { fireEvent, render, screen } from "@testing-library/react";
import { afterEach, expect, test, vi } from "vitest";
import * as api from "../api";
import { saved, savedNoRestart, teamConfig } from "./fixtures";
import { renderOnBoard } from "../desktop/testing";
import SettingsPage from "./SettingsPage";

// The real module, with the calls this file answers itself; ApiError stays the real class.
vi.mock("../api", async (importOriginal) => ({
  ...(await importOriginal<typeof import("../api")>()),
  getConfig: vi.fn(),
  // The strip reads the overview; these tests leave it unanswered.
  getOverview: vi.fn(() => new Promise(() => {})),
  saveSettings: vi.fn(),
}));

afterEach(() => vi.resetAllMocks());

test("saving sends every setting with the version it was read at, then says to restart", async () => {
  vi.mocked(api.getConfig).mockResolvedValueOnce(teamConfig).mockResolvedValue({ ...teamConfig, version: "v2" });
  const save = vi.mocked(api.saveSettings).mockResolvedValue(saved);

  renderOnBoard(<SettingsPage />);
  fireEvent.change(await screen.findByLabelText("Commit author name"), { target: { value: "Dispatch (backend)" } });
  fireEvent.click(screen.getByRole("button", { name: "Save" }));

  expect(await screen.findByText("Restart to apply")).toBeInTheDocument();
  expect(save).toHaveBeenCalledWith("v1", { ...teamConfig.settings, authorName: "Dispatch (backend)" });
  expect(screen.getByRole("button", { name: "Restart now" })).toBeInTheDocument();
});

test("a save that changed nothing does not say to restart", async () => {
  vi.mocked(api.getConfig).mockResolvedValue(teamConfig);
  vi.mocked(api.saveSettings).mockResolvedValue(savedNoRestart);

  renderOnBoard(<SettingsPage />);
  fireEvent.click(await screen.findByRole("button", { name: "Save" }));

  await vi.waitFor(() => expect(api.saveSettings).toHaveBeenCalled());
  expect(screen.queryByText("Restart to apply")).not.toBeInTheDocument();
});

test("a later save that changes nothing keeps an earlier restart notice", async () => {
  vi.mocked(api.getConfig).mockResolvedValueOnce(teamConfig).mockResolvedValue({ ...teamConfig, version: "v2" });
  vi.mocked(api.saveSettings).mockResolvedValueOnce(saved).mockResolvedValueOnce(savedNoRestart);

  renderOnBoard(<SettingsPage />);
  fireEvent.click(await screen.findByRole("button", { name: "Save" }));
  expect(await screen.findByText("Restart to apply")).toBeInTheDocument();

  fireEvent.click(screen.getByRole("button", { name: "Save" }));
  await vi.waitFor(() => expect(api.saveSettings).toHaveBeenCalledTimes(2));
  expect(screen.getByText("Restart to apply")).toBeInTheDocument();
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
