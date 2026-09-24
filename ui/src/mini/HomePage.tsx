import { useCallback, useEffect, useRef, useState } from "react";
import { ApiError, listTasks, type Me, type TaskRow } from "../api";
import { haptic } from "./backButton";
import { Avatar } from "./List";
import { PROJECTS_PATH } from "./paths";
import TicketSheet, { Head, type Decision } from "./TicketSheet";
import { ago, clock, clockStart, groupTickets, reducedMotion, STATIONS, stateOf, stationOf } from "./tickets";

/** How long a decided ticket takes to drop off the pass before the list is asked again (world.css: ticket-leave). */
const LEAVE_MS = 600;

function useNow(everyMs: number) {
  const [now, setNow] = useState(() => Date.now());
  useEffect(() => {
    const timer = setInterval(() => setNow(Date.now()), everyMs);
    return () => clearInterval(timer);
  }, [everyMs]);
  return now;
}

/** The viewer's own tasks, polled as the task pages poll them; asked again at once after a decision. */
function useTasks(intervalMs: number) {
  const [tasks, setTasks] = useState<TaskRow[] | null>(null);
  const [error, setError] = useState<ApiError | null>(null);
  const [reloadToken, setReloadToken] = useState(0);

  useEffect(() => {
    let stopped = false;
    let timer: ReturnType<typeof setTimeout> | undefined;
    const tick = async () => {
      try {
        const answer = await listTasks("me");
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
  }, [intervalMs, reloadToken]);

  const reload = useCallback(() => setReloadToken((token) => token + 1), []);
  return { tasks, error, reload };
}

/** The route from draft to pull request, printed on the slip, with the station it stands at stamped. */
function Stations({ at }: { at: number }) {
  return (
    <ol className="stations" aria-label={`Алхам ${at + 1}/${STATIONS.length}: ${STATIONS[at]}`}>
      {STATIONS.map((name, index) => (
        <li key={name} data-at={index < at ? "past" : index === at ? "now" : "next"} aria-hidden="true">
          <span>{index + 1} {name}</span>
        </li>
      ))}
    </ol>
  );
}


/** A ticket waiting on the owner, full width, with what it waits on and the one thing to do about it. */
function PassTicket({ task, now, leaving, onOpen }: { task: TaskRow; now: number; leaving: boolean; onOpen: () => void }) {
  const questions = task.openQuestions ?? 0;
  return (
    <div className="pass-slot" data-leaving={leaving || undefined}>
      <div>
        <article className="ticket hung" data-tone="you" aria-label={`#${task.taskId} ${task.title}`} onClick={onOpen}>
          <span className="band" aria-hidden="true" />
          <Head task={task} time={clock(clockStart(task), now)} />
          <h3 className="ticket-title">{task.title}</h3>
          <p className="ticket-wait">
            {questions > 0
              ? <><b>{questions} асуулт:</b> {task.question}</>
              : <><b>Төлөвлөгөө бэлэн.</b> Уншаад шийднэ үү.</>}
          </p>
          <Stations at={stationOf(task)} />
          <button type="button" className="ticket-go" onClick={(event) => { event.stopPropagation(); onOpen(); }}>
            {questions > 0 ? "Хариулах" : "Төлөвлөгөө үзэх"}
          </button>
        </article>
      </div>
    </div>
  );
}

function railStep(task: TaskRow) {
  if (task.state === "queued") return "Ээлжээ хүлээж байна";
  if (!task.lastAction) return "Эхэлж байна…";
  return task.steps ? `${task.steps} · ${task.lastAction}` : task.lastAction;
}

/** A ticket in progress, hanging from the rail: its live last step and its clock. */
function RailTicket({ task, now, joined, onOpen }: { task: TaskRow; now: number; joined: boolean; onOpen: () => void }) {
  const state = stateOf(task);
  return (
    <button type="button" className="ticket rail-ticket hung" data-tone={state.tone} data-joined={joined || undefined} onClick={onOpen}>
      <span className="band" aria-hidden="true" />
      <Head task={task} time={clock(clockStart(task), now)} />
      <span className="ticket-title">{task.title}</span>
      <span className="rail-step">{railStep(task)}</span>
    </button>
  );
}

function ServedTicket({ task, now, joined, onOpen }: { task: TaskRow; now: number; joined: boolean; onOpen: () => void }) {
  const state = stateOf(task);
  return (
    <button type="button" className="ticket served-ticket" data-tone={state.tone} data-joined={joined || undefined} onClick={onOpen}>
      <span className="band" aria-hidden="true" />
      <span className="line"><span className="num">#{task.taskId}</span>{task.title}</span>
      <span className="when"><span className="state">{state.word}</span> · {ago(clockStart(task), now)}</span>
    </button>
  );
}

function Lane({ title, count, action, children }: {
  title: string;
  count?: number;
  action?: React.ReactNode;
  children: React.ReactNode;
}) {
  return (
    <section className="lane" aria-label={title}>
      <h2 className="lane-title">
        {title}
        {action ?? (count !== undefined && count > 0 && <span className="num">{count}</span>)}
      </h2>
      {children}
    </section>
  );
}

/** Everything else, behind one row at the bottom. */
function Shelf({ me, navigate }: { me: Me; navigate: (path: string) => void }) {
  const links: [string, string][] = [
    ["Төслүүд", PROJECTS_PATH], ["Миний даалгаврууд", "/tasks"], ["Миний тохиргоо", "/prefs"],
    ...(me.admin ? [["Бүх даалгавар", "/group-tasks"], ["Группүүд", "/groups"], ["Хүмүүс", "/people"],
      ["Тохиргоо", "/settings"], ["Лог", "/logs"], ["Тойм", "/overview"]] as [string, string][] : []),
  ];
  return (
    <nav className="shelf" aria-label="Бусад хуудас">
      {links.map(([label, path]) => <button key={path} type="button" onClick={() => navigate(path)}>{label}</button>)}
    </nav>
  );
}

/**
 * The Mini App's first screen: what waits on the owner at the pass, what is moving along the rail, and what was served.
 * A decision taken in a ticket's sheet sends the ticket off the pass and onto the rail.
 */
export default function HomePage({ me, navigate, intervalMs = 5000 }: {
  me: Me;
  navigate: (path: string) => void;
  intervalMs?: number;
}) {
  const { tasks, error, reload } = useTasks(intervalMs);
  const now = useNow(1000);
  const [open, setOpen] = useState<TaskRow | null>(null);
  const [leaving, setLeaving] = useState<number | null>(null);
  const [departed, setDeparted] = useState<number[]>([]);
  const [joined, setJoined] = useState<number | null>(null);
  const timer = useRef<ReturnType<typeof setTimeout>>(undefined);
  useEffect(() => () => clearTimeout(timer.current), []);

  const close = useCallback(() => setOpen(null), []);
  const decided = useCallback((taskId: number, decision: Decision) => {
    setOpen(null);
    haptic(decision === "rejected" ? "warning" : "success");
    setLeaving(taskId);
    timer.current = setTimeout(() => {
      // Kept off the pass until the list agrees, so it never flickers back while the reload is on its way.
      setDeparted((ids) => [...ids, taskId]);
      setLeaving(null);
      setJoined(taskId);
      reload();
    }, reducedMotion() ? 0 : LEAVE_MS);
  }, [reload]);

  const { pass, rail, served } = groupTickets(tasks ?? []);
  const waiting = pass.filter((task) => !departed.includes(task.taskId));

  return (
    <>
      <header className="strip">
        <Avatar name="dispatcher" size={36} />
        <span className="strip-name"><strong>Dispatch</strong><span>@{me.bot}</span></span>
        <span className="strip-count" aria-label={`${waiting.length} даалгавар таныг хүлээж байна`}>
          <b data-zero={waiting.length === 0 || undefined}>{waiting.length}</b>хүлээж байна
        </span>
      </header>

      <Lane title="Таны шийдвэр">
        {tasks === null && !error && <p className="lane-quiet" aria-busy="true">Уншиж байна…</p>}
        {error && <p className="lane-quiet" role="alert">Dispatch-аас уншиж чадсангүй: {error.message}</p>}
        {tasks !== null && waiting.length === 0 && (
          <p className="lane-quiet">Таны шийдвэр хүлээсэн зүйл алга — бусад нь өөрөө явж байна.</p>
        )}
        {waiting.length > 0 && (
          <div className="pass">
            {waiting.map((task) => (
              <PassTicket key={task.taskId} task={task} now={now} leaving={leaving === task.taskId} onOpen={() => setOpen(task)} />
            ))}
          </div>
        )}
      </Lane>

      <Lane title="Явж байна" count={rail.length}>
        {tasks !== null && rail.length === 0 && <p className="lane-quiet">Одоо ажиллаж буй даалгавар алга.</p>}
        {rail.length > 0 && (
          <div className="rail">
            {rail.map((task) => (
              <RailTicket key={task.taskId} task={task} now={now} joined={joined === task.taskId} onOpen={() => setOpen(task)} />
            ))}
          </div>
        )}
      </Lane>

      <Lane title="Дууссан" action={<button type="button" className="lane-link" onClick={() => navigate("/tasks")}>Бүгд</button>}>
        {tasks !== null && served.length === 0 && <p className="lane-quiet">Дууссан даалгавар алга.</p>}
        {served.length > 0 && (
          <div className="served">
            {served.map((task) => (
              <ServedTicket key={task.taskId} task={task} now={now} joined={joined === task.taskId} onOpen={() => setOpen(task)} />
            ))}
          </div>
        )}
      </Lane>

      <Shelf me={me} navigate={navigate} />

      {open && <TicketSheet key={open.taskId} task={open} now={now} onClose={close} onDecided={decided} />}
    </>
  );
}
