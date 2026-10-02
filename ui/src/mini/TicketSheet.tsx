import { Input } from "antd";
import { useCallback, useEffect, useRef, useState } from "react";
import {
  answerQuestion, ApiError, approvePlan, correctPlan, followUpTask, getTaskDetail, rejectPlan, type Answer, type PlanQuestionView, type TaskDetail,
  type TaskRow,
} from "../api";
import { haptic, useTelegramBackButton } from "./backButton";
import RunMonitor from "./RunMonitor";
import { clock, clockStart, stateOf } from "./tickets";

export type Decision = "answered" | "approved" | "rejected" | "corrected" | "followedUp";

/** The printed header: number, project, and the state word with how long it has been in it. */
export function Head({ task, time }: { task: TaskRow; time: string }) {
  return (
    <span className="ticket-head">
      <b>#{task.taskId}</b>
      <span className="project">{task.project}</span>
      <span className="clock"><span className="state">{stateOf(task).word}</span> {time}</span>
    </span>
  );
}

const asApiError = (e: unknown) => (e instanceof ApiError ? e : new ApiError("unknown", String(e)));

/**
 * A ticket slid up into a full sheet: its plan, the open question as tap choices, and the decision. Telegram's Back
 * button, Escape and a tap on the dimmed page close it. Only the requester opens their own tickets here, so every
 * action is theirs; the server holds the same rules as the chat's buttons.
 */
export default function TicketSheet({ task, now, onClose, onDecided }: {
  task: TaskRow;
  now: number;
  onClose: () => void;
  /** A decision that takes the ticket off the pass: the last answer, an approval, a rejection, added context or a follow-up. */
  onDecided: (taskId: number, decision: Decision) => void;
}) {
  const [detail, setDetail] = useState<TaskDetail | null>(null);
  const [error, setError] = useState<ApiError | null>(null);
  const [busy, setBusy] = useState(false);
  const heading = useRef<HTMLHeadingElement>(null);
  const close = useRef(onClose);
  close.current = onClose;
  useTelegramBackButton(onClose);

  const load = useCallback(() => {
    setError(null);
    getTaskDetail(task.taskId).then(setDetail, (e: unknown) => setError(asApiError(e)));
  }, [task.taskId]);
  useEffect(load, [load]);

  useEffect(() => {
    const opener = document.activeElement as HTMLElement | null;
    const overflow = document.body.style.overflow;
    document.body.style.overflow = "hidden";
    heading.current?.focus();
    const onKey = (event: KeyboardEvent) => event.key === "Escape" && close.current();
    window.addEventListener("keydown", onKey);
    return () => {
      window.removeEventListener("keydown", onKey);
      document.body.style.overflow = overflow;
      opener?.focus?.();
    };
  }, []);

  const act = async <T,>(call: () => Promise<T>, after: (result: T) => void) => {
    setBusy(true);
    setError(null);
    try {
      after(await call());
    } catch (e) {
      setError(asApiError(e));
    } finally {
      setBusy(false);
    }
  };

  const plan = detail?.plan;
  // What the sheet offers is the server's (ADR 0027): a decision while the plan waits for one, and the question to answer.
  const decides = detail?.actions.includes("reject") ?? false;
  const mayCorrect = detail?.actions.includes("correct") ?? false;
  const mayFollowUp = detail?.actions.includes("followUp") ?? false;
  const mayApprove = detail?.actions.includes("approve") ?? false;
  const current = plan?.current ?? 0;

  const answer = (question: PlanQuestionView, given: Answer) => act(
    () => answerQuestion(task.taskId, plan!.planSeq, question.index, given),
    (next) => {
      if (next.phase !== "AWAITING_APPROVAL") {
        onDecided(task.taskId, "answered");
        return;
      }
      haptic("selection");
      setDetail(next);
    });

  const state = stateOf(task);
  return (
    <>
      <div className="sheet-backdrop" onClick={onClose} aria-hidden="true" />
      <section className="sheet" role="dialog" aria-modal="true" aria-labelledby="sheet-title" data-tone={state.tone}>
        <span className="band" aria-hidden="true" />
        <button type="button" className="sheet-close" aria-label="Хаах" onClick={onClose}>✕</button>
        <Head task={task} time={clock(clockStart(task), now)} />
        <h2 id="sheet-title" ref={heading} tabIndex={-1}>{task.title}</h2>

        {(task.state === "running" || task.state === "finished") && <RunMonitor taskId={task.taskId} live={task.state === "running"} />}
        {!detail && !error && <p className="quiet" aria-busy="true" style={{ marginTop: 16 }}>Уншиж байна…</p>}
        {plan && (
          <>
            <h3>Ойлголт</h3>
            <p>{plan.understanding}</p>
            {plan.questions.length > 0 && (
              <>
                <h3>Асуултууд</h3>
                {plan.questions.map((question) => (
                  <Question key={question.index} question={question} total={plan.questions.length} busy={busy}
                            current={question.index === current} later={question.answer === null && current > 0 && question.index > current}
                            onAnswer={(given) => void answer(question, given)} />
                ))}
              </>
            )}
            {plan.result === "answer" ? (
              <>
                <h3>Хариулт</h3>
                <p style={{ whiteSpace: "pre-wrap" }}>{plan.answer}</p>
              </>
            ) : (
              <>
                <PlanList title="Алхмууд" items={plan.steps} ordered />
                <PlanList title="Эрсдэл" items={plan.risks} />
                <PlanList title="Плагин" items={plan.plugins} />
                <PlanList title="Олдвор" items={plan.findings} />
              </>
            )}
          </>
        )}
        {detail && !plan && <p className="quiet" style={{ marginTop: 16 }}>Төлөвлөгөө хараахан гараагүй байна.</p>}
        {detail?.failureReason && <p style={{ marginTop: 16 }}>Шалтгаан: {detail.failureReason}</p>}
        {detail?.prUrl && (
          <p style={{ marginTop: 16 }}><a href={detail.prUrl} target="_blank" rel="noreferrer">Pull request нээх</a></p>
        )}

        {error && (
          <p className="sheet-error" role="alert">
            {error.message}
            <button type="button" className="lane-link" onClick={load}>Дахин ачаалах</button>
          </p>
        )}

        <div className="sheet-actions">
          {decides && plan
            ? <Decide busy={busy} mayApprove={mayApprove} mayCorrect={mayCorrect}
                      onCorrect={(text) => void act(() => correctPlan(task.taskId, plan.planSeq, text), () => onDecided(task.taskId, "corrected"))}
                      onApprove={() => void act(() => approvePlan(task.taskId, plan.planSeq), () => onDecided(task.taskId, "approved"))}
                      onReject={() => void act(() => rejectPlan(task.taskId, plan.planSeq), () => onDecided(task.taskId, "rejected"))} />
            : mayFollowUp
              ? <FollowUp busy={busy} onClose={onClose}
                          onSend={(text) => void act(() => followUpTask(task.taskId, text), () => onDecided(task.taskId, "followedUp"))} />
              : <button type="button" className="sheet-reject" style={{ color: "var(--link)" }} onClick={onClose}>Хаах</button>}
        </div>
      </section>
    </>
  );
}

