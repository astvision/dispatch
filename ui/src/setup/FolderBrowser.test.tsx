import { act, fireEvent, render, screen } from "@testing-library/react";
import { afterEach, expect, test, vi } from "vitest";
import * as api from "../api";
import FolderBrowser from "./FolderBrowser";

// The real module, with the calls this file answers itself; ApiError stays the real class.
vi.mock("../api", async (importOriginal) => ({
  ...(await importOriginal<typeof import("../api")>()),
  listFolders: vi.fn(),
}));

afterEach(() => vi.resetAllMocks());

test("a path typed while the first listing loads is kept when it arrives", async () => {
  let answerHome!: (listing: api.FolderListing) => void;
  vi.mocked(api.listFolders).mockReturnValueOnce(new Promise((resolve) => {
    answerHome = resolve;
  }));

  render(<FolderBrowser onPick={vi.fn()} />);
  fireEvent.change(screen.getByLabelText("Folder"), { target: { value: "/work" } });
  await act(async () => answerHome({ path: "/home/bold", parent: "/home", truncated: false, folders: [] }));

  expect(screen.getByLabelText("Folder")).toHaveValue("/work");
});

test("an older listing that answers late does not replace a newer one", async () => {
  let answerHome!: (listing: api.FolderListing) => void;
  vi.mocked(api.listFolders)
    .mockReturnValueOnce(new Promise((resolve) => {
      answerHome = resolve;
    }))
    .mockResolvedValueOnce({ path: "/work", parent: "/", truncated: false, folders: [{ name: "life", path: "/work/life", gitClone: true }] });

  render(<FolderBrowser onPick={vi.fn()} />);
  // A folder's own button stays clickable while the first listing loads.
  await act(async () => answerHome({ path: "/home/bold", parent: "/home", truncated: false,
    folders: [{ name: "work", path: "/work", gitClone: false }] }));
  vi.mocked(api.listFolders).mockReset();
  let answerWork!: (listing: api.FolderListing) => void;
  let answerStale!: (listing: api.FolderListing) => void;
  vi.mocked(api.listFolders)
    .mockReturnValueOnce(new Promise((resolve) => {
      answerStale = resolve;
    }))
    .mockReturnValueOnce(new Promise((resolve) => {
      answerWork = resolve;
    }));
  fireEvent.click(screen.getByRole("button", { name: "Up" }));
  fireEvent.click(screen.getByText("work"));
  await act(async () => answerWork({ path: "/work", parent: "/", truncated: false, folders: [{ name: "life", path: "/work/life", gitClone: true }] }));
  await act(async () => answerStale({ path: "/home", parent: "/", truncated: false, folders: [] }));

  expect(screen.getByRole("button", { name: "Use life" })).toBeInTheDocument();
  expect(screen.getByLabelText("Folder")).toHaveValue("/work");
});
