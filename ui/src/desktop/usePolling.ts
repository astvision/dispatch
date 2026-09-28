import { useCallback, useEffect, useRef, useState } from "react";
import { ApiError } from "../api";

/**
 * Reads now and again {@code intervalMs} after each answer while the page is in view (D-2). A hidden tab reads nothing
 * until it is shown, unless {@code hiddenIntervalMs} keeps a slower beat there (D-2b: notifications). A failure keeps
 * the last answer, so a restarting bot does not blank a page. One reading at a time: a tab shown again while one is out
 * waits for its answer, which sets the next beat.
 */
export function usePolling<T>(read: (signal: AbortSignal) => Promise<T>, intervalMs = 5000, key?: unknown,
                              hiddenIntervalMs?: number) {
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
    let reading = false;
    const hidden = () => document.visibilityState === "hidden";
    const tick = async () => {
      if (reading || (hidden() && hiddenIntervalMs === undefined)) return;
      reading = true;
      try {
        const answer = await latest.current(controller.signal);
        if (controller.signal.aborted) return;
        setData(answer);
        setError(null);
      } catch (e) {
        if (controller.signal.aborted) return;
        setError(e instanceof ApiError ? e : new ApiError("unknown", String(e)));
      } finally {
        reading = false;
      }
      clearTimeout(timer);
      timer = setTimeout(() => void tick(), hidden() ? hiddenIntervalMs ?? intervalMs : intervalMs);
    };
    const shown = () => {
      if (!hidden()) {
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
  }, [intervalMs, key, nonce, hiddenIntervalMs]);

  const reload = useCallback(() => setNonce((n) => n + 1), []);
  return { data, error, reload };
}