function PlanList({ title, items, ordered = false }: { title: string; items: string[]; ordered?: boolean }) {
  if (items.length === 0) return null;
  const list = items.map((item, index) => <li key={index}>{item}</li>);
  return (
    <>
      <h3>{title}</h3>
      {ordered ? <ol>{list}</ol> : <ul>{list}</ul>}
    </>
  );
}

/** One question: its answer once given; the choices while it is the one being asked; dimmed while it waits its turn. */
function Question({ question, total, current, later, busy, onAnswer }: {
  question: PlanQuestionView;
  total: number;
  current: boolean;
  later: boolean;
  busy: boolean;
  onAnswer: (answer: Answer) => void;
}) {
  const [writing, setWriting] = useState(false);
  const [text, setText] = useState("");
  return (
    <div className="question" data-later={later || undefined}>
      <p className="question-text">
        <span className="question-count" aria-label={`Асуулт ${question.index}/${total}`}>{question.index}/{total}</span>
        {question.text}
      </p>
      {question.answer !== null && <p className="answered">Хариулт: <b>{question.answer}</b></p>}
      {current && (
        <div className="choices">
          {question.options.map((option, index) => (
            <button key={option} type="button" className="choice" disabled={busy} onClick={() => onAnswer({ option: index })}>
              {option}
            </button>
          ))}
          <div className="choices-aside">
            <button type="button" className="choice" disabled={busy} aria-expanded={writing} onClick={() => setWriting(!writing)}>
              ✍️ Өөрөөр
            </button>
            <button type="button" className="choice" disabled={busy} onClick={() => onAnswer({ decide: true })}>🤷 Та шийд</button>
          </div>
          {writing && (
            <div className="own">
              <Input.TextArea aria-label="Өөрийн хариулт" autoSize={{ minRows: 2, maxRows: 6 }} value={text} autoFocus
                              onChange={(event) => setText(event.target.value)} />
              <button type="button" className="ticket-go" style={{ marginTop: 0 }} disabled={busy || text.trim() === ""}
                      onClick={() => onAnswer({ text: text.trim() })}>
                Илгээх
              </button>
            </div>
          )}
        </div>
      )}
    </div>
  );
}

