// Typed calls to the dispatch ui server. Every page goes through here, so errors look the same everywhere.

import { chosenMember } from "./desktop/member";
import { currentLanguage, translate } from "./i18n/i18n";
import { inTelegram, initData } from "./telegram";

export type Level = "OK" | "WARN" | "FAIL";

export interface Finding {
  level: Level;
  area: string;
  message: string;
}

export interface ServiceView {
  name: string;
  installed: boolean;
  running: boolean;
  detail: string;
  notes: string[];
}

export interface Overview {
  version: string;
  /** The config's team, which names the instance on the strip (D-2); null before setup. */
  name: string | null;
  configFile: string;
  stateDir: string;
  configured: boolean;
  service: ServiceView;
  findings: Finding[];
}

/** An error the server explained, or a server that could not be reached. */
export class ApiError extends Error {
  constructor(
    readonly code: string,
    message: string,
  ) {
    super(message);
  }
}

async function send<T>(path: string, init: RequestInit): Promise<T> {
  let response: Response;
  try {
    // Inside Telegram every request proves itself with the signed launch data: there is no cookie and no session.
    // Every request asks for the page's language, so the server writes its messages in it.
    // The desktop's chosen admin, on a team with several (D-2); the desk port asks for it when it cannot tell.
    const member = chosenMember();
    const headers = {
      ...init.headers,
      "Accept-Language": currentLanguage(),
      ...(member ? { "X-Dispatch-Member": member } : {}),
      ...(initData ? { Authorization: `tma ${initData}` } : {}),
    };
    response = await fetch(path, { credentials: "same-origin", ...init, headers });
  } catch {
    if (init.signal?.aborted) throw new ApiError("aborted", translate("api.stopped"));
    throw new ApiError("unreachable", translate(inTelegram ? "api.restarting" : "api.notRunning"));
  }
  const body = await response.json().catch(() => null);
  if (!response.ok) {
    throw new ApiError(body?.error ?? "http", body?.message ?? translate("api.answered", { status: response.status }));
  }
  if (body === null) throw new ApiError("http", translate("api.noResult"));
  return body as T;
}

const get = <T,>(path: string, signal?: AbortSignal) => send<T>(path, { signal });

export function post<T>(path: string, body: unknown = {}, signal?: AbortSignal): Promise<T> {
  return send<T>(path, {
    method: "POST",
    headers: { "Content-Type": "application/json" },
    body: JSON.stringify(body),
    signal,
  });
}

export const getOverview = (signal?: AbortSignal) => get<Overview>("/api/overview", signal);

// Setup (spec: Screens and data flow, Setup). The server keeps the answers until Write.

export interface Person {
  id: number;
  name: string;
}

export interface BotView {
  username: string;
  topicsEnabled: boolean;
}

export interface SetupState {
  configExists: boolean;
  configFile: string;
  team: boolean;
  bot: BotView | null;
  members: Person[];
  candidate: Person | null;
  group: { id: number; title: string } | null;
  claudeFound: string | null;
  authorEmail: string | null;
  hints: string[];
  hintAfterSeconds: number;
}

export interface ProjectView {
  folder: string;
  name: string;
  originUrl: string | null;
  originHadCredentials: boolean;
  baseBranch: string | null;
}

export interface FolderEntry {
  name: string;
  path: string;
  gitClone: boolean;
}

export interface FolderListing {
  path: string;
  parent: string | null;
  folders: FolderEntry[];
  truncated: boolean;
}

export type Model = "sonnet" | "opus" | "fable";
export type Effort = "low" | "medium" | "high" | "xhigh" | "max";

/** A project's own model and effort for one phase; null keeps what the project sets for both. */
export interface PhaseChoice {
  model: string | null;
  effort: Effort | null;
}

/** alias, plan and execute come from the Advanced section and are left out when it is not used. */
export interface ProjectChoice {
  folder: string;
  name: string;
  baseBranch: string;
  model: Model | null;
  effort: Effort | null;
  alias?: string;
  plan?: PhaseChoice;
  execute?: PhaseChoice;
}

