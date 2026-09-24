import { act, fireEvent, render, screen, waitFor, within } from "@testing-library/react";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import * as api from "../api";
import App from "../App";
import { saved, teamConfig } from "../manage/fixtures";
import { myRunningTask, someoneElsesTask } from "./fixtures";

// Opened from Telegram: the shell asks who is looking, never the setup state, which this server does not serve.
vi.mock("../telegram", () => ({ inTelegram: true, initData: "signed", themeParams: null, prefersDark: false }));

vi.mock("../api", async (importOriginal) => ({
  ...(await importOriginal<typeof import("../api")>()),
  getMe: vi.fn(),
  getSetupState: vi.fn(),
  getOverview: vi.fn(),
  getConfig: vi.fn(),
  listProjects: vi.fn(),
  listTasks: vi.fn(),
  editProject: vi.fn(),
  removeProject: vi.fn(),
  unlinkGroup: vi.fn(),
  getPrefs: vi.fn(),
  savePrefs: vi.fn(),
}));

const ADMIN: api.Me = { ref: "telegram:100", name: "Bold", admin: true, bot: "dispatch_task_bot" };
const MEMBER: api.Me = { ref: "telegram:222", name: "Ali", admin: false, bot: "dispatch_task_bot" };

/** Rows are buttons whose accessible name starts with their title. */
const row = (title: string) => screen.findByRole("button", { name: new RegExp(`^${title}`) });

