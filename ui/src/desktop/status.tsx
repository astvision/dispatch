import { createContext, useContext, type ReactNode } from "react";
import type { ApiError, Overview } from "../api";
import { RestartContext, useRestartNeeded } from "../restart";
import { useOverview } from "../useOverview";

export interface DesktopStatus {
  overview: Overview | null;
  error: ApiError | null;
  loading: boolean;
  reload: () => Promise<void>;
}

const StatusContext = createContext<DesktopStatus>({ overview: null, error: null, loading: false, reload: async () => {} });

/**
 * The overview, read once when it opens and again when something asks (Дахин шалгах, a restart, the end of setup): on
 * the desktop the strip's lamps and the Overview page show the same result. Nothing polls.
 */
export function StatusProvider({ children }: { children: ReactNode }) {
  const status = useOverview();
  return <StatusContext.Provider value={status}>{children}</StatusContext.Provider>;
}

/** What the desktop's shell and pages share: the status, and whether a save waits for a restart. */
export function DesktopProviders({ children }: { children: ReactNode }) {
  const restart = useRestartNeeded();
  return (
    <StatusProvider>
      <RestartContext.Provider value={restart}>{children}</RestartContext.Provider>
    </StatusProvider>
  );
}

export const useDesktopStatus = () => useContext(StatusContext);