/** Setup's Advanced answers for the whole instance; each one left out keeps its default. */
export interface SetupAdvanced {
  planTimeout?: string;
  planBudgetUsd?: number;
  executeTimeout?: string;
  executeBudgetUsd?: number;
  maxConcurrentRuns?: number;
  stateDir?: string;
  ghCommand?: string;
}

/** Where members' computers reach the team machine; only a team with a group chat needs it. */
export interface WorkersChoice {
  publicUrl: string;
  port: number;
}

export interface SetupPayload {
  teamName: string | null;
  claude: string;
  authorName: string;
  authorEmail: string;
  projects: ProjectChoice[];
  workers?: WorkersChoice;
  advanced?: SetupAdvanced;
}

export interface Written {
  configFile: string;
  secretsFile: string;
}

export const getSetupState = () => post<SetupState>("/api/setup/state");
export const chooseTeam = (team: boolean) => post<SetupState>("/api/setup/team", { team });
export const checkToken = (token: string) => post<BotView>("/api/setup/token", { token });
export const nextPerson = (signal?: AbortSignal) => post<{ candidate: Person | null }>("/api/setup/people/next", {}, signal);
export const answerPerson = (id: number, accept: boolean) => post<SetupState>("/api/setup/people/answer", { id, accept });
export const nextGroup = (signal?: AbortSignal) =>
  post<{ group: { id: number; title: string } | null }>("/api/setup/group/next", {}, signal);
export const checkClaude = (command: string) => post<{ command: string; version: string }>("/api/setup/claude", { command });
export const listFolders = (path: string | null) => post<FolderListing>("/api/setup/folders", { path });
export const probeProject = (folder: string) => post<ProjectView>("/api/setup/project", { folder });
// The management pages' own: the Mini App serves no setup route, so adding a project there cannot use the two above.
export const listProjectFolders = (path: string | null) => post<FolderListing>("/api/manage/folders", { path });
export const probeClone = (folder: string) => post<ProjectView>("/api/manage/projects/probe", { folder });
export const writeSetup = (payload: SetupPayload) => post<Written>("/api/setup/write", payload);
export const installService = () => post<ServiceView>("/api/service/install");
export const stopService = () => post<ServiceView>("/api/service/stop");

// Management (spec: Pages (UI-3a)). Every save sends the version it read and answers the new one.

export interface Settings {
  planTimeout: string;
  planBudgetUsd: number;
  executeTimeout: string;
  executeBudgetUsd: number;
  maxConcurrentRuns: number;
  authorName: string;
  authorEmail: string;
  /** Null when no project runs on Claude Code and none is configured (ADR 0026). */
  claudeCommand: string | null;
  ghCommand: string;
}

/** The CLI a project runs on (ADR 0026). */
export type AgentType = "claude-code" | "codex" | "gemini";

export interface ManagedProject {
  name: string;
  alias: string | null;
  path: string | null;
  repo: string | null;
  baseBranch: string;
  group: string;
  model: string | null;
  effort: Effort | null;
  plan: PhaseChoice | null;
  execute: PhaseChoice | null;
  agent: AgentType;
}

export interface MemberView {
  id: number;
  name: string;
  admin: boolean;
}

export interface GroupView {
  name: string;
  chatId: number | null;
  members: MemberView[];
  projects: string[];
}

export interface ConfigView {
  version: string;
  configFile: string;
  personal: boolean;
  admins: number[];
  service: ServiceView;
  settings: Settings;
  projects: ManagedProject[];
  groups: GroupView[];
}

export interface Saved {
  saved: boolean;
  restartNeeded: boolean;
  version: string;
}

/** What a project's form sends; the server compares it with the config and changes only what differs. */
export interface ProjectFields {
  name: string;
  baseBranch: string;
  alias: string | null;
  model: string | null;
  effort: Effort | null;
  plan: PhaseChoice | null;
  execute: PhaseChoice | null;
  /** Sent only to switch the project to another agent, which also clears its model and effort. */
  agent?: AgentType;
}

