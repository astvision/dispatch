import { render, screen } from "@testing-library/react";
import { afterEach, expect, test, vi } from "vitest";
import OverviewPage from "./OverviewPage";
import type { Overview } from "./api";

const overview: Overview = {
  version: "0.1.0",
  configFile: "/home/bold/.config/dispatch/dispatch.yaml",
  stateDir: "/home/bold/.local/state/dispatch",
  configured: true,
  service: { name: "systemd user service dispatch", installed: true, running: true, detail: "active (running)", notes: [] },
  findings: [
    { level: "OK", area: "bot", message: "bot @dispatch_task_bot (topics off)" },
    { level: "WARN", area: "gh", message: "gh: not logged in or not installed (gh); pull requests will fail until you run: gh auth login" },
  ],
};

function answer(status: number, body: unknown) {
  vi.stubGlobal("fetch", vi.fn().mockResolvedValue(new Response(JSON.stringify(body), { status })));
}

afterEach(() => vi.unstubAllGlobals());

test("shows the service and every finding", async () => {
  answer(200, overview);

  render(<OverviewPage />);

  expect(await screen.findByText("bot @dispatch_task_bot (topics off)")).toBeInTheDocument();
  expect(screen.getByText(/gh auth login/)).toBeInTheDocument();
  expect(screen.getByText("Running")).toBeInTheDocument();
  expect(screen.getByText("0.1.0")).toBeInTheDocument();
});

test("before setup it says how to set Dispatch up", async () => {
  answer(200, {
    ...overview,
    configured: false,
    service: { ...overview.service, installed: false, running: false, detail: "not installed" },
    findings: [{ level: "FAIL", area: "config", message: "config: no config at x; create one with: dispatch init" }],
  });

  render(<OverviewPage />);

  expect(await screen.findByText(/not set up yet/)).toBeInTheDocument();
  expect(screen.getByText("Not installed")).toBeInTheDocument();
});

test("an ended session says to open a new link", async () => {
  answer(401, { error: "session", message: "this page's session ended; restart dispatch ui and open the link it prints" });

  render(<OverviewPage />);

  expect(await screen.findByText(/restart dispatch ui/)).toBeInTheDocument();
});
