import { useCallback, useEffect, useRef, useState } from "react";
import { ApiError, getLogs, getOverview, restartService } from "./api";
import { useT } from "./i18n/i18n";

export type RestartPhase = "idle" | "restarting" | "done" | "failed";

/**
 * Restarts the background service, then asks the overview until the service runs again (spec: Restart). A lost
 * connection meanwhile is expected. After {@code timeoutMs} it gives up and shows the service log's last lines.
 * Unmounting stops the poll (its in-flight request too); a restart already running ignores a second call.
 */
export function useRestart(intervalMs = 2000, timeoutMs = 60_000) {
  const t = useT();
  const [phase, setPhase] = useState<RestartPhase>("idle");
  const [error, setError] = useState<string | null>(null);
  const [lines, setLines] = useState<string[]>([]);
  const running = useRef(false);
  const controllerRef = useRef<AbortController | null>(null);

  useEffect(() => () => controllerRef.current?.abort(), []);

  const restart = useCallback(async () => {
    if (running.current) return;
    running.current = true;
    const controller = new AbortController();
    controllerRef.current = controller;
    setPhase("restarting");
    setError(null);
    setLines([]);
    try {
      await restartService();
    } catch (e) {
      // The service restarting itself can end before it answers: the proxy in front then says 502, or the connection
      // drops. Only a refusal Dispatch wrote itself is final; otherwise the poll below tells whether it came back.
      const lostAnswer = e instanceof ApiError && (e.code === "http" || e.code === "unreachable");
      if (!lostAnswer || controller.signal.aborted) {
        if (!controller.signal.aborted) {
          setError(e instanceof ApiError ? e.message : String(e));
          setPhase("failed");
        }
        running.current = false;
        return;
      }
    }
    const deadline = Date.now() + timeoutMs;
    while (Date.now() < deadline && !controller.signal.aborted) {
      await new Promise((resolve) => setTimeout(resolve, intervalMs));
      if (controller.signal.aborted) break;
      try {
        if ((await getOverview(controller.signal)).service.running) {
          setPhase("done");
          running.current = false;
          return;
        }
      } catch {
        // expected while it restarts (and expected once aborted)
      }
    }
    if (controller.signal.aborted) {
      running.current = false;
      return;
    }
    try {
      setLines((await getLogs({ lines: 20 }, controller.signal)).lines);
    } catch {
      // the message below still says what happened
    }
    if (!controller.signal.aborted) {
      setError(t("restart.timedOut", { seconds: Math.round(timeoutMs / 1000) }));
      setPhase("failed");
    }
    running.current = false;
  }, [intervalMs, timeoutMs, t]);

  return { phase, error, lines, restart };
}