describe("the Mini App", () => {
  beforeEach(() => {
    vi.mocked(api.getConfig).mockResolvedValue(teamConfig);
    vi.mocked(api.listProjects).mockResolvedValue({ projects: [{ name: "alm", alias: null, baseBranch: "main" }] });
    vi.mocked(api.listTasks).mockResolvedValue({ tasks: [myRunningTask] });
    vi.mocked(api.getPrefs).mockResolvedValue({ groupAck: "reaction" });
    window.history.pushState(null, "", "/");
  });

  afterEach(() => vi.resetAllMocks());

  it("opens on the bot and its tickets, with the projects one row away, each with its branch and active count", async () => {
    vi.mocked(api.getMe).mockResolvedValue(ADMIN);

    render(<App />);

    expect(await screen.findByText(/@dispatch_task_bot/)).toBeInTheDocument();
    expect(await screen.findByRole("heading", { name: "Таны шийдвэр" })).toBeInTheDocument();
    expect(screen.getByRole("button", { name: "Хүмүүс" })).toBeInTheDocument();
    fireEvent.click(screen.getByRole("button", { name: "Төслүүд" }));

    expect(await row("alm")).toHaveTextContent("main · 1 идэвхтэй");
    expect(await row("crm")).toHaveTextContent("@c · main");
    expect(screen.getByRole("button", { name: /^Төсөл нэмэх/ })).toBeInTheDocument();
    expect(api.getSetupState).not.toHaveBeenCalled();
    expect(api.getOverview).not.toHaveBeenCalled();
  });

  it("finds a project by its name or its alias", async () => {
    vi.mocked(api.getMe).mockResolvedValue(ADMIN);
    window.history.pushState(null, "", "/projects");
    render(<App />);
    await row("crm");

    fireEvent.change(screen.getByRole("textbox", { name: "Төсөл хайх" }), { target: { value: "c" } });
    expect(screen.queryByRole("button", { name: /^alm/ })).not.toBeInTheDocument();
    expect(screen.getByRole("button", { name: /^crm/ })).toBeInTheDocument();

    fireEvent.change(screen.getByRole("textbox", { name: "Төсөл хайх" }), { target: { value: "billing" } });
    expect(screen.getByText("Ийм төсөл олдсонгүй")).toBeInTheDocument();
  });

  it("gives a member their groups' projects and their own tasks, and none of the setup", async () => {
    vi.mocked(api.getMe).mockResolvedValue(MEMBER);

    render(<App />);

    fireEvent.click(await screen.findByRole("button", { name: "Төслүүд" }));
    expect(screen.queryByRole("button", { name: "Хүмүүс" })).not.toBeInTheDocument();
    expect(screen.queryByRole("button", { name: "Бүх даалгавар" })).not.toBeInTheDocument();
    expect(screen.queryByRole("button", { name: "Группүүд" })).not.toBeInTheDocument();
    expect(await row("alm")).toBeInTheDocument();
    expect(api.getConfig).not.toHaveBeenCalled();
    expect(api.listTasks).toHaveBeenCalledWith("me");
    expect(screen.queryByRole("button", { name: /^Төсөл нэмэх/ })).not.toBeInTheDocument();
  });

  it("shows the current group-ack choice checked and saves a new one on tap", async () => {
    vi.mocked(api.getMe).mockResolvedValue(MEMBER);
    vi.mocked(api.getPrefs).mockResolvedValue({ groupAck: "reactionAndLine" });
    vi.mocked(api.savePrefs).mockResolvedValue({ groupAck: "silent" });
    render(<App />);

    fireEvent.click(await row("Миний тохиргоо"));

    // Exact accessible names: "Реакц" is a prefix of "Реакц + мөр", and "+" is a regex special character, so row()'s
    // prefix regex cannot tell these three choices apart.
    const choice = (title: string) => screen.findByRole("button", { name: title });
    expect(await choice("Реакц + мөр")).toHaveAttribute("aria-pressed", "true");
    expect(await choice("Реакц")).toHaveAttribute("aria-pressed", "false");
    expect(await choice("Чимээгүй")).toHaveAttribute("aria-pressed", "false");

    fireEvent.click(await choice("Чимээгүй"));

    await waitFor(() => expect(api.savePrefs).toHaveBeenCalledWith("silent"));
    expect(await choice("Чимээгүй")).toHaveAttribute("aria-pressed", "true");
  });

  it("opens a project on its own tasks and, for an admin, a row per setting", async () => {
    vi.mocked(api.getMe).mockResolvedValue(ADMIN);
    vi.mocked(api.listTasks).mockResolvedValue({ tasks: [myRunningTask, { ...someoneElsesTask, project: "crm" }] });
    window.history.pushState(null, "", "/projects");
    render(<App />);

    fireEvent.click(await row("alm"));

    expect(await screen.findByText(/Fix the login timeout/)).toBeInTheDocument();
    expect(screen.queryByText(/Rename the settings page/)).not.toBeInTheDocument();
    expect(await row("Эхлэх салбар")).toHaveTextContent("main");
    expect(screen.getByRole("button", { name: /^Төсөл хасах/ })).toBeInTheDocument();
    expect(window.location.pathname).toBe("/p/alm");
  });

  it("shows a member a project without its settings", async () => {
    vi.mocked(api.getMe).mockResolvedValue(MEMBER);
    window.history.pushState(null, "", "/p/alm");

    render(<App />);

    expect(await screen.findByText(/Fix the login timeout/)).toBeInTheDocument();
    expect(screen.queryByRole("button", { name: /^Эхлэх салбар/ })).not.toBeInTheDocument();
    expect(screen.queryByRole("button", { name: /^Төсөл хасах/ })).not.toBeInTheDocument();
  });

  it("keeps a member out of an admin's screens even by path", async () => {
    vi.mocked(api.getMe).mockResolvedValue(MEMBER);
    window.history.pushState(null, "", "/p/alm/edit/baseBranch");

    render(<App />);

    expect(await screen.findByRole("heading", { name: "Таны шийдвэр" })).toBeInTheDocument();
    expect(api.getConfig).not.toHaveBeenCalled();
  });

  it("changes one setting on its own screen, then goes back to the project and says to restart", async () => {
    vi.mocked(api.getMe).mockResolvedValue(ADMIN);
    vi.mocked(api.editProject).mockResolvedValue(saved);
    window.history.pushState(null, "", "/p/crm");
    render(<App />);

    fireEvent.click(await row("Эхлэх салбар"));
    const input = await screen.findByRole("textbox", { name: /салбараас эхэлнэ/ });
    fireEvent.change(input, { target: { value: "develop" } });
    fireEvent.click(screen.getByRole("button", { name: "Хадгалах" }));

    await waitFor(() => expect(api.editProject).toHaveBeenCalledWith("v1", {
      name: "crm", baseBranch: "develop", alias: "c", model: "opus", effort: null, plan: { model: "fable", effort: null },
      execute: null,
    }));
    await waitFor(() => expect(window.location.pathname).toBe("/p/crm"));
    expect(await screen.findByText("Saved. Restart to apply")).toBeInTheDocument();
  });

  it("saves a choice as soon as it is tapped, keeping the phase's other choice", async () => {
    vi.mocked(api.getMe).mockResolvedValue(ADMIN);
    vi.mocked(api.editProject).mockResolvedValue(saved);
    window.history.pushState(null, "", "/p/crm/edit/plan");
    render(<App />);

    const effort = await screen.findByRole("heading", { name: "Effort" });
    fireEvent.click(within(effort.closest("section")!).getByRole("button", { name: /^High/ }));

    await waitFor(() => expect(api.editProject).toHaveBeenCalledWith("v1", expect.objectContaining({
      plan: { model: "fable", effort: "high" },
    })));
  });

  it("asks on the page before removing a project, then goes back to the projects", async () => {
    vi.mocked(api.getMe).mockResolvedValue(ADMIN);
    vi.mocked(api.removeProject).mockResolvedValue(saved);
    window.history.pushState(null, "", "/p/alm");
    render(<App />);

    fireEvent.click(await row("Төсөл хасах"));
    expect(api.removeProject).not.toHaveBeenCalled();
    fireEvent.click(await row("Тийм, хасах"));

    await waitFor(() => expect(api.removeProject).toHaveBeenCalledWith("v1", "alm"));
    await waitFor(() => expect(window.location.pathname).toBe("/projects"));
  });

  it("lists linked groups and unlinks one after asking on the page", async () => {
    vi.mocked(api.getMe).mockResolvedValue(ADMIN);
    vi.mocked(api.unlinkGroup).mockResolvedValue(saved);
    window.history.pushState(null, "", "/groups");
    render(<App />);

    fireEvent.click(await row("acme"));
    fireEvent.click(await row("Тийм, салгах"));

    await waitFor(() => expect(api.unlinkGroup).toHaveBeenCalledWith("v1", "acme"));
  });

  it("says how to link a group, since only Telegram can add the bot", async () => {
    vi.mocked(api.getMe).mockResolvedValue(ADMIN);
    window.history.pushState(null, "", "/groups");
    render(<App />);
    expect(await screen.findByText(/ботыг группт нэмнэ/)).toBeInTheDocument();
  });

  it("goes back one level at a time", async () => {
    vi.mocked(api.getMe).mockResolvedValue(ADMIN);
    window.history.pushState(null, "", "/p/crm/edit/alias");
    render(<App />);

    fireEvent.click(await screen.findByRole("button", { name: "Буцах" }));
    await waitFor(() => expect(window.location.pathname).toBe("/p/crm"));
    fireEvent.click(screen.getByRole("button", { name: "Буцах" }));
    await waitFor(() => expect(window.location.pathname).toBe("/projects"));
    fireEvent.click(screen.getByRole("button", { name: "Буцах" }));
    await waitFor(() => expect(window.location.pathname).toBe("/"));
    expect(screen.queryByRole("button", { name: "Буцах" })).not.toBeInTheDocument();
  });

  it("follows Telegram's own Back button", async () => {
    vi.mocked(api.getMe).mockResolvedValue(ADMIN);
    window.history.pushState(null, "", "/p/crm");
    render(<App />);
    await row("Эхлэх салбар");

    const telegram = (window as unknown as { Telegram: { WebView: { receiveEvent: (type: string, data: unknown) => void } } })
      .Telegram;
    act(() => telegram.WebView.receiveEvent("back_button_pressed", null));

    expect(await row("Төсөл нэмэх")).toBeInTheDocument();
    expect(window.location.pathname).toBe("/projects");
  });

  it("asks a member to reopen the Mini App when the launch data went stale", async () => {
    vi.mocked(api.getMe).mockRejectedValue(new api.ApiError("expired", "this Mini App has been open too long"));

    render(<App />);

    expect(await screen.findByText("Дахин нээнэ үү")).toBeInTheDocument();
    expect(screen.getByText(/open too long/)).toBeInTheDocument();
  });

  it("says who to ask when Telegram knows someone this Dispatch does not", async () => {
    vi.mocked(api.getMe).mockRejectedValue(new api.ApiError("not_a_member", "ask an admin to add you"));

    render(<App />);

    expect(await screen.findByText("Эрх алга")).toBeInTheDocument();
    expect(screen.getByText(/ask an admin/)).toBeInTheDocument();
  });
});
