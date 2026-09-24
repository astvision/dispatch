import { Alert, Button, Result, Spin } from "antd";
import { createContext, useCallback, useContext, useEffect, useState, type ReactNode } from "react";
import { ApiError, getConfig, listProjects, listTasks, type ConfigView, type ProjectSummary, type Saved } from "../api";
import { useManagedConfig } from "../manage/useManagedConfig";

const asApiError = (e: unknown) => (e instanceof ApiError ? e : new ApiError("unknown", String(e)));

/**
 * The projects the viewer may see. An admin reads them from the config, so a project added or changed a moment ago is
 * listed as it is now; a member cannot read the config and gets their groups' projects from /api/projects.
 */
export function useProjects(admin: boolean) {
  const [projects, setProjects] = useState<ProjectSummary[] | null>(null);
  const [error, setError] = useState<ApiError | null>(null);

  useEffect(() => {
    let stopped = false;
    const load = admin
      ? getConfig().then((config) => config.projects.map(({ name, alias, baseBranch }) => ({ name, alias, baseBranch })))
      : listProjects().then((answer) => answer.projects);
    load.then((loaded) => !stopped && setProjects(loaded), (e: unknown) => !stopped && setError(asApiError(e)));
    return () => {
      stopped = true;
    };
  }, [admin]);

  return { projects, error };
}

/** How many of each project's tasks are not finished yet, for the home screen's rows; empty until it has loaded. */
export function useActiveCounts(scope: "me" | "group") {
  const [counts, setCounts] = useState<Record<string, number>>({});

  useEffect(() => {
    let stopped = false;
    listTasks(scope).then((answer) => {
      if (stopped) return;
      const next: Record<string, number> = {};
      for (const task of answer.tasks) {
        if (task.state !== "finished") next[task.project] = (next[task.project] ?? 0) + 1;
      }
      setCounts(next);
    }, () => {
      // The counts are a nicety on the rows; the project list itself still stands without them.
    });
    return () => {
      stopped = true;
    };
  }, [scope]);

  return counts;
}

/**
 * A config save takes effect on restart. The Mini App moves on from the screen that saved, so "restart to apply" is
 * kept here, above every screen, rather than on the screen that is gone.
 */
interface RestartNeeded {
  /** Null until something was saved; then whether a background service can be restarted from here. */
  installed: boolean | null;
  mark: (installed: boolean) => void;
}

export const RestartContext = createContext<RestartNeeded>({ installed: null, mark: () => {} });

export function useRestartNeeded() {
  const [installed, setInstalled] = useState<boolean | null>(null);
  return { installed, mark: setInstalled };
}

/**
 * The management config for a Mini App screen. A save that needs a restart says so to the shell at once, from the save
 * itself: the screen that saved usually moves on straight after, before any effect of its own would run.
 */
export function useMiniConfig() {
  const managed = useManagedConfig();
  const { mark } = useContext(RestartContext);
  const installed = managed.config?.service.installed ?? false;
  const { save: saveConfig } = managed;

  const save = useCallback((call: (version: string) => Promise<Saved>) => saveConfig(async (version) => {
    const result = await call(version);
    if (result.restartNeeded) mark(installed);
    return result;
  }), [saveConfig, mark, installed]);

  return { ...managed, save };
}

/** Loading, a load error and a refused save, as ManagedPage does, but without its own restart notice. */
export function MiniManaged({ config, loadError, saveError, reload, children }: {
  config: ConfigView | null;
  loadError: ApiError | null;
  saveError: ApiError | null;
  reload: () => Promise<void>;
  children: (config: ConfigView) => ReactNode;
}) {
  if (loadError) {
    return <Result status="warning" title="Тохиргоог уншиж чадсангүй" subTitle={loadError.message}
                   extra={<Button onClick={() => void reload()}>Дахин оролдох</Button>} />;
  }
  if (!config) return <Spin size="large"><div style={{ height: 200 }} /></Spin>;
  return (
    <>
      {saveError && (
        <Alert type="error" showIcon message={saveError.message} style={{ marginTop: 12 }}
               action={saveError.code === "changed" && <Button onClick={() => void reload()}>Дахин унших</Button>} />
      )}
      {children(config)}
    </>
  );
}
