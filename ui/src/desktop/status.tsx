import { createContext, useCallback, useContext, useEffect, useRef, useState, type ReactNode } from "react";
import { getLive, type ApiError, type Live, type Overview } from "../api";
import { RestartContext, useRestartNeeded } from "../restart";
import { useT } from "../i18n/i18n";
import { useOverview } from "../useOverview";
import { chooseMember } from "./member";
import { newlyWaiting, notificationsOn, setNotifications, showNotice, type Switched } from "./notify";
import { usePolling } from "./usePolling";

export interface DesktopStatus {
  overview: Overview | null;
  error: ApiError | null;
  loading: boolean;
  reload: () => Promise<void>;
  /** From the running bot's desk port (D-2); null while it does not answer, and always in the Mini App. */
  live: Live | null;
  /** True after the bot answered, false after it said it is not running; null before either. */
  botRunning: boolean | null;
  liveError: ApiError | null;
  reloadLive: () => void;
  /** The desk cannot tell which admin the desktop acts as: the page asks (MemberChoice). */
  needsMember: boolean;
  /** The Мэдэгдэл switch (D-2b): on only when this browser allows notifications and the owner turned them on here. */
  notifications: boolean;
  setNotifications: (on: boolean) => Promise<Switched>;
}

const NO_LIVE = {
  live: null, botRunning: null, liveError: null, reloadLive: () => {}, needsMember: false, notifications: false,
  setNotifications: async (): Promise<Switched> => "unsupported",
};

/** With notifications on, a hidden tab still reads the bot this often, to tell of a task that starts waiting (D-2b). */
const HIDDEN_BEAT_MS = 30_000;

const StatusContext = createContext<DesktopStatus>({ overview: null, error: null, loading: false, reload: async () => {}, ...NO_LIVE });

/**
 * The overview, read once when it opens and again when something asks (Дахин шалгах, a restart, the end of setup): on
 * the desktop the strip's lamps and the Overview page show the same result. The Mini App uses it alone, with no bot to
 * read through a desk port.
 */
export function StatusProvider({ children }: { children: ReactNode }) {
  const status = useOverview();
  return <StatusContext.Provider value={{ ...status, ...NO_LIVE }}>{children}</StatusContext.Provider>;
}

/** The running bot, read every few seconds while the desktop is in view (D-2): the strip's task lamps. */
function LiveReading({ children }: { children: ReactNode }) {
  const status = useContext(StatusContext);
  const t = useT();
  const [notifying, setNotifying] = useState(notificationsOn);
  const { data, error, reload } = usePolling(getLive, 5000, undefined, notifying ? HIDDEN_BEAT_MS : undefined);
  const seen = useRef<number[] | null>(null);
  const stopped = error?.code === "bot_not_running";
  const refused = error?.code === "not_owner";

  useEffect(() => {
    // A remembered admin the desk no longer accepts: forget them and read again at once. With one candidate the desk
    // then answers; with several it says choose_member, and only that makes the page ask.
    if (!refused) return;
    chooseMember(null);
    reload();
  }, [refused, reload]);

  useEffect(() => {
    if (!data) return;
    const fresh = newlyWaiting(seen.current, data.waitingOnYou);
    seen.current = data.waitingOnYou.map((task) => task.taskId);
    if (!notifying) return;
    for (const task of fresh) {
      if (!showNotice(t("notify.waiting", { id: task.taskId, title: task.title }), task.taskId)) {
        // A browser that would not show one (its permission taken back) turns the switch off rather than fail every beat.
        void setNotifications(false).then(() => setNotifying(false));
        return;
      }
    }
  }, [data, notifying, t]);

  const switchNotifications = useCallback(async (on: boolean) => {
    const now = await setNotifications(on);
    setNotifying(now === "on");
    return now;
  }, []);

  const value: DesktopStatus = {
    ...status,
    live: stopped ? null : data,
    botRunning: stopped ? false : data ? true : null,
    liveError: error,
    reloadLive: reload,
    needsMember: error?.code === "choose_member",
    notifications: notifying,
    setNotifications: switchNotifications,
  };
  return <StatusContext.Provider value={value}>{children}</StatusContext.Provider>;
}

/** What the desktop's shell and pages share: the status, the bot's live reading, and whether a save waits for a restart. */
export function DesktopProviders({ children }: { children: ReactNode }) {
  const restart = useRestartNeeded();
  return (
    <StatusProvider>
      <LiveReading>
        <RestartContext.Provider value={restart}>{children}</RestartContext.Provider>
      </LiveReading>
    </StatusProvider>
  );
}

export const useDesktopStatus = () => useContext(StatusContext);
