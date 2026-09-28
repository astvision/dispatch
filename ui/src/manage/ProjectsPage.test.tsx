import { fireEvent, render, screen, within } from "@testing-library/react";
import { afterEach, expect, test, vi } from "vitest";
import * as api from "../api";
import { saved, teamConfig } from "./fixtures";
import { renderOnBoard } from "../desktop/testing";
import { LanguageProvider } from "../i18n/i18n";
import ProjectsPage from "./ProjectsPage";

// The real module, with the calls this file answers itself; ApiError stays the real class.
vi.mock("../api", async (importOriginal) => ({
  ...(await importOriginal<typeof import("../api")>()),
  getConfig: vi.fn(),
  // The strip reads the overview; these tests leave it unanswered.
  getOverview: vi.fn(() => new Promise(() => {})),
  editProject: vi.fn(),
  removeProject: vi.fn(),
  addProject: vi.fn(),
  listFolders: vi.fn(),
  probeProject: vi.fn(),
}));

const matchMedia = window.matchMedia;
afterEach(() => {
  vi.resetAllMocks();
  window.matchMedia = matchMedia;
});

/** The side panel, once it is open. */
const panel = () => screen.findByRole("dialog");

test("editing a project sends all its fields, the changed ones included", async () => {
  vi.mocked(api.getConfig).mockResolvedValue(teamConfig);
  const edit = vi.mocked(api.editProject).mockResolvedValue(saved);

  renderOnBoard(<ProjectsPage />);
  fireEvent.click(await screen.findByRole("button", { name: "Edit crm" }));
  fireEvent.change(screen.getByLabelText("Branch tasks start from"), { target: { value: "develop" } });
  fireEvent.change(screen.getByLabelText("Alias"), { target: { value: "" } });
  fireEvent.click(screen.getByRole("button", { name: "Save" }));

  await vi.waitFor(() => expect(edit).toHaveBeenCalledWith("v1", {
    name: "crm", baseBranch: "develop", alias: null, model: "opus", effort: null, plan: { model: "fable", effort: null }, execute: null,
  }));
  expect(await screen.findByText("Restart to apply")).toBeInTheDocument();
});

test("removing a project asks first", async () => {
  vi.mocked(api.getConfig).mockResolvedValue(teamConfig);
  const remove = vi.mocked(api.removeProject).mockResolvedValue(saved);

  render(<ProjectsPage />);
  fireEvent.click(await screen.findByRole("button", { name: "Remove alm" }));
  expect(remove).not.toHaveBeenCalled();
  expect(await screen.findByText("Remove alm?")).toBeInTheDocument();
  expect(screen.getByText(/the clone stays/)).toBeInTheDocument();
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

test("a row opens the side panel titled with its project, and saving closes it", async () => {
  vi.mocked(api.getConfig).mockResolvedValue(teamConfig);
  vi.mocked(api.editProject).mockResolvedValue(saved);

  render(<ProjectsPage />);
  fireEvent.click(await screen.findByText("alm"));
  const opened = await panel();
  expect(within(opened).getByText("alm")).toBeInTheDocument();
  expect(within(opened).getByText("A project of acme")).toBeInTheDocument();
  fireEvent.click(within(opened).getByRole("button", { name: "Save" }));

  await vi.waitFor(() => expect(api.editProject).toHaveBeenCalled());
  await vi.waitFor(() => expect(screen.queryByLabelText("Branch tasks start from")).not.toBeInTheDocument());
});

test("below 640 px the side panel takes the whole width", async () => {
  window.matchMedia = ((query: string) => ({ ...matchMedia(query), matches: query === "(max-width: 640px)" })) as typeof window.matchMedia;
  vi.mocked(api.getConfig).mockResolvedValue(teamConfig);

  render(<ProjectsPage />);
  fireEvent.click(await screen.findByRole("button", { name: "Edit crm" }));
  await panel();

  expect(document.querySelector<HTMLElement>(".ant-drawer-content-wrapper")?.style.width).toBe("100%");
});

test("switching the agent sends it, with the old agent's model and effort cleared", async () => {
  vi.mocked(api.getConfig).mockResolvedValue(teamConfig);
  const edit = vi.mocked(api.editProject).mockResolvedValue(saved);

  render(<ProjectsPage />);
  fireEvent.click(await screen.findByRole("button", { name: "Edit crm" }));
  fireEvent.mouseDown(within(await panel()).getByLabelText("Agent"));
  fireEvent.click(await screen.findByTitle("Codex"));
  fireEvent.click(screen.getByRole("button", { name: "Save" }));

  await vi.waitFor(() => expect(edit).toHaveBeenCalledWith("v1", {
    name: "crm", baseBranch: "main", alias: "c", model: null, effort: null, plan: { model: "fable", effort: null }, execute: null,
    agent: "codex",
  }));
});

test("in Mongolian the columns and the panel's help are Mongolian", async () => {
  vi.mocked(api.getConfig).mockResolvedValue(teamConfig);

  render(<LanguageProvider storage={{ getItem: () => null, setItem: () => {} }} languages={["mn"]}><ProjectsPage /></LanguageProvider>);

  for (const column of ["Төсөл", "Бүлэг", "Хавтас", "Салбар", "Агент"]) {
    expect(await screen.findByRole("columnheader", { name: column })).toBeInTheDocument();
  }
  fireEvent.click(screen.getByText("crm"));
  expect(within(await panel()).getByText("Даалгавар өгөхдөө төслийг ингэж дуудна; хоосон бол байхгүй")).toBeInTheDocument();
});
