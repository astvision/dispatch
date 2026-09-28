import { Button, Drawer, Empty, Flex, Input, Result, Select, Spin, Typography } from "antd";
import { useMemo, useState } from "react";
import { listTasks, type TaskRow } from "../../api";
import { useT, type Key, type Translate } from "../../i18n/i18n";
import { useNarrow } from "../../useNarrow";
import { usePolling } from "../usePolling";
import { age, grouped, type Filter, type TaskGroup } from "./groups";
import TaskView from "./TaskView";

const FINISHED: Record<string, Key> = {
  COMPLETED: "tasks.state.completed", FAILED: "tasks.state.failed", REJECTED: "tasks.state.rejected", CANCELLED: "tasks.state.cancelled",
};

/** A row's state as a lamp colour and its word (a lamp is never colour alone), and the time it counts from. */
function stateOf(t: Translate, row: TaskRow, group: TaskGroup): { colour: string; word: string; since?: string | null } {
  switch (group) {
    case "waitingOnYou":
      return { colour: "amber", word: t(row.openQuestions ? "tasks.state.answer" : "tasks.state.approve"), since: row.since };
    case "waitingOnOthers":
      return { colour: "quiet", word: t("tasks.state.theirs", { name: row.requester ?? "" }), since: row.since };
    case "running":
      return { colour: "green", word: t("tasks.state.running"), since: row.startedAt };
    case "queued":
      return { colour: "quiet", word: t("tasks.state.queued"), since: row.queuedAt };
    default:
      return { colour: row.phase === "FAILED" ? "red" : row.phase === "COMPLETED" ? "green" : "quiet",
        word: t(FINISHED[row.phase ?? ""] ?? "tasks.state.completed"), since: row.completedAt };
  }
}

function Row({ row, group, now, onOpen }: { row: TaskRow; group: TaskGroup; now: Date; onOpen: () => void }) {
  const t = useT();
  const state = stateOf(t, row, group);
  const ago = age(state.since, now);
  return (
    <button type="button" className="task-row" onClick={onOpen}>
      <span className="mono task-id">#{row.taskId}</span>
      <span className="task-title">{row.title}</span>
      <span className="task-project">{row.project}</span>
      <span className="task-person">{row.requester}</span>
      <span className={`lamp ${state.colour}`}><i aria-hidden="true" /><span>{state.word}</span></span>
      <span className="task-age">{ago && t(`tasks.ago.${ago.unit}` as Key, { n: ago.n })}</span>
    </button>
  );
}

/**
 * Даалгавар (D-2): every task of the instance, grouped by what it needs — waiting on you first — with a side panel for
 * one task and its own page one click further. Read from the running bot every few seconds while in view.
 */
export default function TasksPage({ navigate }: { navigate: (path: string) => void }) {
  const t = useT();
  const narrow = useNarrow();
  const { data, error, reload } = usePolling((signal) => listTasks("group", signal));
  const [filter, setFilter] = useState<Filter>({ project: null, person: null, text: "" });
  const [open, setOpen] = useState<number | null>(null);
  const rows = data?.tasks ?? [];
  const now = new Date();
  const projects = useMemo(() => [...new Set(rows.map((row) => row.project))].sort(), [rows]);
  const people = useMemo(() => [...new Set(rows.map((row) => row.requester).filter((name): name is string => !!name))].sort(), [rows]);

  if (error?.code === "bot_not_running") {
    return (
      <Result status="info" title={t("tasks.botNotRunning")}
              extra={<Button onClick={() => navigate("/")}>{t("tasks.openOverview")}</Button>} />
    );
  }
  const groups = grouped(rows, filter, now);
  return (
    <Flex vertical gap={12}>
      <Typography.Title level={4} style={{ margin: 0 }}>{t("tasks.title")}</Typography.Title>
      <Flex wrap gap={8}>
        <Select aria-label={t("tasks.allProjects")} style={{ minWidth: 160 }} value={filter.project}
                options={[{ value: null, label: t("tasks.allProjects") }, ...projects.map((name) => ({ value: name, label: name }))]}
                onChange={(project) => setFilter({ ...filter, project })} />
        <Select aria-label={t("tasks.everyone")} style={{ minWidth: 140 }} value={filter.person}
                options={[{ value: null, label: t("tasks.everyone") }, ...people.map((name) => ({ value: name, label: name }))]}
                onChange={(person) => setFilter({ ...filter, person })} />
        <Input allowClear aria-label={t("tasks.search")} placeholder={t("tasks.search")} style={{ width: 220 }} value={filter.text}
               onChange={(e) => setFilter({ ...filter, text: e.target.value })} />
      </Flex>
      {!data && !error && <Spin />}
      {error && error.code !== "bot_not_running" && !data && <Typography.Text type="danger">{error.message}</Typography.Text>}
      {data && groups.length === 0 && <Empty description={t("tasks.none")} />}
      {groups.map(([group, list]) => (
        <section key={group} className="task-group">
          <h5 className="task-group-title">{t(`tasks.group.${group}` as Key)}</h5>
          {list.map((row) => <Row key={row.taskId} row={row} group={group} now={now} onOpen={() => setOpen(row.taskId)} />)}
        </section>
      ))}
      <Drawer open={open !== null} onClose={() => setOpen(null)} placement="right" size={narrow ? "100%" : 520} destroyOnHidden
              title={open === null ? null : `#${open}`}>
        {open !== null && (
          <TaskView key={open} taskId={open} layout="panel" onChanged={reload} onDetails={() => navigate(`/tasks/${open}`)} />
        )}
      </Drawer>
    </Flex>
  );
}
