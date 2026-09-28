import { act, cleanup, render } from "@testing-library/react";
import { afterEach, beforeEach, expect, test, vi } from "vitest";
import * as api from "../api";
import { newlyWaiting, notificationsOn, setNotifications } from "./notify";
import { DesktopProviders } from "./status";

vi.mock("../api", async (importOriginal) => ({
  ...(await importOriginal<typeof import("../api")>()),
  getOverview: vi.fn(),
  getLive: vi.fn(),
}));

let visibility: DocumentVisibilityState = "visible";
Object.defineProperty(document, "visibilityState", { configurable: true, get: () => visibility });
function setVisibility(next: DocumentVisibilityState) {
  visibility = next;
  document.dispatchEvent(new Event("visibilitychange"));
}

class FakeNotification {
  static permission: NotificationPermission = "default";
  static answer: NotificationPermission = "granted";
  static requestPermission = vi.fn(async () => {
    FakeNotification.permission = FakeNotification.answer;
    return FakeNotification.answer;
  });
  static shown: FakeNotification[] = [];
  onclick: (() => void) | null = null;
  close = vi.fn();
  constructor(readonly title: string) {
    FakeNotification.shown.push(this);
  }
}

const overview: api.Overview = {
  configured: true, version: "0.2.0", name: "acme", configFile: "/c", stateDir: "/s",
  service: { name: "systemd user service dispatch.service", installed: true, running: true, detail: "active", notes: [] },
  findings: [],
};
const live: api.Live = {
  version: "0.2.0", name: "acme", running: 0, queued: 0, waitingOnYou: [{ taskId: 14, title: "Fix the login timeout" }],
  waitingOnOthers: 0, todayUsd: "0.00", monthUsd: "0.00", maxConcurrent: 2, projects: ["alm"],
  tasks: { running: [], queued: [], awaitingApproval: [] },
};

beforeEach(async () => {
  FakeNotification.permission = "default";
  FakeNotification.answer = "granted";
  FakeNotification.shown = [];
  vi.stubGlobal("Notification", FakeNotification);
  // Through the switch, not storage: under Node the page's localStorage has no methods, and notify keeps its own copy.
  await setNotifications(false);
  FakeNotification.requestPermission.mockClear();
  vi.mocked(api.getOverview).mockResolvedValue(overview);
});
afterEach(() => {
  // Unmounted before the tab is shown again below: a page still mounted would read the bot once more on that.
  cleanup();
  vi.unstubAllGlobals();
  vi.useRealTimers();
  vi.mocked(api.getLive).mockReset();
  setVisibility("visible");
  window.history.pushState(null, "", "/");
});

test("the switch asks the browser once and is remembered in it", async () => {
  expect(await setNotifications(true)).toBe("on");
  expect(await setNotifications(true)).toBe("on");

  expect(FakeNotification.requestPermission).toHaveBeenCalledTimes(1);
  expect(notificationsOn()).toBe(true);
});

test("a browser that refuses leaves the switch off and says why", async () => {
  FakeNotification.answer = "denied";

  expect(await setNotifications(true)).toBe("blocked");
  expect(notificationsOn()).toBe(false);
});

test("a browser without notifications cannot turn them on", async () => {
  vi.stubGlobal("Notification", undefined);

  expect(await setNotifications(true)).toBe("unsupported");
});

test("only a task that starts waiting is news, not those waiting when the page opened", () => {
  expect(newlyWaiting(null, [{ taskId: 14, title: "a" }])).toEqual([]);
  expect(newlyWaiting([14], [{ taskId: 14, title: "a" }, { taskId: 15, title: "b" }])).toEqual([{ taskId: 15, title: "b" }]);
});

test("a task that starts waiting on you is shown once, and a click opens its page", async () => {
  vi.useFakeTimers();
  FakeNotification.permission = "granted";
  await setNotifications(true);
  vi.mocked(api.getLive).mockResolvedValueOnce(live)
    .mockResolvedValue({ ...live, waitingOnYou: [...live.waitingOnYou, { taskId: 15, title: "Excel import" }] });
  render(<DesktopProviders><p>page</p></DesktopProviders>);

  await act(() => vi.advanceTimersByTimeAsync(0));
  expect(FakeNotification.shown).toHaveLength(0);
  await act(() => vi.advanceTimersByTimeAsync(5000));
  expect(FakeNotification.shown.map((notice) => notice.title)).toEqual(["#15 is waiting on you: Excel import"]);
  await act(() => vi.advanceTimersByTimeAsync(5000));
  expect(FakeNotification.shown).toHaveLength(1);

  act(() => FakeNotification.shown[0].onclick?.());
  expect(window.location.pathname).toBe("/tasks/15");
});

test("with notifications on, a hidden tab still reads every 30 seconds; with them off, not at all", async () => {
  vi.useFakeTimers();
  vi.mocked(api.getLive).mockResolvedValue(live);
  const { unmount } = render(<DesktopProviders><p>page</p></DesktopProviders>);
  await act(() => vi.advanceTimersByTimeAsync(0));
  act(() => setVisibility("hidden"));
  await act(() => vi.advanceTimersByTimeAsync(40_000));
  expect(api.getLive).toHaveBeenCalledTimes(1);
  unmount();
  act(() => setVisibility("visible"));

  vi.mocked(api.getLive).mockClear();
  FakeNotification.permission = "granted";
  await setNotifications(true);
  render(<DesktopProviders><p>page</p></DesktopProviders>);
  await act(() => vi.advanceTimersByTimeAsync(0));
  act(() => setVisibility("hidden"));
  await act(() => vi.advanceTimersByTimeAsync(35_000));
  expect(api.getLive).toHaveBeenCalledTimes(3); // 0 s, 5 s (the beat set in view), 35 s
});