export interface Logs {
  file: string;
  exists: boolean;
  lines: string[];
}

export type LogLevel = "INFO" | "WARN" | "ERROR";

export const getConfig = () => post<ConfigView>("/api/manage/config");
export const saveSettings = (version: string, settings: Settings) => post<Saved>("/api/manage/settings", { version, ...settings });
export const addProject = (version: string, folder: string, group: string | null, project: ProjectFields) =>
  post<Saved>("/api/manage/projects/add", { version, folder, group, ...project });
export const editProject = (version: string, project: ProjectFields) => post<Saved>("/api/manage/projects/edit", { version, ...project });
export const removeProject = (version: string, name: string) => post<Saved>("/api/manage/projects/remove", { version, name });
export const renameMember = (version: string, id: number, name: string) => post<Saved>("/api/manage/people/rename", { version, id, name });
export const removeMember = (version: string, group: string, id: number) =>
  post<Saved>("/api/manage/people/remove", { version, group, id });
export const setAdmin = (version: string, id: number, admin: boolean) => post<Saved>("/api/manage/people/admin", { version, id, admin });
export const unlinkGroup = (version: string, name: string) => post<Saved>("/api/manage/groups/unlink", { version, name });
/** The log's last lines that match: a level, an event containing {@code event}, a task, and any text (any case). */
export const getLogs = (filter: { lines?: number; level?: LogLevel | null; event?: string | null; task?: number | null; text?: string | null },
                        signal?: AbortSignal) =>
  post<Logs>("/api/manage/logs", filter, signal);
export const restartService = () => post<ServiceView>("/api/service/restart");
/** A fresh one-time link to the web UI on the bot's computer (Mini App, admins; ADR 0018 amended). */
export const openWebUi = () => post<{ url: string }>("/api/webui/open");

// The Mini App (spec: Task pages). Only `dispatch run` serves these, because only it holds the queue.

/** Who Telegram says is looking. The Mini App asks this instead of the setup state, which it never serves. */
export interface Me {
  ref: string;
  name: string;
  admin: boolean;
  /** The bot's own @username, without the @. */
  bot: string;
  /** The bot's profile photo as a data URI; null when it has none, and the header shows its initials. */
  botPhoto?: string | null;
}

/** A project of the viewer's own groups, as the Mini App's home lists it. */
export interface ProjectSummary {
  name: string;
  alias: string | null;
  baseBranch: string;
}

export type TaskState = "running" | "queued" | "awaitingApproval" | "finished";

/** What the viewer may do with a task now, as the server's task access decided (ADR 0027). */
export type TaskAction = "approve" | "correct" | "answer" | "reject" | "priority" | "cancel" | "retry" | "followUp" | "merge";

/** One row of a task list. A task that is not the viewer's own carries the headline fields only (ADR 0020). */
export interface TaskRow {
  taskId: number;
  project: string;
  title: string;
  state: TaskState;
  priority: string;
  requester: string | null;
  mine: boolean;
  /** What the viewer may do with it now; a button whose action is not here is not shown (ADR 0027). */
  actions: TaskAction[];
  phase?: string;
  prUrl?: string | null;
  failureReason?: string | null;
  costUsd?: string | null;
  createdAt?: string | null;
  completedAt?: string | null;
  startedAt?: string | null;
  queuedAt?: string | null;
  since?: string | null;
  steps?: number;
  lastAction?: string;
  waitingForWorker?: boolean;
  /** A running or queued run's kind: PLAN or EXECUTE. */
  kind?: string;
  blocked?: string;
  /** On the viewer's own task awaiting approval: how many of its plan's questions are open, and the first of them. */
  openQuestions?: number;
  question?: string;
  /** On the viewer's own waiting task: the plan an approval refers to (D-2b's Overview). */
  planSeq?: number;
}

export interface TaskRun {
  seq: number;
  kind: string;
  cause: string | null;
  status: string;
  requestedBy: string;
  instruction: string | null;
  queuedAt: string | null;
  startedAt: string | null;
  finishedAt: string | null;
  costUsd: string | null;
  failureReason: string | null;
}

