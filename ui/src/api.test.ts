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

  it("says in the page's language that a server cannot be reached", async () => {
    vi.doMock("./telegram", () => ({ inTelegram: false, initData: null }));
    fetchMock.mockRejectedValue(new TypeError("Failed to fetch"));
    const { render } = await import("@testing-library/react");
    const { createElement } = await import("react");
    const { LanguageProvider } = await import("./i18n/i18n");
    const { getOverview } = await import("./api");
    render(createElement(LanguageProvider, { fixed: "mn", children: null }));

    await expect(getOverview()).rejects.toThrow("dispatch ui ажиллахгүй байна; дахин эхлүүлээд хэвлэсэн холбоосыг нь нээнэ үү");
  });

  it("names the desktop's chosen admin to the desk once there is one", async () => {
    vi.doMock("./telegram", () => ({ inTelegram: false, initData: null }));
    const { chooseMember } = await import("./desktop/member");
    const { getLive } = await import("./api");
    chooseMember("telegram:300");

    await getLive();

    const [, init] = fetchMock.mock.calls[0];
    expect(init.headers).toMatchObject({ "X-Dispatch-Member": "telegram:300" });
    chooseMember(null);
  });

  it("asks for the page's language, so the server writes its messages in it", async () => {
    vi.doMock("./telegram", () => ({ inTelegram: false, initData: null }));
    vi.doMock("./i18n/i18n", () => ({ currentLanguage: () => "mn" }));
    const { getOverview } = await import("./api");

    await getOverview();

    const [, init] = fetchMock.mock.calls[0];
    expect(init.headers).toMatchObject({ "Accept-Language": "mn" });
    vi.doUnmock("./i18n/i18n");
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

describe("the desk's own calls", () => {
  const fetchMock = vi.fn();
  beforeEach(() => {
    vi.resetModules();
    fetchMock.mockReset();
    fetchMock.mockResolvedValue({ ok: true, status: 200, json: () => Promise.resolve({ taskId: 12 }) });
    vi.stubGlobal("fetch", fetchMock);
    vi.doMock("./telegram", () => ({ inTelegram: false, initData: null }));
  });
  afterEach(() => {
    vi.unstubAllGlobals();
    vi.doUnmock("./telegram");
  });

  it("gives a task with its project, words and priority", async () => {
    const { giveTask } = await import("./api");
    await expect(giveTask("alm", "Fix the login timeout", "URGENT")).resolves.toEqual({ taskId: 12 });
    const [path, init] = fetchMock.mock.calls[0];
    expect(path).toBe("/api/tasks/new");
    expect(JSON.parse(init.body)).toEqual({ project: "alm", text: "Fix the login timeout", priority: "URGENT" });
  });
});
