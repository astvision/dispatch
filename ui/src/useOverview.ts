import { useCallback, useEffect, useState } from "react";
import { ApiError, getOverview, type Overview } from "./api";

export function useOverview() {
  const [overview, setOverview] = useState<Overview | null>(null);
  const [error, setError] = useState<ApiError | null>(null);
  const [loading, setLoading] = useState(true);

  const reload = useCallback(async () => {
    setLoading(true);
    setError(null);
    try {
      setOverview(await getOverview());
    } catch (e) {
      setError(e instanceof ApiError ? e : new ApiError("unknown", String(e)));
    } finally {
      setLoading(false);
    }
  }, []);

  useEffect(() => {
    void reload();
  }, [reload]);

  return { overview, error, loading, reload };
}
