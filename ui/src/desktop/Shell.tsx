import { MenuOutlined } from "@ant-design/icons";
import { Button, Dropdown, Menu, Spin } from "antd";
import { Suspense, useContext, useEffect, type ReactNode } from "react";
import type { Live, Overview } from "../api";
import { useLanguage, useT, type Key } from "../i18n/i18n";
import { RestartContext } from "../restart";
import { RestartStatus } from "../RestartNotice";
import { useRestart } from "../useRestart";
import { useDesktopStatus } from "./status";

export interface ShellPage {
  key: string;
  label: Key;
}

export const DESKTOP_PAGES: ShellPage[] = [
  { key: "/", label: "nav.overview" },
  { key: "/tasks", label: "nav.tasks" },
  { key: "/projects", label: "nav.projects" },
  { key: "/people", label: "nav.people" },
  { key: "/settings", label: "nav.settings" },
  { key: "/logs", label: "nav.logs" },
];

export type Colour = "green" | "amber" | "red" | "quiet";

/** A lamp is never colour alone: its words say the same. */
export function Lamp({ colour, title, children }: { colour: Colour; title?: string; children: ReactNode }) {
  return <span className={`lamp ${colour}`} title={title}><i aria-hidden="true" /><span>{children}</span></span>;
}

function ServiceLamp({ overview }: { overview: Overview }) {
  const t = useT();
  if (!overview.configured) return <Lamp colour="quiet">{t("strip.service.notSetUp")}</Lamp>;
  if (!overview.service.installed) return <Lamp colour="quiet">{t("strip.service.none")}</Lamp>;
  return overview.service.running
    ? <Lamp colour="green">{t("strip.service.running")}</Lamp>
    : <Lamp colour="red">{t("strip.service.stopped")}</Lamp>;
}

/** A problem outweighs a warning: the lamp shows the worst level and how many findings are at it. */
function ChecksLamp({ overview }: { overview: Overview }) {
  const t = useT();
  const fails = overview.findings.filter((finding) => finding.level === "FAIL").length;
  if (fails > 0) return <Lamp colour="red">{t(fails === 1 ? "strip.checksFail.one" : "strip.checksFail.other", { count: fails })}</Lamp>;
  const warnings = overview.findings.filter((finding) => finding.level === "WARN").length;
  if (warnings > 0) {
    return <Lamp colour="amber">{t(warnings === 1 ? "strip.checksWarn.one" : "strip.checksWarn.other", { count: warnings })}</Lamp>;
  }
  return <Lamp colour="green">{t("strip.checksOk")}</Lamp>;
}

/** What the running bot is doing (D-2): what runs, what waits on you, today's spend, and a bot older or newer than this page. */
function TaskLamps({ live, version }: { live: Live; version: string | null }) {
  const t = useT();
  const waiting = live.waitingOnYou.length;
  return (
    <>
      <Lamp colour={live.running > 0 ? "green" : "quiet"}>{t("strip.running", { count: live.running })}</Lamp>
      <Lamp colour={waiting > 0 ? "amber" : "quiet"}>{t("strip.waitingOnYou", { count: waiting })}</Lamp>
      <Lamp colour="quiet">{t("strip.today", { usd: live.todayUsd })}</Lamp>
      {version && live.version !== version && <Lamp colour="quiet">{t("strip.newVersion", { version })}</Lamp>}
    </>
  );
}

/**
 * "Restart to apply" after a save, until a restart applies it. Mounted only while a restart is needed, so a later save
 * starts from a fresh restart. A failed restart shows why, and the service log's last lines, across the strip.
 */
function RestartLamp({ installed }: { installed: boolean }) {
  const t = useT();
  const { mark } = useContext(RestartContext);
  const { reload } = useDesktopStatus();
  const { phase, error, lines, restart } = useRestart();

  useEffect(() => {
    if (phase !== "done") return;
    mark(null);
    // The service lamp shows the restarted service, not the one read before the save.
    void reload();
  }, [phase, mark, reload]);

  if (phase === "done") return null;
  if (phase === "restarting") return <Lamp colour="amber">{t("restart.restarting")}</Lamp>;
  return (
    <>
      <span className="board-restart">
        <Lamp colour={phase === "failed" ? "red" : "amber"}>{t("strip.restart")}</Lamp>
        {installed
          ? <Button size="small" onClick={() => void restart()}>{t("strip.restartNow")}</Button>
          : <span className="hint">{t("strip.restartByHand")}</span>}
      </span>
      {phase === "failed" && <div className="board-strip-line"><RestartStatus phase={phase} error={error} lines={lines} /></div>}
    </>
  );
}

/** Each language named in itself; the choice is remembered (LanguageProvider). */
function LanguageSwitch() {
  const t = useT();
  const { language, choose } = useLanguage();
  return (
    <div className="board-lang" role="group" aria-label={t("strip.language")}>
      <button type="button" lang="mn" aria-pressed={language === "mn"} onClick={() => choose("mn")}>Монгол</button>
      <span aria-hidden="true">/</span>
      <button type="button" lang="en" aria-pressed={language === "en"} onClick={() => choose("en")}>English</button>
    </div>
  );
}

/**
 * The desktop's frame: the strip of lamps across the top (the service, the checks, "restart to apply", the language),
 * the rail of pages on the left, and the page. Below 640 px the rail becomes a menu button in the strip.
 */
export default function Shell({ pages, selected, onSelect, children }: {
  pages: ShellPage[];
  selected: string;
  onSelect: (key: string) => void;
  children: ReactNode;
}) {
  const t = useT();
  const { overview, error, live } = useDesktopStatus();
  const restart = useContext(RestartContext);
  const items = pages.map(({ key, label }) => ({ key, label: t(label) }));
  const go = ({ key }: { key: string }) => onSelect(key);

  return (
    <div className="board">
      <header className="board-strip">
        <span className="board-menu">
          <Dropdown menu={{ items, selectable: true, selectedKeys: [selected], onClick: go }} trigger={["click"]}>
            <Button type="text" icon={<MenuOutlined />} aria-label={t("strip.menu")} />
          </Dropdown>
        </span>
        <span className="board-name">{overview?.name ?? "Dispatch"}</span>
        {overview && <ServiceLamp overview={overview} />}
        {overview?.configured && <ChecksLamp overview={overview} />}
        {live && <TaskLamps live={live} version={overview?.version ?? null} />}
        {!overview && error && <Lamp colour="red" title={error.message}>{t("strip.statusUnknown")}</Lamp>}
        {restart.installed !== null && <RestartLamp installed={restart.installed} />}
        <LanguageSwitch />
      </header>
      <div className="board-body">
        <nav className="board-rail">
          <Menu mode="inline" selectedKeys={[selected]} items={items} onClick={go} />
        </nav>
        <main className="board-page"><Suspense fallback={<Spin />}>{children}</Suspense></main>
      </div>
    </div>
  );
}
