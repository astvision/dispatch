import { Alert, Button, Spin, Typography } from "antd";
import { useState } from "react";
import { ApiError, approvePlan, type LiveTask } from "../../api";
import { useT, type Key } from "../../i18n/i18n";
import { Lamp } from "../Shell";
import { useDesktopStatus } from "../status";
import { age } from "../tasks/groups";
import TaskDrawer from "../tasks/TaskDrawer";
import SpendChart from "./SpendChart";

function Counter({ colour, label, value }: { colour?: "green" | "amber" | "quiet"; label: string; value: string }) {
  return (
    <div className="counter">
      <div className="counter-label">{colour ? <Lamp colour={colour}>{label}</Lamp> : label}</div>
      <div className="counter-value">{value}</div>
    </div>
  );
}

/** A task of yours waiting on you, with its one action: Approve a plan that asks nothing, else open it to answer. */
function WaitingRow({ row, onOpen, onChanged }: { row: LiveTask; onOpen: (taskId: number) => void; onChanged: () => void }) {
  const t = useT();
  const [busy, setBusy] = useState(false);
  const [refusal, setRefusal] = useState<string | null>(null);
  const questions = row.openQuestions ?? 0;
  const approvable = questions === 0 && row.planSeq !== undefined && row.actions.includes("approve");
  const approve = async () => {
    setBusy(true);
    setRefusal(null);
    try {
      await approvePlan(row.taskId, row.planSeq as number);
    } catch (e) {
      // A plan replaced since the reading, or a task that moved on: the bot's own words, then a fresh reading.
      setRefusal(e instanceof ApiError ? e.message : String(e));
    } finally {
      setBusy(false);
      onChanged();
    }
  };
  return (
    <div className="overview-item">
      <span className="mono task-id">#{row.taskId}</span>
      <button type="button" className="overview-title" onClick={() => onOpen(row.taskId)}>
        {row.title} <span className="hint">· {row.project}{questions > 0 && ` · ${t("overview.live.questions", { count: questions })}`}</span>
      </button>
      {approvable
        ? <Button size="small" type="primary" loading={busy} onClick={() => void approve()}>{t("tasks.approve")}</Button>
        : <Button size="small" onClick={() => onOpen(row.taskId)}>{t(questions > 0 ? "tasks.state.answer" : "overview.live.open")}</Button>}
      {refusal && <Alert className="overview-refusal" type="error" showIcon message={refusal} />}
    </div>
  );
}

/** A running or queued task: its kind and how long it has run or waited. */
function RunningRow({ row, now, onOpen }: { row: LiveTask; now: Date; onOpen: (taskId: number) => void }) {
  const t = useT();
  const since = age(row.startedAt ?? row.queuedAt, now);
  return (
    <div className="overview-item">
      <span className="mono task-id">#{row.taskId}</span>
      <button type="button" className="overview-title" onClick={() => onOpen(row.taskId)}>
        {row.title} <span className="hint">· {row.project}{row.requester && ` · ${row.requester}`}</span>
      </button>
      <span className="hint">
        {row.kind && t(`tasks.kind.${row.kind}` as Key)}{since && ` · ${t(`tasks.ago.${since.unit}` as Key, { n: since.n })}`}
      </span>
    </div>
  );
}

/**
 * The live Тойм (D-2b): what runs, what waits on you with its one action, and what it costs, from the reading the strip's
 * lamps use. The page shows it only while the bot answers.
 */
export default function LiveBoard({ navigate }: { navigate: (path: string) => void }) {
  const t = useT();
  const { live, liveError, reloadLive, overview } = useDesktopStatus();
  const [open, setOpen] = useState<number | null>(null);
  if (!live) return liveError ? <Typography.Text type="danger">{liveError.message}</Typography.Text> : <Spin />;
  if (!live.tasks || live.maxConcurrent === undefined) {
    // A bot of the version before this page, still running after an upgrade: the strip says the same.
    return <Alert type="info" showIcon message={t("strip.newVersion", { version: overview?.version ?? "" })} />;
  }
  const now = new Date();
  const waiting = live.tasks.awaitingApproval.filter((row) => row.mine);
  const { running, queued } = live.tasks;
  return (
    <div className="live-board">
      <div className="counters">
        <Counter colour={running.length > 0 ? "green" : "quiet"} label={t("overview.live.running")} value={String(running.length)} />
        <Counter colour={waiting.length > 0 ? "amber" : "quiet"} label={t("overview.live.waitingOnYou")} value={String(waiting.length)} />
        <Counter label={t("overview.live.today")} value={`$${live.todayUsd}`} />
        <Counter label={t("overview.live.month")} value={`$${live.monthUsd}`} />
      </div>
      <div className="overview-cols">
        <div className="overview-col">
          <section className="panel-box">
            <div className="panel-head"><span>{t("overview.live.waitingOnYou")}</span><span className="hint">{waiting.length}</span></div>
            {waiting.length === 0
              ? <Typography.Text type="secondary">{t("overview.live.nothingWaits")}</Typography.Text>
              : waiting.map((row) => <WaitingRow key={row.taskId} row={row} onOpen={setOpen} onChanged={reloadLive} />)}
          </section>
          <section className="panel-box">
            <div className="panel-head">
              <span>{t("overview.live.running")}</span>
              <span className="hint">{t("overview.live.runningOf", { count: running.length, max: live.maxConcurrent })}</span>
            </div>
            {running.length === 0
              ? <Typography.Text type="secondary">{t("overview.live.nothingRuns")}</Typography.Text>
              : running.map((row) => <RunningRow key={row.taskId} row={row} now={now} onOpen={setOpen} />)}
            {queued.length > 0 && (
              <>
                <div className="panel-head overview-queued"><span>{t("overview.live.queued")}</span><span className="hint">{queued.length}</span></div>
                {queued.map((row) => <RunningRow key={row.taskId} row={row} now={now} onOpen={setOpen} />)}
              </>
            )}
          </section>
        </div>
        <div className="overview-col"><SpendChart /></div>
      </div>
      <TaskDrawer taskId={open} onClose={() => setOpen(null)} onChanged={reloadLive} navigate={navigate} />
    </div>
  );
}
