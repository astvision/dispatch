import { createContext, useContext, useEffect, type ReactNode } from "react";
import { getLive, type ApiError, type Live, type Overview } from "../api";
import { RestartContext, useRestartNeeded } from "../restart";
import { useOverview } from "../useOverview";
import { chooseMember } from "./member";
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
}

const NO_LIVE = { live: null, botRunning: null, liveError: null, reloadLive: () => {}, needsMember: false };

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
  const { data, error, reload } = usePolling(getLive);
  const stopped = error?.code === "bot_not_running";
  const forgotten = error?.code === "not_owner";

  useEffect(() => {
    // A remembered admin the config no longer names: forget them, so the page asks again.
    if (forgotten) chooseMember(null);
  }, [forgotten]);

  const value: DesktopStatus = {
    ...status,
    live: stopped ? null : data,
    botRunning: stopped ? false : data ? true : null,
    liveError: error,
    reloadLive: reload,
    needsMember: error?.code === "choose_member" || forgotten,
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