/**
 * Approve, add context for the agent to plan again with, or reject after one more tap: rejecting ends the task, and
 * Telegram's webview shows no confirm() dialog.
 */
function Decide({ busy, mayApprove, mayCorrect, onApprove, onCorrect, onReject }: {
  busy: boolean;
  /** False while the plan asks questions: their answers make the agent plan again, and that plan is approved. */
  mayApprove: boolean;
  mayCorrect: boolean;
  onApprove: () => void;
  onCorrect: (text: string) => void;
  onReject: () => void;
}) {
  const [asking, setAsking] = useState(false);
  const [writing, setWriting] = useState(false);
  if (writing) {
    return <WriteBox label="Нэмэлт мэдээлэл" note="Агент энэ мэдээллийг авч төлөвлөгөөгөө дахин гаргана."
                     placeholder="Жишээ нь: зөвхөн backend-ийг өөрчил, тестээ бич…" busy={busy} onSend={onCorrect}
                     onCancel={() => setWriting(false)} />;
  }
  if (asking) {
    return (
      <>
        <p className="note">Татгалзвал даалгавар энд дуусна.</p>
        <button type="button" className="ticket-go" style={{ marginTop: 0, background: "var(--danger)" }} disabled={busy}
                onClick={onReject}>
          Тийм, татгалзах
        </button>
        <button type="button" className="sheet-reject" style={{ color: "var(--link)" }} onClick={() => setAsking(false)}>Болих</button>
      </>
    );
  }
  return (
    <>
      {!mayApprove && <p className="note">Асуултад хариулсны дараа агент төлөвлөгөөгөө шинэчилнэ.</p>}
      <button type="button" className="ticket-go" style={{ marginTop: 0 }} disabled={busy || !mayApprove} onClick={onApprove}>
        Зөвшөөрөх
      </button>
      {mayCorrect && (
        <button type="button" className="choice" style={{ textAlign: "center", color: "var(--link)" }} disabled={busy}
                onClick={() => setWriting(true)}>
          ✍️ Нэмэлт мэдээлэл өгөх
        </button>
      )}
      <button type="button" className="sheet-reject" disabled={busy} onClick={() => setAsking(true)}>Татгалзах</button>
    </>
  );
}

/** More work on a finished task: the agent continues in the same session and branch, or a new task once it is merged. */
function FollowUp({ busy, onSend, onClose }: { busy: boolean; onSend: (text: string) => void; onClose: () => void }) {
  const [writing, setWriting] = useState(false);
  if (writing) {
    return <WriteBox label="Дараагийн алхам" note="Агент энэ даалгавар дээрээ үргэлжлүүлж ажиллана."
                     placeholder="Жишээ нь: XLSX экспорт нэм, тест бич…" busy={busy} onSend={onSend}
                     onCancel={() => setWriting(false)} />;
  }
  return (
    <>
      <button type="button" className="ticket-go" style={{ marginTop: 0 }} disabled={busy} onClick={() => setWriting(true)}>
        ➕ Дараагийн алхам өгөх
      </button>
      <button type="button" className="sheet-reject" style={{ color: "var(--link)" }} onClick={onClose}>Хаах</button>
    </>
  );
}

/** A few words for the agent, sent with one tap; empty text sends nothing. */
function WriteBox({ label, note, placeholder, busy, onSend, onCancel }: {
  label: string;
  note: string;
  placeholder: string;
  busy: boolean;
  onSend: (text: string) => void;
  onCancel: () => void;
}) {
  const [text, setText] = useState("");
  return (
    <>
      <p className="note">{note}</p>
      <Input.TextArea aria-label={label} placeholder={placeholder} autoSize={{ minRows: 3, maxRows: 8 }} value={text} autoFocus
                      onChange={(event) => setText(event.target.value)} />
      <button type="button" className="ticket-go" style={{ marginTop: 0 }} disabled={busy || text.trim() === ""}
              onClick={() => onSend(text.trim())}>
        Илгээх
      </button>
      <button type="button" className="sheet-reject" style={{ color: "var(--link)" }} onClick={onCancel}>Болих</button>
    </>
  );
}
