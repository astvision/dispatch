import { fireEvent, render, screen } from "@testing-library/react";
import { afterEach, expect, test, vi } from "vitest";
import * as api from "../api";
import { saved, teamConfig } from "./fixtures";
import PeoplePage from "./PeoplePage";

// The real module, with the calls this file answers itself; ApiError stays the real class.
vi.mock("../api", async (importOriginal) => ({
  ...(await importOriginal<typeof import("../api")>()),
  getConfig: vi.fn(),
  renameMember: vi.fn(),
  setAdmin: vi.fn(),
  removeMember: vi.fn(),
}));

afterEach(() => vi.resetAllMocks());

test("a member is renamed and made admin", async () => {
  vi.mocked(api.getConfig).mockResolvedValue(teamConfig);
  const rename = vi.mocked(api.renameMember).mockResolvedValue(saved);
  const admin = vi.mocked(api.setAdmin).mockResolvedValue(saved);

  render(<PeoplePage />);
  expect(await screen.findByText(/admin approves them there/)).toBeInTheDocument();
  fireEvent.click(screen.getByRole("button", { name: "Rename Ali" }));
  fireEvent.change(screen.getByLabelText("New name"), { target: { value: "Ali Baba" } });
  fireEvent.click(screen.getByRole("button", { name: "Save" }));
  await vi.waitFor(() => expect(rename).toHaveBeenCalledWith("v1", 222, "Ali Baba"));
  fireEvent.click(screen.getByRole("button", { name: "Make Ali admin" }));

  await vi.waitFor(() => expect(admin).toHaveBeenCalledWith("v1", 222, true));
});

test("removing a member asks first and names the group", async () => {
  vi.mocked(api.getConfig).mockResolvedValue(teamConfig);
  const remove = vi.mocked(api.removeMember).mockResolvedValue(saved);

  render(<PeoplePage />);
  fireEvent.click(await screen.findByRole("button", { name: "Remove Ali" }));
  expect(await screen.findByText("Remove Ali from acme?")).toBeInTheDocument();
  fireEvent.click(screen.getByRole("button", { name: "Remove" }));

  await vi.waitFor(() => expect(remove).toHaveBeenCalledWith("v1", "acme", 222));
});

test("a personal bot offers no admins", async () => {
  vi.mocked(api.getConfig).mockResolvedValue({
    ...teamConfig, personal: true, admins: [],
    groups: [{ ...teamConfig.groups[0], members: [{ id: 100, name: "Bold", admin: false }] }],
  });

  render(<PeoplePage />);

  expect(await screen.findByText(/one member, you, and no admins/)).toBeInTheDocument();
  expect(screen.queryByRole("button", { name: "Make Bold admin" })).not.toBeInTheDocument();
});
