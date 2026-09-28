const KEY = "dispatch.notify";
let remembered = false;

export type Switched = "on" | "off" | "blocked" | "unsupported";

/** Whether this browser has notifications at all (127.0.0.1 is a secure context, so `dispatch ui` qualifies). */
export function notificationsSupported(): boolean {
  return typeof window.Notification === "function";
}

/** The Мэдэгдэл switch (D-2b): on only when this browser allows them and the owner turned them on here. */
export function notificationsOn(): boolean {
  if (!notificationsSupported() || Notification.permission !== "granted") return false;
  try {
    return window.localStorage.getItem(KEY) === "on" || remembered;
  } catch {
    return remembered;
  }
}

/** Turns the switch on — asking the browser the first time — or off; says what it now is, and why not when it cannot. */
export async function setNotifications(on: boolean): Promise<Switched> {
  if (!on) {
    remember(false);
    return "off";
  }
  if (!notificationsSupported()) return "unsupported";
  const permission = Notification.permission === "default" ? await Notification.requestPermission() : Notification.permission;
  if (permission !== "granted") {
    remember(false);
    return "blocked";
  }
  remember(true);
  return "on";
}

function remember(on: boolean) {
  remembered = on;
  try {
    window.localStorage.setItem(KEY, on ? "on" : "off");
  } catch {
    // Remembered for this visit only.
  }
}

/** The tasks now waiting on you that the reading before did not hold; the first reading is where the page starts, not news. */
export function newlyWaiting(before: number[] | null, now: { taskId: number; title: string }[]) {
  if (before === null) return [];
  return now.filter((task) => !before.includes(task.taskId));
}

/** Opens a task's page from outside the page's router (a notification's click): the router follows popstate. */
export function openTaskPage(taskId: number) {
  window.history.pushState(null, "", `/tasks/${taskId}`);
  window.dispatchEvent(new PopStateEvent("popstate"));
}

/** Shows one notice; its click opens the task's page. False when the browser would not show it. */
export function showNotice(text: string, taskId: number): boolean {
  if (!notificationsOn()) return false;
  try {
    const notice = new Notification(text, { tag: `dispatch-task-${taskId}` });
    notice.onclick = () => {
      window.focus();
      openTaskPage(taskId);
      notice.close();
    };
    return true;
  } catch (e) {
    // Some browsers (Chrome on Android) show notifications only through a service worker, which this page has not.
    console.warn("dispatch: the browser would not show a notification", e);
    return false;
  }
}
