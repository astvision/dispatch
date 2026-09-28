import { useCallback, useContext, useEffect, useState } from "react";
import { ApiError, getConfig, type ConfigView, type Saved } from "../api";
import { RestartContext } from "../restart";

/** The config as the management pages show it, and a save that sends the version it was read at. */
export function useManagedConfig() {
  const { mark } = useContext(RestartContext);
  const [config, setConfig] = useState<ConfigView | null>(null);
  const [loadError, setLoadError] = useState<ApiError | null>(null);
  const [saving, setSaving] = useState(false);
  const [saveError, setSaveError] = useState<ApiError | null>(null);

  const reload = useCallback(async () => {
    try {
      setConfig(await getConfig());
      setLoadError(null);
      setSaveError(null);
    } catch (e) {
      setLoadError(e instanceof ApiError ? e : new ApiError("unknown", String(e)));
    }
  }, []);

  useEffect(() => {
    void reload();
  }, [reload]);

  /**
   * @returns whether it saved; the page then shows the config as it is now. A save that needs a restart says so to the
   * shell at once, from the save itself: the page that saved may move on straight after.
   */
  const save = useCallback(async (call: (version: string) => Promise<Saved>) => {
    if (!config) return false;
    setSaving(true);
    setSaveError(null);
    try {
      const result = await call(config.version);
      // Only turn the notice on: a later save that changes nothing must not hide an earlier real save's notice.
      if (result.restartNeeded) mark(config.service.installed);
      await reload();
      return true;
    } catch (e) {
      setSaveError(e instanceof ApiError ? e : new ApiError("unknown", String(e)));
      return false;
    } finally {
      setSaving(false);
    }
  }, [config, reload, mark]);

  return { config, loadError, reload, save, saving, saveError };
}
