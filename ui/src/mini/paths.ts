/** The Mini App's screens and their paths. Telegram always opens it at "/", so a path only matters once inside. */

export type Field = "baseBranch" | "alias" | "model" | "effort" | "plan" | "execute";

export const FIELDS: Field[] = ["baseBranch", "alias", "model", "effort", "plan", "execute"];

/** Pages from `dispatch ui` reached from the home screen, by path. */
export type PagePath = "/tasks" | "/group-tasks" | "/people" | "/settings" | "/logs" | "/overview";

export type Screen =
  | { kind: "home" }
  | { kind: "page"; path: PagePath }
  | { kind: "add" }
  | { kind: "project"; name: string }
  | { kind: "field"; name: string; field: Field };

const PAGES: PagePath[] = ["/tasks", "/group-tasks", "/people", "/settings", "/logs", "/overview"];
export const ADMIN_PAGES: PagePath[] = ["/group-tasks", "/people", "/settings", "/logs", "/overview"];

// The server answers a path whose last segment has a dot as a file, not as this page, so a dot is escaped too.
const segment = (name: string) => encodeURIComponent(name).replace(/\./g, "%2E");

export const projectPath = (name: string) => `/p/${segment(name)}`;
export const fieldPath = (name: string, field: Field) => `${projectPath(name)}/edit/${field}`;
export const ADD_PATH = "/projects/add";

export function screenOf(path: string): Screen {
  if (PAGES.includes(path as PagePath)) return { kind: "page", path: path as PagePath };
  if (path === ADD_PATH) return { kind: "add" };
  const project = /^\/p\/([^/]+)(?:\/edit\/([^/]+))?$/.exec(path);
  if (project) {
    let name: string;
    try {
      name = decodeURIComponent(project[1]);
    } catch {
      return { kind: "home" };
    }
    const field = project[2] as Field | undefined;
    if (field === undefined) return { kind: "project", name };
    return FIELDS.includes(field) ? { kind: "field", name, field } : { kind: "project", name };
  }
  return { kind: "home" };
}

/** Where Back goes from each screen: one level up, never out of the Mini App. */
export function parentOf(screen: Screen): string | null {
  switch (screen.kind) {
    case "home":
      return null;
    case "field":
      return projectPath(screen.name);
    default:
      return "/";
  }
}
