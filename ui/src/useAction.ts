import { useCallback, useState } from "react";
import { ApiError } from "./api";

/** One call's busy and error state, for a button that asks the server something. */
export function useAction() {
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<ApiError | null>(null);

  const run = useCallback(async <T,>(call: () => Promise<T>): Promise<T | undefined> => {
    setBusy(true);
    setError(null);
    try {
      return await call();
    } catch (e) {
      setError(e instanceof ApiError ? e : new ApiError("unknown", String(e)));
      return undefined;
    } finally {
      setBusy(false);
    }
  }, []);

  const clear = useCallback(() => setError(null), []);
  return { busy, error, run, clear };
}
