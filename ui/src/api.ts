// Typed calls to the dispatch ui server. Every page goes through here, so errors look the same everywhere.

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
    response = await fetch(path, { credentials: "same-origin", ...init });
  } catch {
    if (init.signal?.aborted) throw new ApiError("aborted", "the request was stopped");
    throw new ApiError("unreachable", "dispatch ui is not running; start it again and open the link it prints");
  }
  const body = await response.json().catch(() => null);
  if (!response.ok) {
    throw new ApiError(body?.error ?? "http", body?.message ?? `the server answered ${response.status}`);
  }
  if (body === null) throw new ApiError("http", "the server answered without a result");
  return body as T;
}

const get = <T,>(path: string) => send<T>(path, {});

export function post<T>(path: string, body: unknown = {}, signal?: AbortSignal): Promise<T> {
  return send<T>(path, {
    method: "POST",
    headers: { "Content-Type": "application/json" },
    body: JSON.stringify(body),
    signal,
  });
}

export const getOverview = () => get<Overview>("/api/overview");

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

export interface ProjectChoice {
  folder: string;
  name: string;
  baseBranch: string;
  model: Model | null;
  effort: Effort | null;
}

export interface SetupPayload {
  teamName: string | null;
  claude: string;
  authorName: string;
  authorEmail: string;
  projects: ProjectChoice[];
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
export const writeSetup = (payload: SetupPayload) => post<Written>("/api/setup/write", payload);
export const installService = () => post<ServiceView>("/api/service/install");
export const stopService = () => post<ServiceView>("/api/service/stop");
