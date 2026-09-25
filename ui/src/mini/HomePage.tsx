import {
  BookOutlined, CommentOutlined, ControlOutlined, DashboardOutlined, FileTextOutlined, FolderOutlined, ProfileOutlined,
  SearchOutlined, SettingOutlined, TeamOutlined, UnorderedListOutlined,
} from "@ant-design/icons";
import { Input } from "antd";
import { useCallback, useEffect, useState, type ReactNode } from "react";
import { ApiError, listTasks, type Me, type TaskRow } from "../api";
import { haptic } from "./backButton";
import { Avatar, Header, Row, Section } from "./List";
import { PROJECTS_PATH } from "./paths";
import TicketSheet, { type Decision } from "./TicketSheet";
import { ago, clock, clockStart, groupTickets, stateOf } from "./tickets";

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

function matches(task: TaskRow, query: string) {
  const wanted = query.trim().toLowerCase().replace(/^#/, "");
  return wanted === "" || task.title.toLowerCase().includes(wanted) || task.project.toLowerCase().includes(wanted)
    || String(task.taskId) === wanted;
}

/** What a task waits on or is doing, after its number, project and state word: the band-and-word rule as a line. */
function detail(task: TaskRow) {
  if (task.state === "awaitingApproval") {
    const questions = task.openQuestions ?? 0;
    return questions > 0 ? `${questions} асуулт: ${task.question ?? ""}` : "Төлөвлөгөө бэлэн";
  }
  if (task.state === "running") return task.lastAction ? (task.steps ? `${task.steps} · ${task.lastAction}` : task.lastAction) : "Эхэлж байна…";
  return null;
}

/** One task as a row: the project's avatar, the title, and "#7 · project · state" with a tone dot beside the state. */
function TaskRowView({ task, now, onOpen }: { task: TaskRow; now: number; onOpen: () => void }) {
  const state = stateOf(task);
  const time = task.state === "finished" ? ago(clockStart(task), now) : clock(clockStart(task), now);
  const line = detail(task);
  return (
    <Row leading={<Avatar name={task.project} />} title={task.title} onClick={onOpen}
         value={time ? <span className="num">{time}</span> : undefined}
         subtitle={
           <>
             <span className="num">#{task.taskId}</span> · {task.project} · <span className="tone-dot" data-tone={state.tone} aria-hidden="true" />
             <span className="state-word">{state.word}</span>
             {line && <span className="task-detail">{line}</span>}
           </>
         } />
  );
}

function MenuRow({ icon, title, onClick }: { icon: ReactNode; title: string; onClick: () => void }) {
  return <Row leading={<span aria-hidden="true" className="mini-row-icon">{icon}</span>} title={title} onClick={onClick} />;
}

/**
 * The Mini App's first screen, laid out as BotFather's: the bot's photo and name, a search, then sections of rows:
 * what waits on the owner, what is moving, what finished, and every other page.
 */
export default function HomePage({ me, navigate, intervalMs = 5000 }: {
  me: Me;
  navigate: (path: string) => void;
  intervalMs?: number;
}) {
  const { tasks, error, reload } = useTasks(intervalMs);
  const now = useNow(1000);
  const [open, setOpen] = useState<TaskRow | null>(null);
  const [query, setQuery] = useState("");

  const close = useCallback(() => setOpen(null), []);
  const decided = useCallback((_taskId: number, decision: Decision) => {
    setOpen(null);
    haptic(decision === "rejected" ? "warning" : "success");
    reload();
  }, [reload]);

  const shown = (tasks ?? []).filter((task) => matches(task, query));
  const { pass, rail, served } = groupTickets(shown);
  const waitingCount = tasks ? groupTickets(tasks).pass.length : 0;
  const loading = tasks === null && !error;
  const row = (task: TaskRow) => <TaskRowView key={task.taskId} task={task} now={now} onOpen={() => setOpen(task)} />;

  return (
    <>
      <Header name={me.bot} photo={me.botPhoto} title="Dispatch"
              subtitle={
                <>
                  @{me.bot}
                  <span className="header-count" aria-label={`${waitingCount} даалгавар таныг хүлээж байна`}>
                    {waitingCount > 0 ? ` · ${waitingCount} таныг хүлээж байна` : " · Агентын даалгавруудаа эндээс удирдана."}
                  </span>
                </>
              } />

      <Input aria-label="Даалгавар хайх" placeholder="Хайх" allowClear size="large" variant="filled"
             prefix={<SearchOutlined aria-hidden="true" />} value={query} onChange={(event) => setQuery(event.target.value)}
             style={{ marginTop: 8 }} />

      {error && <p className="mini-note" role="alert">Dispatch-аас уншиж чадсангүй: {error.message}</p>}

      <Section title="Таны шийдвэр">
        {loading && <Row title="Уншиж байна…" />}
        {tasks !== null && pass.length === 0 && <Row title="Таны шийдвэр хүлээсэн зүйл алга" subtitle="Бусад нь өөрөө явж байна." />}
        {pass.map(row)}
      </Section>

      <Section title="Явж байна">
        {tasks !== null && rail.length === 0 && <Row title="Одоо ажиллаж буй даалгавар алга" />}
        {rail.map(row)}
      </Section>

      <Section title="Дууссан">
        {tasks !== null && served.length === 0 && <Row title="Дууссан даалгавар алга" />}
        {served.map(row)}
        <MenuRow icon={<UnorderedListOutlined />} title="Бүх даалгавраа харах" onClick={() => navigate("/tasks")} />
      </Section>

      <Section title="Цэс">
        <MenuRow icon={<FolderOutlined />} title="Төслүүд" onClick={() => navigate(PROJECTS_PATH)} />
        <MenuRow icon={<SettingOutlined />} title="Миний тохиргоо" onClick={() => navigate("/prefs")} />
        <MenuRow icon={<BookOutlined />} title="Гарын авлага" onClick={() => navigate("/guide")} />
      </Section>

      {me.admin && (
        <Section title="Удирдлага">
          <MenuRow icon={<ProfileOutlined />} title="Бүх даалгавар" onClick={() => navigate("/group-tasks")} />
          <MenuRow icon={<CommentOutlined />} title="Группүүд" onClick={() => navigate("/groups")} />
          <MenuRow icon={<TeamOutlined />} title="Хүмүүс" onClick={() => navigate("/people")} />
          <MenuRow icon={<ControlOutlined />} title="Тохиргоо" onClick={() => navigate("/settings")} />
          <MenuRow icon={<FileTextOutlined />} title="Лог" onClick={() => navigate("/logs")} />
          <MenuRow icon={<DashboardOutlined />} title="Тойм" onClick={() => navigate("/overview")} />
        </Section>
      )}

      {open && <TicketSheet key={open.taskId} task={open} now={now} onClose={close} onDecided={decided} />}
    </>
  );
}
