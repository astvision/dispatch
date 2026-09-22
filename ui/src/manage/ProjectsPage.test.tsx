import { fireEvent, render, screen } from "@testing-library/react";
import { afterEach, expect, test, vi } from "vitest";
import * as api from "../api";
import { saved, teamConfig } from "./fixtures";
import ProjectsPage from "./ProjectsPage";

// The real module, with the calls this file answers itself; ApiError stays the real class.
vi.mock("../api", async (importOriginal) => ({
  ...(await importOriginal<typeof import("../api")>()),
  getConfig: vi.fn(),
  editProject: vi.fn(),
  removeProject: vi.fn(),
  addProject: vi.fn(),
  listFolders: vi.fn(),
  probeProject: vi.fn(),
}));

afterEach(() => vi.resetAllMocks());

test("editing a project sends all its fields, the changed ones included", async () => {
  vi.mocked(api.getConfig).mockResolvedValue(teamConfig);
  const edit = vi.mocked(api.editProject).mockResolvedValue(saved);

  render(<ProjectsPage />);
  fireEvent.click(await screen.findByRole("button", { name: "Edit crm" }));
  fireEvent.change(screen.getByLabelText("Branch tasks start from"), { target: { value: "develop" } });
  fireEvent.change(screen.getByLabelText("Alias"), { target: { value: "" } });
  fireEvent.click(screen.getByRole("button", { name: "Save" }));

  await vi.waitFor(() => expect(edit).toHaveBeenCalledWith("v1", {
    name: "crm", baseBranch: "develop", alias: null, model: "opus", effort: null, plan: { model: "fable", effort: null }, execute: null,
  }));
  expect(await screen.findByText("Saved. Restart to apply")).toBeInTheDocument();
});

test("removing a project asks first", async () => {
  vi.mocked(api.getConfig).mockResolvedValue(teamConfig);
  const remove = vi.mocked(api.removeProject).mockResolvedValue(saved);

  render(<ProjectsPage />);
  fireEvent.click(await screen.findByRole("button", { name: "Remove alm" }));
  expect(remove).not.toHaveBeenCalled();
  fireEvent.click(await screen.findByRole("button", { name: "Remove" }));

  await vi.waitFor(() => expect(remove).toHaveBeenCalledWith("v1", "alm"));
});

test("a clone picked in the folder browser is added", async () => {
  vi.mocked(api.getConfig).mockResolvedValue(teamConfig);
  vi.mocked(api.listFolders).mockResolvedValue({
    path: "/home/bold", parent: "/home", truncated: false, folders: [{ name: "life", path: "/home/bold/life", gitClone: true }],
  });
  vi.mocked(api.probeProject).mockResolvedValue({
    folder: "/home/bold/life", name: "life", originUrl: null, originHadCredentials: false, baseBranch: "master",
  });
  const add = vi.mocked(api.addProject).mockResolvedValue(saved);

  render(<ProjectsPage />);
  fireEvent.click(await screen.findByRole("button", { name: "Add a project" }));
  fireEvent.click(await screen.findByRole("button", { name: "Use life" }));
  fireEvent.click(await screen.findByRole("button", { name: "Add project" }));

  await vi.waitFor(() => expect(add).toHaveBeenCalledWith("v1", "/home/bold/life", "acme", {
    name: "life", baseBranch: "master", alias: null, model: null, effort: null, plan: null, execute: null,
  }));
});
