import { useCallback, useEffect, useRef, useState } from "react";
import { ApiError } from "../api";

/**
 * Reads now and again {@code intervalMs} after each answer while the page is in view (D-2); a hidden tab reads nothing
 * until it is shown. A failure keeps the last answer, so a restarting bot does not blank a page.
 */
export function usePolling<T>(read: (signal: AbortSignal) => Promise<T>, intervalMs = 5000, key?: unknown) {
  const [data, setData] = useState<T | null>(null);
  const [error, setError] = useState<ApiError | null>(null);
  const [nonce, setNonce] = useState(0);
  const latest = useRef(read);

  useEffect(() => {
    latest.current = read;
  });

  useEffect(() => {
    const controller = new AbortController();
    let timer: ReturnType<typeof setTimeout> | undefined;
    const tick = async () => {
      if (document.visibilityState === "hidden") return;
      try {
        const answer = await latest.current(controller.signal);
        if (controller.signal.aborted) return;
        setData(answer);
        setError(null);
      } catch (e) {
        if (controller.signal.aborted) return;
        setError(e instanceof ApiError ? e : new ApiError("unknown", String(e)));
      }
      timer = setTimeout(() => void tick(), intervalMs);
    };
    const shown = () => {
      if (document.visibilityState === "visible") {
        clearTimeout(timer);
        void tick();
      }
    };
    document.addEventListener("visibilitychange", shown);
    void tick();
    return () => {
      controller.abort();
      clearTimeout(timer);
      document.removeEventListener("visibilitychange", shown);
    };
  }, [intervalMs, key, nonce]);

  const reload = useCallback(() => setNonce((n) => n + 1), []);
  return { data, error, reload };
}
