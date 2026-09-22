import { request } from "@playwright/test";
import { execFileSync, spawn } from "node:child_process";
import { mkdirSync, mkdtempSync, readdirSync, rmSync, writeFileSync } from "node:fs";
import { tmpdir } from "node:os";
import path from "node:path";
import { PATHS_FILE, PORT, STATE_FILE } from "./dispatch";

const TARGET = path.join(import.meta.dirname, "..", "..", "target");

/** A git clone with one commit on main, as ProjectProbe expects. */
function clone(folder: string) {
  mkdirSync(folder, { recursive: true });
  execFileSync("git", ["init", "--quiet", "-b", "main", folder]);
  execFileSync("git", ["-C", folder, "-c", "user.name=e2e", "-c", "user.email=e2e@example.com", "commit", "--quiet", "--allow-empty",
    "-m", "initial"]);
}

function yamlPath(folder: string) {
  return `'${folder.replaceAll("'", "''")}'`;
}

/** Starts `dispatch ui` on a team config and logs the browser in; the returned function stops it. */
export default async function globalSetup() {
  const jar = readdirSync(TARGET).find((name) => /^dispatch-.*\d\.jar$/.test(name));
  if (!jar) throw new Error(`no dispatch jar in ${TARGET}; build it first: ./mvnw -Pui package`);
  const dir = mkdtempSync(path.join(tmpdir(), "dispatch-e2e-"));
  clone(path.join(dir, "alm"));
  clone(path.join(dir, "life"));
  const configFile = path.join(dir, "dispatch.yaml");
  writeFileSync(configFile, `# The e2e team's Dispatch
team: acme
stateDir: ${yamlPath(path.join(dir, "state"))}

telegram:
  admins:
    - 100
  groups:
    - name: acme
      members:
        - id: 100
          name: 'Bold'
        - id: 222
          name: 'Ali'
      projects:
        - alm

delivery:
  authorName: 'Dispatch (acme)'
  authorEmail: 'dispatch@example.com'

scheduler:
  maxConcurrentRuns: 2

limits:
  plan:
    timeout: 15m
    budgetUsd: 2
  execute:
    timeout: 60m
    budgetUsd: 10

agents:
  claude-code:
    command: 'claude'

projects:
  - name: alm
    path: ${yamlPath(path.join(dir, "alm"))}
    baseBranch: main
    agent: claude-code
`);
  // Not a bot token, so the overview's check says so without asking Telegram.
  writeFileSync(path.join(dir, "dispatch.env"), "TELEGRAM_BOT_TOKEN=e2e-not-a-bot-token\n", { mode: 0o600 });

  const ui = spawn("java", ["-jar", path.join(TARGET, jar), "ui", "--no-browser", "--port", String(PORT), "--config", configFile],
    { stdio: ["ignore", "pipe", "inherit"] });
  const link = await new Promise<string>((resolve, reject) => {
    let printed = "";
    const timer = setTimeout(() => reject(new Error(`dispatch ui printed no link within 30 s:\n${printed}`)), 30_000);
    ui.stdout.on("data", (chunk: Buffer) => {
      printed += chunk.toString();
      const found = printed.match(/http:\/\/127\.0\.0\.1:\d+\/\?t=[A-Za-z0-9_-]+/);
      if (found) {
        clearTimeout(timer);
        resolve(found[0]);
      }
    });
    ui.on("exit", (code) => reject(new Error(`dispatch ui exited with ${code}:\n${printed}`)));
  });

  mkdirSync(path.dirname(STATE_FILE), { recursive: true });
  const browser = await request.newContext();
  const login = await browser.get(link, { maxRedirects: 0 });
  if (login.status() !== 302) throw new Error(`the login link answered ${login.status()}`);
  await browser.storageState({ path: STATE_FILE });
  await browser.dispose();
  writeFileSync(PATHS_FILE, JSON.stringify({ configFile, cloneToAdd: path.join(dir, "life") }));

  return async () => {
    ui.kill();
    rmSync(dir, { recursive: true, force: true });
  };
}
