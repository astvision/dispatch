import { Button, Flex, Result, Spin, Typography } from "antd";
import { getLogs, taskTimeline, type Timeline } from "../../api";
import { useT, type Key } from "../../i18n/i18n";
import { LogRows } from "../../manage/LogsPage";
import { usePolling } from "../usePolling";
import { age } from "./groups";
import TaskView from "./TaskView";

function Facts({ timeline }: { timeline: Timeline }) {
  const t = useT();
  const rows: [Key, React.ReactNode][] = [
    ["tasks.project", timeline.project],
    ["tasks.requester", timeline.requester],
    ["tasks.priorityLabel", t(`tasks.priority.${timeline.priority}` as Key)],
    ["tasks.branch", timeline.branch && <span className="mono">{timeline.branch}</span>],
    ["tasks.cost", timeline.costUsd ? `$${timeline.costUsd}` : t("tasks.noCost")],
  ];
  return (
    <section className="panel-box">
      <h5 className="task-group-title" style={{ marginTop: 0 }}>{t("tasks.facts")}</h5>
      <dl className="task-facts">
        {rows.filter(([, value]) => value).map(([label, value]) => (
          <div key={label}><dt>{t(label)}</dt><dd>{value}</dd></div>
        ))}
      </dl>
      {timeline.prUrl && <Typography.Link href={timeline.prUrl} target="_blank" rel="noreferrer">{t("tasks.pullRequest")}</Typography.Link>}
    </section>
  );
}

/** Each run in order: when it started, what it was, how it ended, how long it took and what it cost — the task's timeline. */
function Runs({ timeline }: { timeline: Timeline }) {
  const t = useT();
  return (
    <section className="panel-box">
      <h5 className="task-group-title" style={{ marginTop: 0 }}>{t("tasks.timeline")}</h5>
      {(timeline.runs ?? []).map((run) => {
        const took = run.startedAt && run.finishedAt ? age(run.startedAt, new Date(run.finishedAt)) : null;
        return (
          <div key={run.seq} className="task-run">
            <span className="mono task-age">{run.startedAt ? new Date(run.startedAt).toTimeString().slice(0, 5) : ""}</span>
            <span>{t(`tasks.kind.${run.kind}` as Key)}</span>
            <span className="task-age">{t(`tasks.status.${run.status}` as Key)}</span>
            <span className="task-age">{took && t(`tasks.ago.${took.unit}` as Key, { n: took.n })}</span>
            <span>{run.costUsd ? `$${run.costUsd}` : t("tasks.noCost")}</span>
          </div>
        );
      })}
    </section>
  );
}

/**
 * A task's own page (D-2): the same view as its side panel, beside its facts, its runs and its own rows of the service
 * log. The log is dispatch ui's to read, so it shows even while the bot is stopped.
 */
export default function TaskPage({ taskId, navigate }: { taskId: number; navigate: (path: string) => void }) {
  const t = useT();
  const timeline = usePolling((signal) => taskTimeline(taskId, signal), 5000, taskId);
  const logs = usePolling((signal) => getLogs({ lines: 200, task: taskId }, signal), 5000, taskId);
  const botDown = timeline.error?.code === "bot_not_running";

  return (
    <Flex vertical gap={12}>
      <Button type="link" onClick={() => navigate("/tasks")} style={{ alignSelf: "flex-start", paddingInline: 0 }}>
        ← {t("tasks.back")}
      </Button>
      <div className="task-page">
        <section className="panel-box">
          {botDown
            ? <Result status="info" title={t("tasks.botNotRunning")}
                      extra={<Button onClick={() => navigate("/")}>{t("tasks.openOverview")}</Button>} />
            : <TaskView taskId={taskId} layout="page" />}
        </section>
        <Flex vertical gap={12}>
          {timeline.data && <Facts timeline={timeline.data} />}
          {timeline.data?.runs && <Runs timeline={timeline.data} />}
          <section className="panel-box">
            <h5 className="task-group-title" style={{ marginTop: 0 }}>{t("tasks.log")}</h5>
            {logs.data ? <LogRows lines={logs.data.lines} /> : logs.error
              ? <Typography.Text type="danger">{logs.error.message}</Typography.Text> : <Spin />}
          </section>
        </Flex>
      </div>
    </Flex>
  );
}