export interface Timeline {
  taskId: number;
  project: string;
  title: string;
  requester: string;
  phase: string;
  priority: string;
  prUrl: string | null;
  branch?: string;
  baseBranch?: string;
  failureReason: string | null;
  createdAt: string | null;
  completedAt: string | null;
  costUsd: string | null;
  /** True when this is someone else's task: no runs and no cost (ADR 0020). */
  headline?: boolean;
  runs?: TaskRun[];
}

/** One of a plan's questions: the answer options the agent offered, and the answer once given. */
export interface PlanQuestionView {
  index: number;
  text: string;
  options: string[];
  answer: string | null;
}

export interface PlanView {
  planSeq: number;
  /** The question to answer now, 1-based; 0 when none is open. */
  current: number;
  understanding: string;
  steps: string[];
  risks: string[];
  findings: string[];
  questions: PlanQuestionView[];
}

/** The requester's own task with its latest plan, as the Mini App's task sheet shows it; the desk shows any task so (D-2). */
export interface TaskDetail {
  taskId: number;
  project: string;
  title: string;
  requester?: string;
  priority?: string;
  branch?: string;
  baseBranch?: string;
  phase: string;
  prUrl: string | null;
  failureReason: string | null;
  createdAt: string | null;
  completedAt: string | null;
  costUsd: string | null;
  actions: TaskAction[];
  plan?: PlanView;
}

/** One step of a run (RM): the implementation, a test run, a fix, the review, the delivery, or a plan run's one call. */
export interface RunStepView {
  n: number;
  kind: "PLAN" | "IMPLEMENT" | "TEST" | "FIX" | "PAUSE" | "REVIEW" | "DELIVER";
  round: number;
  startedAt: string;
  endedAt: string | null;
  /** Null while the step runs. */
  outcome: "DONE" | "PASSED" | "FAILED" | "OK" | "FINDINGS" | "SKIPPED" | "STOPPED" | null;
  detail?: {
    tail?: string;
    findings?: { severity: string; file: string; line: number; text: string }[];
    error?: string;
  };
}

/** The task's latest run step by step, for its requester: what the run monitor shows. */
export interface RunView {
  taskId: number;
  seq: number;
  kind: "PLAN" | "EXECUTE" | "DELIVER";
  status: string;
  startedAt: string | null;
  finishedAt: string | null;
  costUsd: string | null;
  /** The server's clock when it answered: step times are counted against it, not the phone's. */
  now: string;
  steps: RunStepView[];
  /** While it runs on the bot's own computer or a worker: the agent's tool calls so far and the latest one. */
  activity?: { steps: number; lastAction: string | null };
  /** How to go on with the task's agent session in a terminal (RM-6); reason says why not yet, when it cannot. */
  teleport?: {
    command: string;
    line: string | null;
    reason: "RUNNING" | "NOT_CLAUDE" | "ON_WORKER" | "NO_WORKTREE" | "NO_TASK" | null;
    worker: string | null;
  };
  /** While an execution runs: ⏭ on the running test, fix or review (its step number), and 📦 deliver now. */
  controls?: {
    skip: number | null;
    deliverNow: boolean;
    deliverNowRequested: boolean;
    /** ⏸ before the review: the switch, whether it can still be changed, and a paused run's automatic go-on time. */
    pauseBeforeReview: boolean;
    canPause: boolean;
    paused: boolean;
    pauseEndsAt: string | null;
  };
}

/** One answer to a question: an offered option's index, the requester's own words, or "you decide". */
export type Answer = { option: number } | { text: string } | { decide: true };

/** How the caller's own group hears about their task (G-1e); reaction is the default. */
export type GroupAck = "reaction" | "reactionAndLine" | "silent";

