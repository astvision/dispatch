import { Alert, Button, Empty, Flex, Popconfirm, Space, Spin, Tag, Typography } from "antd";
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
 * <p>A phone is too narrow for a table, so each task is a row that wraps: the title on its own line, everything else
 * underneath. It polls rather than saving anything, so it follows LogsPage rather than useManagedConfig.
 */
export default function TasksPage({ scope, intervalMs = 5000, heading = true, project }: {
  scope: "me" | "group";
  intervalMs?: number;
  /** False when the screen already names this list, so the phone does not say it twice. */
  heading?: boolean;
  /** Only this project's tasks, on its own page. */
  project?: string;
}) {
  const [tasks, setTasks] = useState<TaskRow[] | null>(null);
  const [error, setError] = useState<ApiError | null>(null);
  const [open, setOpen] = useState<number | null>(null);
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
          setTasks(project === undefined ? answer.tasks : answer.tasks.filter((task) => task.project === project));
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
  }, [scope, intervalMs, reloadToken, project]);

  const act = useCallback(async (call: () => Promise<unknown>) => {
    const done = await acting.run(call);
    // Reload at once rather than waiting out the poll, so the row reflects what just happened.
    if (done !== undefined) setReloadToken((token) => token + 1);
  }, [acting]);

  const toggle = useCallback(async (taskId: number) => {
    if (open === taskId) {
      setOpen(null);
      return;
    }
    setOpen(taskId);
    if (timelines[taskId]) return;
    const answer = await acting.run(() => taskTimeline(taskId));
    if (answer) setTimelines((current) => ({ ...current, [taskId]: answer }));
  }, [acting, open, timelines]);

  return (
    <>
      {heading && (
        <Typography.Title level={4} style={{ margin: "0 0 12px" }}>
          {scope === "me" ? "Миний даалгаврууд" : "Бүх даалгавар"}
        </Typography.Title>
      )}
      {error && <Alert type="error" showIcon message={error.message} style={{ marginBottom: 12 }} />}
      {acting.error && <Alert type="error" showIcon closable message={acting.error.message} onClose={acting.clear}
                              style={{ marginBottom: 12 }} />}
      {!tasks && !error && <Spin />}
      {tasks && tasks.length === 0 && <Empty description="Одоогоор даалгавар алга." />}
      {tasks && tasks.length > 0 && (
        <Flex vertical>
          {tasks.map((task, index) => (
            <div
              key={task.taskId}
              style={{
                padding: "14px 0",
                borderTop: index === 0 ? undefined : "1px solid rgba(128,128,128,0.2)",
              }}
            >
              <Task
                task={task}
                scope={scope}
                open={open === task.taskId}
                timeline={timelines[task.taskId]}
                onToggle={() => void toggle(task.taskId)}
                onCancel={() => void act(() => cancelTask(task.taskId))}
                onRetry={() => void act(() => retryTask(task.taskId))}
              />
            </div>
          ))}
        </Flex>
      )}
    </>
  );
}

function Task({ task, scope, open, timeline, onToggle, onCancel, onRetry }: {
  task: TaskRow;
  scope: "me" | "group";
  open: boolean;
  timeline: Timeline | undefined;
  onToggle: () => void;
  onCancel: () => void;
  onRetry: () => void;
}) {
  const state = STATE_LABELS[task.state];
  return (
    <Flex vertical gap={8}>
      {/* The whole line is the target: a tap opens the runs, which is what a thumb can hit reliably. */}
      <Typography.Link onClick={onToggle} style={{ fontSize: 16, lineHeight: 1.35, textAlign: "left" }}>
        #{task.taskId} {task.title}
      </Typography.Link>
      <Flex wrap gap={8} align="center">
        <Tag color={state.color} style={{ marginInlineEnd: 0 }}>{state.text}</Tag>
        <Typography.Text type="secondary" style={{ fontSize: 13 }}>
          {task.project}
          {scope === "group" && task.requester ? ` · ${task.requester}` : ""}
          {task.costUsd ? ` · $${task.costUsd}` : ""}
        </Typography.Text>
      </Flex>
      <Space>
        {CANCELLABLE.includes(task.state) && (
          <Popconfirm title={`#${task.taskId} цуцлах уу?`} okText="Цуцлах" cancelText="Болих" onConfirm={onCancel}>
            <Button size="middle" danger aria-label={`Цуцлах ${task.taskId}`}>Цуцлах</Button>
          </Popconfirm>
        )}
        {/* Someone else's task is an admin's to stop, never to start again for them. */}
        {task.mine && task.state === "finished" && (
          <Button size="middle" aria-label={`Дахин эхлүүлэх ${task.taskId}`} onClick={onRetry}>Дахин</Button>
        )}
      </Space>
      {open && <TaskDetail timeline={timeline} />}
    </Flex>
  );
}

/** A task's runs, or the note that someone else's task shows its headline alone (ADR 0020). */
function TaskDetail({ timeline }: { timeline: Timeline | undefined }) {
  if (!timeline) return <Spin size="small" />;
  if (timeline.headline) {
    return <Typography.Text type="secondary">Энэ бол өөр гишүүний даалгавар: гарчгаас цаашгүй.</Typography.Text>;
  }
  return (
    <Flex vertical gap={4} style={{ paddingLeft: 12, borderInlineStart: "2px solid rgba(128,128,128,0.25)" }}>
      {(timeline.runs ?? []).map((run) => (
        <Typography.Text key={run.seq} style={{ fontSize: 13 }}>
          #{run.seq} · {run.kind} · {run.status}
          {run.failureReason ? ` · ${run.failureReason}` : ""}
        </Typography.Text>
      ))}
      {timeline.prUrl && <a href={timeline.prUrl} target="_blank" rel="noreferrer">Pull request</a>}
    </Flex>
  );
}
