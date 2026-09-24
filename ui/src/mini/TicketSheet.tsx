import { Input } from "antd";
import { useCallback, useEffect, useRef, useState } from "react";
import {
  answerQuestion, ApiError, approvePlan, getTaskDetail, rejectPlan, type Answer, type PlanQuestionView, type TaskDetail,
  type TaskRow,
} from "../api";
import { haptic, useTelegramBackButton } from "./backButton";
import { clock, clockStart, stateOf } from "./tickets";

export type Decision = "answered" | "approved" | "rejected";

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
  /** A decision that takes the ticket off the pass: the last answer, an approval or a rejection. */
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
  const awaiting = detail?.phase === "AWAITING_APPROVAL" && plan !== undefined;
  const open = plan?.questions.filter((question) => question.answer === null) ?? [];
  const current = awaiting ? open[0] : undefined;

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
        <Head task={task} time={clock(clockStart(task), now)} />
        <h2 id="sheet-title" ref={heading} tabIndex={-1}>{task.title}</h2>

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
                            current={question === current} later={awaiting && question.answer === null && question !== current}
                            onAnswer={(given) => void answer(question, given)} />
                ))}
              </>
            )}
            <PlanList title="Алхмууд" items={plan.steps} ordered />
            <PlanList title="Эрсдэл" items={plan.risks} />
            <PlanList title="Олдвор" items={plan.findings} />
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
          {awaiting
            ? <Decide busy={busy} openQuestions={open.length}
                      onApprove={() => void act(() => approvePlan(task.taskId, plan.planSeq), () => onDecided(task.taskId, "approved"))}
                      onReject={() => void act(() => rejectPlan(task.taskId, plan.planSeq), () => onDecided(task.taskId, "rejected"))} />
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

/** Approve, or reject after one more tap: rejecting ends the task, and Telegram's webview shows no confirm() dialog. */
function Decide({ busy, openQuestions, onApprove, onReject }: {
  busy: boolean;
  openQuestions: number;
  onApprove: () => void;
  onReject: () => void;
}) {
  const [asking, setAsking] = useState(false);
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
      {openQuestions > 0 && <p className="note">Асуултад хариулсны дараа агент төлөвлөгөөгөө шинэчилнэ.</p>}
      <button type="button" className="ticket-go" style={{ marginTop: 0 }} disabled={busy || openQuestions > 0} onClick={onApprove}>
        Зөвшөөрөх
      </button>
      <button type="button" className="sheet-reject" disabled={busy} onClick={() => setAsking(true)}>Татгалзах</button>
    </>
  );
}
