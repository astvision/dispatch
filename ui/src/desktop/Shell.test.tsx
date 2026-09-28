import { fireEvent, render, screen } from "@testing-library/react";
import { useContext } from "react";
import { afterEach, expect, test, vi } from "vitest";
import * as api from "../api";
import type { Overview } from "../api";
import { LanguageProvider } from "../i18n/i18n";
import { RestartContext } from "../restart";
import Shell, { DESKTOP_PAGES } from "./Shell";
import { DesktopProviders } from "./status";

vi.mock("../api", async (importOriginal) => ({
  ...(await importOriginal<typeof import("../api")>()),
  getOverview: vi.fn(),
}));

afterEach(() => vi.resetAllMocks());

const running: Overview = {
  configured: true, version: "0.2.0", name: null, configFile: "/home/ann/.config/dispatch/dispatch.yaml", stateDir: "/home/ann/.local/state",
  service: { name: "systemd user service dispatch.service", installed: true, running: true, detail: "active", notes: [] },
  findings: [
    { level: "OK", area: "config", message: "config /home/ann/.config/dispatch/dispatch.yaml" },
    { level: "WARN", area: "gh", message: "gh: not logged in or not installed (gh)" },
  ],
};
const remembered = { getItem: () => null, setItem: () => {} };

/** Stands in for a page whose save needs a restart. */
function Saver() {
  const { mark } = useContext(RestartContext);
  return <button onClick={() => mark(true)}>save</button>;
}

function renderShell(onSelect = vi.fn()) {
  render(
    <LanguageProvider storage={remembered} languages={["en-US"]}>
      <DesktopProviders>
        <Shell pages={DESKTOP_PAGES} selected="/" onSelect={onSelect}><Saver /></Shell>
      </DesktopProviders>
    </LanguageProvider>,
  );
  return onSelect;
}

test("the strip shows the service and the checks in words beside their lamps", async () => {
  vi.mocked(api.getOverview).mockResolvedValue(running);

  renderShell();

  expect(await screen.findByText("Service running")).toBeInTheDocument();
  expect(screen.getByText("1 warning")).toBeInTheDocument();
  expect(api.getOverview).toHaveBeenCalledTimes(1);
});

test("a problem outweighs a warning, and a stopped service says so", async () => {
  vi.mocked(api.getOverview).mockResolvedValue({
    ...running,
    service: { ...running.service, running: false, detail: "inactive" },
    findings: [...running.findings, { level: "FAIL", area: "bot", message: "bot: Telegram refused the token" },
      { level: "FAIL", area: "config", message: "config: broken" }],
  });

  renderShell();

  expect(await screen.findByText("Service stopped")).toBeInTheDocument();
  expect(screen.getByText("2 problems")).toBeInTheDocument();
});

test("a save that needs a restart lights the restart lamp with its button", async () => {
  vi.mocked(api.getOverview).mockResolvedValue(running);
  renderShell();
  await screen.findByText("Service running");

  fireEvent.click(screen.getByRole("button", { name: "save" }));

  expect(screen.getByText("Restart to apply")).toBeInTheDocument();
  expect(screen.getByRole("button", { name: "Restart now" })).toBeInTheDocument();
});

test("the rail moves between pages, and the switch turns every word Mongolian", async () => {
  vi.mocked(api.getOverview).mockResolvedValue(running);
  const onSelect = renderShell();
  await screen.findByText("Service running");

  fireEvent.click(screen.getByText("People"));
  fireEvent.click(screen.getByRole("button", { name: "Монгол" }));

  expect(onSelect).toHaveBeenCalledWith("/people");
  expect(screen.getByText("Хүмүүс")).toBeInTheDocument();
  expect(screen.getByText("Сервис ажиллаж байна")).toBeInTheDocument();
  expect(screen.getByRole("button", { name: "Монгол" })).toHaveAttribute("aria-pressed", "true");
});

test("before setup the strip says so and the rail offers setup only", async () => {
  vi.mocked(api.getOverview).mockResolvedValue({ ...running, configured: false, findings: [],
    service: { ...running.service, installed: false, running: false } });

  render(
    <LanguageProvider storage={remembered} languages={["en-US"]}>
      <DesktopProviders>
        <Shell pages={[{ key: "setup", label: "nav.setup" }]} selected="setup" onSelect={vi.fn()}><p>wizard</p></Shell>
      </DesktopProviders>
    </LanguageProvider>,
  );

  expect(await screen.findByText("Not set up yet")).toBeInTheDocument();
  expect(screen.getByText("Setup")).toBeInTheDocument();
  expect(screen.queryByText("Projects")).not.toBeInTheDocument();
});
