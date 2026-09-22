import { fireEvent, render, screen } from "@testing-library/react";
import { afterEach, expect, test, vi } from "vitest";
import * as api from "../api";
import ProjectsStep from "./ProjectsStep";
import type { Draft } from "./SetupPage";

// The real module, with the calls this file answers itself; ApiError stays the real class.
vi.mock("../api", async (importOriginal) => ({
  ...(await importOriginal<typeof import("../api")>()),
  listFolders: vi.fn(),
  probeProject: vi.fn(),
}));

const draft: Draft = { claude: "claude", projects: [], authorName: "", authorEmail: "", teamName: "" };

afterEach(() => vi.resetAllMocks());

test("a clone picked in the folder browser becomes a project", async () => {
  vi.mocked(api.listFolders).mockResolvedValue({
    path: "/home/bold", parent: "/home", truncated: false,
    folders: [{ name: "alm", path: "/home/bold/alm", gitClone: true }, { name: "notes", path: "/home/bold/notes", gitClone: false }],
  });
  vi.mocked(api.probeProject).mockResolvedValue({
    folder: "/home/bold/alm", name: "alm", originUrl: "git@github.com:acme/alm.git", originHadCredentials: false, baseBranch: "main",
  });
  const update = vi.fn();

  render(<ProjectsStep draft={draft} update={update} next={vi.fn()} back={vi.fn()} />);
  fireEvent.click(await screen.findByRole("button", { name: "Use alm" }));
  fireEvent.click(await screen.findByRole("button", { name: "Add project" }));

  await vi.waitFor(() => expect(update).toHaveBeenCalledWith({
    projects: [{ folder: "/home/bold/alm", name: "alm", baseBranch: "main", model: null, effort: null }],
  }));
  expect(screen.queryByRole("button", { name: "Use notes" })).not.toBeInTheDocument();
});
