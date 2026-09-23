import { render, screen } from "@testing-library/react";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import * as api from "../api";
import App from "../App";

// Opened from Telegram: the shell asks who is looking, never the setup state, which this server does not serve.
vi.mock("../telegram", () => ({ inTelegram: true, initData: "signed", themeParams: null, prefersDark: false }));

vi.mock("../api", async (importOriginal) => ({
  ...(await importOriginal<typeof import("../api")>()),
  getMe: vi.fn(),
  getSetupState: vi.fn(),
  listTasks: vi.fn(),
}));

describe("the Mini App shell", () => {
  beforeEach(() => {
    vi.mocked(api.listTasks).mockResolvedValue({ tasks: [] });
    window.history.pushState(null, "", "/");
  });

  afterEach(() => vi.resetAllMocks());

  it("shows a member their own tasks and nothing to manage", async () => {
    vi.mocked(api.getMe).mockResolvedValue({ ref: "telegram:200", name: "Ali", admin: false });

    render(<App />);

    expect(await screen.findByRole("menuitem", { name: "Миний даалгаврууд" })).toBeInTheDocument();
    expect(screen.queryByRole("menuitem", { name: "Settings" })).not.toBeInTheDocument();
    expect(screen.queryByRole("menuitem", { name: "Бүх даалгавар" })).not.toBeInTheDocument();
    expect(api.getSetupState).not.toHaveBeenCalled();
  });

  it("gives an admin the group's tasks and the management pages too", async () => {
    vi.mocked(api.getMe).mockResolvedValue({ ref: "telegram:100", name: "Bold", admin: true });

    render(<App />);

    expect(await screen.findByRole("menuitem", { name: "Бүх даалгавар" })).toBeInTheDocument();
    expect(screen.getByRole("menuitem", { name: "Settings" })).toBeInTheDocument();
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
