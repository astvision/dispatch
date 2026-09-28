import { fireEvent, render, screen } from "@testing-library/react";
import { afterEach, expect, test, vi } from "vitest";
import * as api from "../api";
import type { ConfigView, Overview } from "../api";
import { teamConfig } from "../manage/fixtures";
import { chooseMember, chosenMember } from "./member";
import Shell, { DESKTOP_PAGES } from "./Shell";
import { DesktopProviders } from "./status";

vi.mock("../api", async (importOriginal) => ({
  ...(await importOriginal<typeof import("../api")>()),
  getOverview: vi.fn(),
  getLive: vi.fn(),
  getConfig: vi.fn(),
}));

afterEach(() => {
  vi.resetAllMocks();
  chooseMember(null);
});

const overview: Overview = {
  configured: true, version: "0.2.0", name: "acme", configFile: "/c", stateDir: "/s",
  service: { name: "systemd user service dispatch.service", installed: true, running: true, detail: "active", notes: [] },
  findings: [],
};
const live: api.Live = { version: "0.2.0", name: "acme", running: 0, queued: 0, waitingOnYou: [], waitingOnOthers: 0,
  todayUsd: "0.00", monthUsd: "0.00" };
const twoAdmins: ConfigView = {
  ...teamConfig, admins: [100, 300],
  groups: [{ ...teamConfig.groups[0], members: [...teamConfig.groups[0].members, { id: 300, name: "Saraa", admin: true }] }],
};

function renderDesktop() {
  render(
    <DesktopProviders>
      <Shell pages={DESKTOP_PAGES} selected="/" onSelect={vi.fn()}><p>page</p></Shell>
    </DesktopProviders>,
  );
}

test("a team with several admins is asked once which one this is, and the choice is kept", async () => {
  vi.mocked(api.getOverview).mockResolvedValue(overview);
  vi.mocked(api.getConfig).mockResolvedValue(twoAdmins);
  vi.mocked(api.getLive).mockRejectedValueOnce(new api.ApiError("choose_member", "choose which admin you are")).mockResolvedValue(live);

  renderDesktop();
  expect(await screen.findByText("Which admin are you?")).toBeInTheDocument();
  fireEvent.click(await screen.findByRole("button", { name: "Saraa" }));

  expect(chosenMember()).toBe("telegram:300");
  await vi.waitFor(() => expect(screen.queryByText("Which admin are you?")).not.toBeInTheDocument());
  expect(api.getLive).toHaveBeenCalledTimes(2);
});

test("a remembered admin who is no longer one is forgotten and the page asks again", async () => {
  chooseMember("telegram:200");
  vi.mocked(api.getOverview).mockResolvedValue(overview);
  vi.mocked(api.getConfig).mockResolvedValue(twoAdmins);
  vi.mocked(api.getLive)
    .mockRejectedValueOnce(new api.ApiError("not_owner", "telegram:200 may not act for this Dispatch"))
    .mockRejectedValue(new api.ApiError("choose_member", "choose which admin you are"));

  renderDesktop();

  expect(await screen.findByText("Which admin are you?")).toBeInTheDocument();
  expect(chosenMember()).toBeNull();
});

test("a remembered member the desk refuses is forgotten and the desk read again at once, asking nothing", async () => {
  chooseMember("telegram:999");
  vi.mocked(api.getOverview).mockResolvedValue(overview);
  vi.mocked(api.getLive)
    .mockRejectedValueOnce(new api.ApiError("not_owner", "telegram:999 may not act for this Dispatch"))
    .mockResolvedValue(live);

  renderDesktop();

  // Well inside the 5-second beat: the second reading is the retry, not the next poll.
  await screen.findByText("0 running");
  expect(chosenMember()).toBeNull();
  expect(api.getConfig).not.toHaveBeenCalled();
});

test("a desk that asks while the config names no admin opens no dialog it could never close", async () => {
  vi.mocked(api.getOverview).mockResolvedValue(overview);
  vi.mocked(api.getConfig).mockResolvedValue({ ...teamConfig, admins: [] });
  vi.mocked(api.getLive).mockRejectedValue(new api.ApiError("choose_member", "choose which admin you are"));

  renderDesktop();

  await vi.waitFor(() => expect(api.getConfig).toHaveBeenCalled());
  await Promise.resolve();
  expect(screen.queryByRole("dialog")).not.toBeInTheDocument();
});

test("a desk that knows who you are never asks", async () => {
  vi.mocked(api.getOverview).mockResolvedValue(overview);
  vi.mocked(api.getLive).mockResolvedValue(live);

  renderDesktop();

  await screen.findByText("0 running");
  expect(screen.queryByText("Which admin are you?")).not.toBeInTheDocument();
  expect(api.getConfig).not.toHaveBeenCalled();
});
