import type { ConfigView } from "../api";

/** A team's config as the server shows it: two members, Bold an admin, and two projects. */
export const teamConfig: ConfigView = {
  version: "v1",
  configFile: "/home/bold/.config/dispatch/dispatch.yaml",
  personal: false,
  admins: [100],
  service: { name: "systemd user service dispatch.service", installed: true, running: true, detail: "active", notes: [] },
  settings: {
    planTimeout: "15m", planBudgetUsd: 2, executeTimeout: "1h", executeBudgetUsd: 10, maxConcurrentRuns: 2,
    authorName: "Dispatch (acme)", authorEmail: "dispatch@example.com", claudeCommand: "claude", ghCommand: "gh",
  },
  projects: [
    { name: "alm", alias: null, path: "/home/bold/alm", repo: null, baseBranch: "main", group: "acme", model: null, effort: null,
      plan: null, execute: null, agent: "claude-code" },
    { name: "crm", alias: "c", path: "/home/bold/crm", repo: null, baseBranch: "main", group: "acme", model: "opus", effort: null,
      plan: { model: "fable", effort: null }, execute: null, agent: "claude-code" },
  ],
  groups: [{
    name: "acme", chatId: -1001234567890, projects: ["alm", "crm"],
    members: [{ id: 100, name: "Bold", admin: true }, { id: 222, name: "Ali", admin: false }],
  }],
};

export const saved = { saved: true, restartNeeded: true, version: "v2" };

/** A save that changed nothing (e.g. setting an admin to the state they already have): no restart is needed. */
export const savedNoRestart = { saved: true, restartNeeded: false, version: "v1" };
