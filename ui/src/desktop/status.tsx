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
 * The overview, read once when the desktop opens and again when something asks (Дахин шалгах, a restart, the end of
 * setup): the strip's lamps and the Overview page show the same result. Nothing polls.
 */
export function DesktopProviders({ children }: { children: ReactNode }) {
  const status = useOverview();
  const restart = useRestartNeeded();
  return (
    <StatusContext.Provider value={status}>
      <RestartContext.Provider value={restart}>{children}</RestartContext.Provider>
    </StatusContext.Provider>
  );
}

export const useDesktopStatus = () => useContext(StatusContext);
