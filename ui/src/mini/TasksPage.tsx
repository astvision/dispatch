import { Alert, Button, Card, Empty, Popconfirm, Space, Spin, Table, Tag, Typography } from "antd";
import { useCallback, useEffect, useState } from "react";
import { ApiError, cancelTask, listTasks, retryTask, taskTimeline, type TaskRow, type TaskState, type Timeline } from "../api";
import { useAction } from "../useAction";

/** Mongolian, like the bot: these are the pages members read inside Telegram. */
const STATE_LABELS: Record<TaskState, { text: string; color: string }> = {
  running: { text: "Ажиллаж байна", color: "processing" },
  queued: { text: "Дараалалд", color: "default" },
  awaitingApproval: { text: "Зөвшөөрөл хүлээж байна", color: "warning" },
  finished: { text: "Дууссан", color: "success" },
};

const CANCELLABLE: TaskState[] = ["running", "queued", "awaitingApproval"];

/**
 * A member's own tasks, or every task of an admin's groups (spec: Task pages). The two differ in which list they ask
 * for and whether a row may be retried, not in how they look.
 *
 * <p>It polls rather than saving anything, so it follows LogsPage rather than useManagedConfig.
 */
export default function TasksPage({ scope, intervalMs = 5000 }: { scope: "me" | "group"; intervalMs?: number }) {
  const [tasks, setTasks] = useState<TaskRow[] | null>(null);
  const [error, setError] = useState<ApiError | null>(null);
  const [timelines, setTimelines] = useState<Record<number, Timeline | undefined>>({});
  const acting = useAction();
  const [reloadToken, setReloadToken] = useState(0);

  useEffect(() => {
    let stopped = false;
    let timer: ReturnType<typeof setTimeout> | undefined;
    const tick = async () => {
      try {
        const answer = await listTasks(scope);
        if (!stopped) {
          setTasks(answer.tasks);
          setError(null);
        }
      } catch (e) {
        if (!stopped) setError(e instanceof ApiError ? e : new ApiError("unknown", String(e)));
      }
      if (!stopped) timer = setTimeout(() => void tick(), intervalMs);
    };
    void tick();
    return () => {
      stopped = true;
      clearTimeout(timer);
    };
  }, [scope, intervalMs, reloadToken]);

  const act = useCallback(async (call: () => Promise<unknown>) => {
    const done = await acting.run(call);
    // Reload at once rather than waiting out the poll, so the row reflects what just happened.
    if (done !== undefined) setReloadToken((token) => token + 1);
  }, [acting]);

  const expand = useCallback(async (taskId: number) => {
    if (timelines[taskId]) return;
    const answer = await acting.run(() => taskTimeline(taskId));
    if (answer) setTimelines((current) => ({ ...current, [taskId]: answer }));
  }, [acting, timelines]);

  const columns = [
    { title: "#", dataIndex: "taskId", width: 64 },
    {
      title: "Даалгавар",
      dataIndex: "title",
      render: (title: string, task: TaskRow) => (
        <Space direction="vertical" size={0}>
          <Typography.Text>{title}</Typography.Text>
          <Typography.Text type="secondary" style={{ fontSize: 12 }}>
            {task.project}
            {scope === "group" && task.requester ? ` · ${task.requester}` : ""}
          </Typography.Text>
        </Space>
      ),
    },
    {
      title: "Төлөв",
      dataIndex: "state",
      width: 140,
      render: (state: TaskState) => <Tag color={STATE_LABELS[state].color}>{STATE_LABELS[state].text}</Tag>,
    },
    {
      title: "",
      key: "actions",
      width: 150,
      render: (_: unknown, task: TaskRow) => (
        <Space>
          {CANCELLABLE.includes(task.state) && (
            <Popconfirm title={`#${task.taskId} цуцлах уу?`} okText="Цуцлах" cancelText="Болих"
                        onConfirm={() => void act(() => cancelTask(task.taskId))}>
              <Button size="small" danger aria-label={`Цуцлах ${task.taskId}`}>Цуцлах</Button>
            </Popconfirm>
          )}
          {/* Someone else's task is an admin's to stop, never to start again for them. */}
          {task.mine && task.state === "finished" && (
            <Button size="small" aria-label={`Дахин эхлүүлэх ${task.taskId}`}
                    onClick={() => void act(() => retryTask(task.taskId))}>
              Дахин
            </Button>
          )}
        </Space>
      ),
    },
  ];

  return (
    <Card title={scope === "me" ? "Миний даалгаврууд" : "Бүх даалгавар"}>
      {error && <Alert type="error" showIcon message={error.message} style={{ marginBottom: 12 }} />}
      {acting.error && <Alert type="error" showIcon closable message={acting.error.message} onClose={acting.clear}
                              style={{ marginBottom: 12 }} />}
      {!tasks && !error && <Spin />}
      {tasks && tasks.length === 0 && <Empty description="Одоогоор даалгавар алга." />}
      {tasks && tasks.length > 0 && (
        <Table<TaskRow>
          size="small"
          pagination={false}
          rowKey="taskId"
          dataSource={tasks}
          columns={columns}
          expandable={{
            onExpand: (expanded, task) => expanded && void expand(task.taskId),
            expandedRowRender: (task) => <TaskDetail timeline={timelines[task.taskId]} />,
          }}
        />
      )}
    </Card>
  );
}

/** A task's runs, or the note that someone else's task shows its headline alone (ADR 0020). */
function TaskDetail({ timeline }: { timeline: Timeline | undefined }) {
  if (!timeline) return <Spin size="small" />;
  if (timeline.headline) {
    return <Typography.Text type="secondary">Энэ бол өөр гишүүний даалгавар: гарчгаас цаашгүй.</Typography.Text>;
  }
  return (
    <Space direction="vertical" size={4} style={{ width: "100%" }}>
      {timeline.prUrl && <a href={timeline.prUrl} target="_blank" rel="noreferrer">Pull request</a>}
      {(timeline.runs ?? []).map((run) => (
        <Typography.Text key={run.seq} style={{ fontSize: 12 }}>
          #{run.seq} · {run.kind} · {run.status}
          {run.failureReason ? ` · ${run.failureReason}` : ""}
        </Typography.Text>
      ))}
      {timeline.costUsd && <Typography.Text type="secondary" style={{ fontSize: 12 }}>${timeline.costUsd}</Typography.Text>}
    </Space>
  );
}
