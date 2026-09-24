import { render, screen, waitFor } from "@testing-library/react";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import * as api from "../api";
import App from "../App";

// Opened from Telegram: the shell asks who is looking, never the setup state, which this server does not serve.
vi.mock("../telegram", () => ({ inTelegram: true, initData: "signed", themeParams: null, prefersDark: false }));

vi.mock("../api", async (importOriginal) => ({
  ...(await importOriginal<typeof import("../api")>()),
  getMe: vi.fn(),
  getSetupState: vi.fn(),
  getOverview: vi.fn(),
  listTasks: vi.fn(),
}));

describe("the Mini App shell", () => {
  beforeEach(() => {
    vi.mocked(api.listTasks).mockResolvedValue({ tasks: [] });
    window.history.pushState(null, "", "/");
  });

  afterEach(() => vi.resetAllMocks());

  it("gives a member their own tasks and no chrome at all", async () => {
    vi.mocked(api.getMe).mockResolvedValue({ ref: "telegram:200", name: "Ali", admin: false });

    render(<App />);

    expect(await screen.findByRole("heading", { name: "Миний даалгаврууд" })).toBeInTheDocument();
    expect(screen.queryByRole("radio", { name: "Бүгд" })).not.toBeInTheDocument();
    expect(screen.queryByRole("button", { name: "Тохиргоо" })).not.toBeInTheDocument();
    expect(api.getSetupState).not.toHaveBeenCalled();
    expect(api.listTasks).toHaveBeenCalledWith("me");
  });

  it("gives an admin a switch between the two task lists and the management pages behind it", async () => {
    vi.mocked(api.getMe).mockResolvedValue({ ref: "telegram:100", name: "Bold", admin: true });

    render(<App />);

    expect(await screen.findByRole("radio", { name: "Минийх" })).toBeInTheDocument();
    expect(screen.getByRole("radio", { name: "Бүгд" })).toBeInTheDocument();
    expect(screen.getByRole("button", { name: "Тохиргоо" })).toBeInTheDocument();
  });

  /** Telegram opens the Mini App at "/", which is also Overview's path; tasks are what a member came for. */
  it("lands on the tasks page, not on the Overview an admin shares a path with", async () => {
    vi.mocked(api.getMe).mockResolvedValue({ ref: "telegram:100", name: "Bold", admin: true });

    render(<App />);

    await waitFor(() => expect(api.listTasks).toHaveBeenCalledWith("me"));
    expect(api.getOverview).not.toHaveBeenCalled();
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