export const getMe = (signal?: AbortSignal) => get<Me>("/api/me", signal);
export const getPrefs = (signal?: AbortSignal) => get<{ groupAck: GroupAck }>("/api/me/prefs", signal);
export const savePrefs = (groupAck: GroupAck) => post<{ groupAck: GroupAck }>("/api/me/prefs", { groupAck });
export const listProjects = (signal?: AbortSignal) => get<{ projects: ProjectSummary[] }>("/api/projects", signal);
export const listTasks = (scope: "me" | "group", signal?: AbortSignal) =>
  post<{ tasks: TaskRow[] }>("/api/tasks/list", { scope }, signal);
export const taskTimeline = (taskId: number, signal?: AbortSignal) => post<Timeline>("/api/tasks/timeline", { taskId }, signal);
export const cancelTask = (taskId: number) => post<{ result: string }>("/api/tasks/cancel", { taskId });
export const retryTask = (taskId: number) => post<{ result: string }>("/api/tasks/retry", { taskId });
export const getTaskDetail = (taskId: number, signal?: AbortSignal) => post<TaskDetail>("/api/tasks/detail", { taskId }, signal);
export const getTaskRun = (taskId: number, signal?: AbortSignal) => post<RunView>("/api/tasks/run", { taskId }, signal);
export type RunControl = { action: "skip"; step: number } | { action: "deliverNow" | "pause" | "unpause" | "review" };
export const steerRun = (taskId: number, control: RunControl) =>
  post<RunView>("/api/tasks/run/control", { taskId, ...control });
export const answerQuestion = (taskId: number, planSeq: number, index: number, answer: Answer) =>
  post<TaskDetail & { result: string }>("/api/tasks/answer", { taskId, planSeq, index, ...answer });
export const approvePlan = (taskId: number, planSeq: number) => post<{ result: string }>("/api/tasks/approve", { taskId, planSeq });
export const rejectPlan = (taskId: number, planSeq: number) => post<{ result: string }>("/api/tasks/reject", { taskId, planSeq });

// The desktop's tasks (D-2), which `dispatch ui` passes on to the running bot's desk port.

/** What the strip's lamps and the Overview count, from the running bot's desk port. */
/** A task as the live reading lists it: a list row without its state, which the list it is in says. */
export type LiveTask = Omit<TaskRow, "state">;

export interface Live {
  version: string;
  name: string;
  running: number;
  queued: number;
  waitingOnYou: { taskId: number; title: string }[];
  waitingOnOthers: number;
  todayUsd: string;
  monthUsd: string;
  // The three below are D-2b's: a bot of an older version, still running after an upgrade, answers without them.
  /** How many runs the bot runs at once (its scheduler.maxConcurrentRuns). */
  maxConcurrent?: number;
  /** The projects the desktop's admin may give a task in. */
  projects?: string[];
  tasks?: { running: LiveTask[]; queued: LiveTask[]; awaitingApproval: LiveTask[] };
}

export interface Spend {
  from: string;
  to: string;
  /** Every day from `from` to `to`, oldest first; `usd` holds the projects that cost something that day. */
  days: { day: string; usd: Record<string, string> }[];
  /** Each project with finished runs in the window, costliest first; `unpriced` runs reported no cost (Codex, Gemini CLI). */
  projects: { project: string; usd: string; runs: number; unpriced: number }[];
  totalUsd: string;
}

export type Priority = "URGENT" | "NORMAL" | "LOW";

export const giveTask = (project: string, text: string, priority: Priority) =>
  post<{ taskId: number }>("/api/tasks/new", { project, text, priority });
export const getSpend = (days: number, signal?: AbortSignal) => post<Spend>("/api/tasks/spend", { days }, signal);

export const getLive = (signal?: AbortSignal) => get<Live>("/api/live", signal);
export const correctPlan = (taskId: number, planSeq: number, text: string) =>
  post<{ result: string }>("/api/tasks/correct", { taskId, planSeq, text });
/** QUEUED: the task runs again in its session; NEW_TASK: its pull request was merged, so the follow-up is a new task. */
export const followUpTask = (taskId: number, text: string) =>
  post<{ result: "QUEUED" | "NEW_TASK" }>("/api/tasks/followUp", { taskId, text });
