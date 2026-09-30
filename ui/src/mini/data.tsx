import { Alert, Button, Result, Spin } from "antd";
import { useEffect, useRef, useState, type ReactNode } from "react";
import { ApiError, getConfig, listProjects, listTasks, type ConfigView, type ProjectSummary } from "../api";

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

/** Loading, a load error and a refused save, as ManagedPage does, but without its own restart notice. */
export function MiniManaged({ config, loadError, saveError, reload, children }: {
  config: ConfigView | null;
  loadError: ApiError | null;
  saveError: ApiError | null;
  reload: () => Promise<void>;
  children: (config: ConfigView) => ReactNode;
}) {
  const errorRef = useRef<HTMLDivElement>(null);
  // The save button sits at the bottom of a long form, so an error shown up here would go unseen without the scroll.
  useEffect(() => {
    if (saveError) errorRef.current?.scrollIntoView?.({ behavior: "smooth", block: "center" });
  }, [saveError]);
  if (loadError) {
    return <Result status="warning" title="Тохиргоог уншиж чадсангүй" subTitle={loadError.message}
                   extra={<Button onClick={() => void reload()}>Дахин оролдох</Button>} />;
  }
  if (!config) return <Spin size="large"><div style={{ height: 200 }} /></Spin>;
  return (
    <>
      {saveError && (
        <div ref={errorRef}><Alert type="error" showIcon message={saveError.message} style={{ marginTop: 12 }}
               action={saveError.code === "changed" && <Button onClick={() => void reload()}>Дахин унших</Button>} /></div>
      )}
      {children(config)}
    </>
  );
}
