import { createContext, useState } from "react";

/**
 * A config save takes effect on restart. Each shell keeps "restart to apply" above its pages rather than on the page
 * that saved, which may be gone by then: the desktop as a lamp in its strip, the Mini App above every screen.
 */
export interface RestartNeeded {
  /** Null until something was saved; then whether a background service can be restarted from here. */
  installed: boolean | null;
  /** Null once a restart applied it. */
  mark: (installed: boolean | null) => void;
}

export const RestartContext = createContext<RestartNeeded>({ installed: null, mark: () => {} });

export function useRestartNeeded(): RestartNeeded {
  const [installed, setInstalled] = useState<boolean | null>(null);
  return { installed, mark: setInstalled };
}
