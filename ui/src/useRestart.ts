import { useCallback, useState } from "react";
import { ApiError, getLogs, getOverview, restartService } from "./api";

export type RestartPhase = "idle" | "restarting" | "done" | "failed";

/**
 * Restarts the background service, then asks the overview until the service runs again (spec: Restart). A lost
 * connection meanwhile is expected. After {@code timeoutMs} it gives up and shows the service log's last lines.
 */
export function useRestart(intervalMs = 2000, timeoutMs = 60_000) {
  const [phase, setPhase] = useState<RestartPhase>("idle");
  const [error, setError] = useState<string | null>(null);
  const [lines, setLines] = useState<string[]>([]);

  const restart = useCallback(async () => {
    setPhase("restarting");
    setError(null);
    setLines([]);
    try {
      await restartService();
    } catch (e) {
      setError(e instanceof ApiError ? e.message : String(e));
      setPhase("failed");
      return;
    }
    const deadline = Date.now() + timeoutMs;
    while (Date.now() < deadline) {
      await new Promise((resolve) => setTimeout(resolve, intervalMs));
      try {
        if ((await getOverview()).service.running) {
          setPhase("done");
          return;
        }
      } catch {
        // expected while it restarts
      }
    }
    try {
      setLines((await getLogs({ lines: 20 })).lines);
    } catch {
      // the message below still says what happened
    }
    setError(`Dispatch is not running again after ${Math.round(timeoutMs / 1000)} seconds. The service log's last lines:`);
    setPhase("failed");
  }, [intervalMs, timeoutMs]);

  return { phase, error, lines, restart };
}
