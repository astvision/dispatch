import { useCallback, useEffect, useState } from "react";
import { ApiError, getSetupState, type SetupState } from "./api";

export function useSetupState() {
  const [state, setState] = useState<SetupState | null>(null);
  const [error, setError] = useState<ApiError | null>(null);

  const refresh = useCallback(async () => {
    try {
      setState(await getSetupState());
      setError(null);
    } catch (e) {
      setError(e instanceof ApiError ? e : new ApiError("unknown", String(e)));
    }
  }, []);

  useEffect(() => {
    void refresh();
  }, [refresh]);

  return { state, error, refresh };
}
