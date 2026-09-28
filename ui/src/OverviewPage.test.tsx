import { fireEvent, render, screen } from "@testing-library/react";
import { afterEach, expect, test, vi } from "vitest";
import type { Overview } from "./api";
import { StatusProvider } from "./desktop/status";
import { renderOnBoard } from "./desktop/testing";
import { LanguageProvider } from "./i18n/i18n";
import OverviewPage from "./OverviewPage";

const overview: Overview = {
  version: "0.1.0",
  name: "bold",
  configFile: "/home/bold/.config/dispatch/dispatch.yaml",
  stateDir: "/home/bold/.local/state/dispatch",
  configured: true,
  service: { name: "systemd user service dispatch", installed: true, running: true, detail: "active (running)", notes: [] },
  findings: [
    { level: "OK", area: "bot", message: "bot @dispatch_task_bot (topics off)" },
    { level: "WARN", area: "gh", message: "gh: not logged in or not installed (gh); pull requests will fail until you run: gh auth login" },
  ],
};
const notInstalled: Overview = { ...overview, service: { ...overview.service, installed: false, running: false, detail: "not installed" } };
const remembered = { getItem: () => null, setItem: () => {} };

/** Answers each path with its own fresh response, and records what the page asked for. */
function serve(routes: Record<string, { status?: number; body: unknown }>) {
  const fetch = vi.fn(async (url: RequestInfo | URL) => {
    const answer = routes[new URL(String(url), "http://page").pathname];
    return new Response(JSON.stringify(answer?.body ?? {}), { status: answer?.status ?? 200 });
  });
  vi.stubGlobal("fetch", fetch);
  return (path: string) => fetch.mock.calls.filter(([url]) => String(url) === path).length;
}

/** The desktop's Overview; the Mini App's server cannot install or stop the service, so its Overview says installAndStop={false}. */
function renderPage(languages: string[] = ["en-US"], installAndStop = true) {
  render(
    <LanguageProvider storage={remembered} languages={languages}>
      <StatusProvider><OverviewPage installAndStop={installAndStop} /></StatusProvider>
    </LanguageProvider>,
  );
}

afterEach(() => vi.unstubAllGlobals());

test("shows the service and every finding", async () => {
  serve({ "/api/overview": { body: overview } });

  renderPage();

  expect(await screen.findByText("bot @dispatch_task_bot (topics off)")).toBeInTheDocument();
  expect(screen.getByText(/gh auth login/)).toBeInTheDocument();
  expect(screen.getByText("Running")).toBeInTheDocument();
  expect(screen.getByText("0.1.0")).toBeInTheDocument();
});

test("the strip and the page share one reading of the overview", async () => {
  const asked = serve({ "/api/overview": { body: overview } });

  renderOnBoard(<OverviewPage installAndStop />);

  expect(await screen.findByText("bot @dispatch_task_bot (topics off)")).toBeInTheDocument();
  expect(screen.getByText("Service running")).toBeInTheDocument();
  expect(asked("/api/overview")).toBe(1);
});

test("in Mongolian the page's words are Mongolian and each finding's area has its label", async () => {
  serve({ "/api/overview": { body: { ...overview, findings: [...overview.findings,
    { level: "OK", area: "project crm", message: "project crm: acme/crm, base main" }] } } });

  renderPage(["mn"]);

  expect(await screen.findByText("Сервис")).toBeInTheDocument();
  expect(screen.getByText("Шалгалт")).toBeInTheDocument();
  expect(screen.getByRole("button", { name: "Дахин шалгах" })).toBeInTheDocument();
  expect(screen.getByText("Бот")).toBeInTheDocument();
  expect(screen.getByText("GitHub CLI")).toBeInTheDocument();
  expect(screen.getByText("Төсөл crm")).toBeInTheDocument();
});

test("before setup it says how to set Dispatch up", async () => {
  serve({ "/api/overview": { body: { ...notInstalled, configured: false,
    findings: [{ level: "FAIL", area: "config", message: "config: no config at x; create one with: dispatch init" }] } } });

  renderPage();

  expect(await screen.findByText(/not set up yet/)).toBeInTheDocument();
  expect(screen.getByText("Not installed")).toBeInTheDocument();
  expect(screen.queryByRole("button", { name: "Install" })).not.toBeInTheDocument();
});

test("an ended session says to open a new link", async () => {
  serve({ "/api/overview": { status: 401,
    body: { error: "session", message: "this page's session ended; restart dispatch ui and open the link it prints" } } });

  renderPage();

  expect(await screen.findByText(/restart dispatch ui/)).toBeInTheDocument();
});

test("without a background service Restart is not offered, Install is", async () => {
  const asked = serve({ "/api/overview": { body: notInstalled }, "/api/service/install": { body: overview.service } });

  renderPage();

  expect(await screen.findByText(/stop it and start it again where it runs/)).toBeInTheDocument();
  expect(screen.queryByRole("button", { name: "Restart" })).not.toBeInTheDocument();
  fireEvent.click(screen.getByRole("button", { name: "Install" }));

  await vi.waitFor(() => expect(asked("/api/overview")).toBe(2));
  expect(asked("/api/service/install")).toBe(1);
});

test("a running service can be restarted or stopped from here", async () => {
  const asked = serve({ "/api/overview": { body: overview },
    "/api/service/stop": { body: { ...overview.service, running: false, detail: "inactive" } } });

  renderPage();

  expect(await screen.findByRole("button", { name: "Restart" })).toBeInTheDocument();
  fireEvent.click(screen.getByRole("button", { name: "Stop" }));

  await vi.waitFor(() => expect(asked("/api/overview")).toBe(2));
  expect(asked("/api/service/stop")).toBe(1);
});

test("in the Mini App, whose server cannot, neither Install nor Stop is offered", async () => {
  serve({ "/api/overview": { body: overview } });

  renderPage(["en-US"], false);

  expect(await screen.findByRole("button", { name: "Restart" })).toBeInTheDocument();
  expect(screen.queryByRole("button", { name: "Stop" })).not.toBeInTheDocument();
});
