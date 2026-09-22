import { useCallback, useEffect, useState } from "react";
import { ApiError, getConfig, type ConfigView, type Saved } from "../api";

/** The config as the management pages show it, and a save that sends the version it was read at. */
export function useManagedConfig() {
  const [config, setConfig] = useState<ConfigView | null>(null);
  const [loadError, setLoadError] = useState<ApiError | null>(null);
  const [saving, setSaving] = useState(false);
  const [saveError, setSaveError] = useState<ApiError | null>(null);
  const [saved, setSaved] = useState(false);

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

  /** @returns whether it saved; the page then shows the config as it is now. */
  const save = useCallback(async (call: (version: string) => Promise<Saved>) => {
    if (!config) return false;
    setSaving(true);
    setSaveError(null);
    try {
      const result = await call(config.version);
      setSaved(result.restartNeeded);
      await reload();
      return true;
    } catch (e) {
      setSaveError(e instanceof ApiError ? e : new ApiError("unknown", String(e)));
      return false;
    } finally {
      setSaving(false);
    }
  }, [config, reload]);

  return { config, loadError, reload, save, saving, saveError, saved };
}
