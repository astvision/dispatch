import { fireEvent, render, screen, within } from "@testing-library/react";
import { afterEach, expect, test, vi } from "vitest";
import * as api from "../api";
import { LanguageProvider } from "../i18n/i18n";
import { saved, teamConfig } from "./fixtures";
import PeoplePage from "./PeoplePage";

// The real module, with the calls this file answers itself; ApiError stays the real class.
vi.mock("../api", async (importOriginal) => ({
  ...(await importOriginal<typeof import("../api")>()),
  getConfig: vi.fn(),
  renameMember: vi.fn(),
  setAdmin: vi.fn(),
  removeMember: vi.fn(),
  unlinkGroup: vi.fn(),
}));

afterEach(() => vi.resetAllMocks());

/** The section of one group, found by its heading. */
const section = async (group: string) => (await screen.findByRole("heading", { name: group })).closest("section")!;

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
  // The switches wait while a save is on its way: a second save would carry a version the first one replaced.
  await vi.waitFor(() => expect(screen.getByRole("switch", { name: "Admin: Ali" })).toBeEnabled());
  fireEvent.click(screen.getByRole("switch", { name: "Admin: Ali" }));

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

test("each group shows its chat and its projects, and the chat can be unlinked", async () => {
  vi.mocked(api.getConfig).mockResolvedValue(teamConfig);
  const unlink = vi.mocked(api.unlinkGroup).mockResolvedValue(saved);

  render(<PeoplePage />);
  const acme = await section("acme");
  expect(within(acme).getByText("-1001234567890")).toBeInTheDocument();
  expect(within(acme).getByText("alm")).toBeInTheDocument();
  expect(within(acme).getByText("crm")).toBeInTheDocument();
  fireEvent.click(within(acme).getByRole("button", { name: "Unlink the chat" }));
  expect(unlink).not.toHaveBeenCalled();
  fireEvent.click(await screen.findByRole("button", { name: "Unlink" }));

  await vi.waitFor(() => expect(unlink).toHaveBeenCalledWith("v1", "acme"));
});

test("a group without a chat says it is only you, with nothing to unlink", async () => {
  vi.mocked(api.getConfig).mockResolvedValue({ ...teamConfig, groups: [{ ...teamConfig.groups[0], chatId: null }] });

  render(<PeoplePage />);

  expect(within(await section("acme")).getByText("No chat: only you")).toBeInTheDocument();
  expect(screen.queryByRole("button", { name: "Unlink the chat" })).not.toBeInTheDocument();
});

test("a personal bot offers no admins", async () => {
  vi.mocked(api.getConfig).mockResolvedValue({
    ...teamConfig, personal: true, admins: [],
    groups: [{ ...teamConfig.groups[0], members: [{ id: 100, name: "Bold", admin: false }] }],
  });

  render(<PeoplePage />);

  expect(await screen.findByText(/one member, you, and no admins/)).toBeInTheDocument();
  expect(screen.queryByRole("switch")).not.toBeInTheDocument();
});

test("in Mongolian the admin switch says whose it is", async () => {
  vi.mocked(api.getConfig).mockResolvedValue(teamConfig);

  render(<LanguageProvider storage={{ getItem: () => null, setItem: () => {} }} languages={["mn"]}><PeoplePage /></LanguageProvider>);

  expect(await screen.findByRole("switch", { name: "Админ: Ali" })).not.toBeChecked();
  expect(screen.getByRole("switch", { name: "Админ: Bold" })).toBeChecked();
  expect(screen.getByRole("button", { name: "Чат салгах" })).toBeInTheDocument();
});
