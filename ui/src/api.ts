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

async function get<T>(path: string): Promise<T> {
  let response: Response;
  try {
    response = await fetch(path, { credentials: "same-origin" });
  } catch {
    throw new ApiError("unreachable", "dispatch ui is not running; start it again and open the link it prints");
  }
  const body = await response.json().catch(() => null);
  if (!response.ok) {
    throw new ApiError(body?.error ?? "http", body?.message ?? `the server answered ${response.status}`);
  }
  return body as T;
}

export const getOverview = () => get<Overview>("/api/overview");
