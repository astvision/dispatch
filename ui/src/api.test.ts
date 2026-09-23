import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";

/** What every request carries, which differs entirely between `dispatch ui` and the Mini App. */
describe("the launch data on every request", () => {
  const fetchMock = vi.fn();

  beforeEach(() => {
    vi.resetModules();
    fetchMock.mockReset();
    fetchMock.mockResolvedValue({ ok: true, status: 200, json: () => Promise.resolve({ ok: true }) });
    vi.stubGlobal("fetch", fetchMock);
  });

  afterEach(() => {
    vi.unstubAllGlobals();
    vi.doUnmock("./telegram");
  });

  it("sends the signed launch data inside Telegram", async () => {
    vi.doMock("./telegram", () => ({ inTelegram: true, initData: "user=%7B%22id%22%3A1%7D&hash=abc" }));
    const { getMe } = await import("./api");

    await getMe();

    const [, init] = fetchMock.mock.calls[0];
    expect(init.headers).toMatchObject({ Authorization: "tma user=%7B%22id%22%3A1%7D&hash=abc" });
  });

  it("sends no such header through dispatch ui, which has a cookie instead", async () => {
    vi.doMock("./telegram", () => ({ inTelegram: false, initData: null }));
    const { getOverview } = await import("./api");

    await getOverview();

    const [, init] = fetchMock.mock.calls[0];
    expect(init.headers ?? {}).not.toHaveProperty("Authorization");
  });

  it("keeps the content type of a post and adds the launch data beside it", async () => {
    vi.doMock("./telegram", () => ({ inTelegram: true, initData: "signed" }));
    const { cancelTask } = await import("./api");

    await cancelTask(7);

    const [path, init] = fetchMock.mock.calls[0];
    expect(path).toBe("/api/tasks/cancel");
    expect(init.headers).toMatchObject({ "Content-Type": "application/json", Authorization: "tma signed" });
    expect(init.body).toBe(JSON.stringify({ taskId: 7 }));
  });

  it("explains an unreachable Dispatch differently inside Telegram, where there is no link to reopen", async () => {
    vi.doMock("./telegram", () => ({ inTelegram: true, initData: "signed" }));
    const { getMe, ApiError } = await import("./api");
    fetchMock.mockRejectedValue(new TypeError("network"));

    await expect(getMe()).rejects.toThrow(ApiError);
    await expect(getMe()).rejects.toThrow(/restarting/);
  });

  it("passes the server's own error code through, so a page can answer it", async () => {
    vi.doMock("./telegram", () => ({ inTelegram: true, initData: "signed" }));
    const { getMe, ApiError } = await import("./api");
    fetchMock.mockResolvedValue({
      ok: false,
      status: 401,
      json: () => Promise.resolve({ error: "expired", message: "this Mini App has been open too long" }),
    });

    await expect(getMe()).rejects.toMatchObject({ code: "expired" });
    expect(ApiError).toBeDefined();
  });
});
