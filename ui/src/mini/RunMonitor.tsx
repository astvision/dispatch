import { useEffect, useState } from "react";
import { getTaskRun, type RunStepView, type RunView } from "../api";
import { clock } from "./tickets";

/** How often a live run is asked again; a team worker's own progress comes every 10 s, so its steps may lag that much. */
export const POLL_MS = 2000;

const FIX_ROUNDS = 3;

const ICONS: Record<NonNullable<RunStepView["outcome"]>, string> = {
  DONE: "✅", PASSED: "✅", OK: "✅", FAILED: "❌", FINDINGS: "⚠️", SKIPPED: "⏭", STOPPED: "⛔",
};

function label(step: RunStepView): string {
  switch (step.kind) {
    case "PLAN": return "Төлөвлөлт";
    case "IMPLEMENT": return "Хэрэгжүүлэлт";
    case "TEST": return `Тест ${step.round}`;
    case "FIX": return `Засвар ${step.round}/${FIX_ROUNDS}`;
    case "REVIEW": return "Review";
    case "DELIVER": return "Хүргэх";
  }
}

/**
 * The task's latest run step by step (RM-3): each step's state and time, a failing test's tail and a review's findings
 * folded under it, and the running step's latest agent action. While the run is live it asks again every
 * {@link POLL_MS}, and not while the page is hidden.
 */
export default function RunMonitor({ taskId, live }: { taskId: number; live: boolean }) {
  const [run, setRun] = useState<RunView | null>(null);
  // The server's clock minus the phone's, so a running step's time does not drift with the phone.
  const [skew, setSkew] = useState(0);
  const [failed, setFailed] = useState(false);
  const [, setTick] = useState(0);

  useEffect(() => {
    const abort = new AbortController();
    const load = () => {
      if (document.hidden) return;
      getTaskRun(taskId, abort.signal).then((answer) => {
        setRun(answer);
        setSkew(Date.parse(answer.now) - Date.now());
        setFailed(false);
      }, () => !abort.signal.aborted && setFailed(true));
    };
    load();
    if (!live) return () => abort.abort();
    const poll = setInterval(load, POLL_MS);
    // The running step's clock ticks between answers.
    const second = setInterval(() => setTick((tick) => tick + 1), 1000);
    return () => {
      abort.abort();
      clearInterval(poll);
      clearInterval(second);
    };
  }, [taskId, live]);

  if (!run) {
    return failed ? <p className="quiet" style={{ marginTop: 16 }}>Явцыг уншиж чадсангүй.</p> : null;
  }
  const now = Date.now() + skew;
  return (
    <section className="run-monitor" aria-label="Явц">
      <ol className="run-rail">
        {run.steps.map((step) => (
          <Step key={step.n} step={step} now={now} action={step.endedAt === null ? run.activity?.lastAction ?? null : null} />
        ))}
      </ol>
      {run.costUsd && <p className="run-cost num">${run.costUsd}</p>}
      {failed && <p className="quiet">Холболт тасарсан, дахин оролдож байна…</p>}
    </section>
  );
}

function Step({ step, now, action }: { step: RunStepView; now: number; action: string | null }) {
  const running = step.outcome === null;
  const time = clock(step.startedAt, running ? now : Date.parse(step.endedAt ?? step.startedAt));
  return (
    <li className="run-step" data-running={running || undefined} data-outcome={step.outcome ?? undefined}>
      <span className="run-icon" aria-hidden="true">{running ? "⏳" : ICONS[step.outcome!]}</span>
      <span className="run-name">
        {label(step)}
        {action && <small>{action}</small>}
        {step.outcome === "SKIPPED" && <small>Та алгассан</small>}
        {step.detail?.error && <small>{step.detail.error}</small>}
      </span>
      <span className="run-time num">{time}</span>
      {step.detail?.tail && (
        <details className="run-detail">
          <summary>Тестийн сүүл</summary>
          <pre>{step.detail.tail}</pre>
        </details>
      )}
      {step.detail?.findings && step.detail.findings.length > 0 && (
        <details className="run-detail" open>
          <summary>{step.detail.findings.length} олдвор</summary>
          <ul className="run-findings">
            {step.detail.findings.map((finding, index) => (
              <li key={index}>
                {finding.severity === "blocking" ? "🔴" : "🟡"} <code>{finding.file}:{finding.line}</code> — {finding.text}
              </li>
            ))}
          </ul>
        </details>
      )}
    </li>
  );
}
