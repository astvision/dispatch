import { readFileSync } from "node:fs";
import path from "node:path";

export const PORT = 7890;
/** The browser's session cookie, from the one-time link `dispatch ui` printed. */
export const STATE_FILE = path.join(import.meta.dirname, ".state", "session.json");
/** Where global-setup put the config and the clone to add. */
export const PATHS_FILE = path.join(import.meta.dirname, ".state", "paths.json");

export interface Paths {
  configFile: string;
  cloneToAdd: string;
}

export function paths(): Paths {
  return JSON.parse(readFileSync(PATHS_FILE, "utf8")) as Paths;
}

export function config(): string {
  return readFileSync(paths().configFile, "utf8");
}
