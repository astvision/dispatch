import { useCallback, useEffect, useState } from "react";
import { ApiError } from "../api";

/**
 * Asks the server again and again (each call waits up to 25 s on Telegram) until it finds something. `quiet` turns true
 * after `hintAfterMs` without a result. A found value stays pending on the server, so the second effect of React's
 * StrictMode, or a request abandoned on unmount, loses nothing.
 */
export function useLongPoll<T>(active: boolean, ask: (signal: AbortSignal) => Promise<T | null>, hintAfterMs: number) {
  const [found, setFound] = useState<T | null>(null);
  const [quiet, setQuiet] = useState(false);
  const [error, setError] = useState<ApiError | null>(null);
  const [attempt, setAttempt] = useState(0);

  useEffect(() => {
    if (!active || found !== null || error) return;
    const controller = new AbortController();
    const timer = setTimeout(() => setQuiet(true), hintAfterMs);
    void (async () => {
      while (!controller.signal.aborted) {
        try {
          const result = await ask(controller.signal);
          if (result !== null && !controller.signal.aborted) {
            setFound(result);
            return;
          }
        } catch (e) {
          if (!controller.signal.aborted) setError(e instanceof ApiError ? e : new ApiError("unknown", String(e)));
          return;
        }
      }
    })();
    return () => {
      controller.abort();
      clearTimeout(timer);
    };
  }, [active, found, error, attempt, ask, hintAfterMs]);

  const retry = useCallback(() => {
    setError(null);
    setAttempt((n) => n + 1);
  }, []);
  const reset = useCallback(() => {
    setFound(null);
    setQuiet(false);
  }, []);

  return { found, quiet, error, retry, reset };
}
