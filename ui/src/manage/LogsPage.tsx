import { Alert, Empty, Flex, Input, Segmented, Spin, Switch, Typography } from "antd";
import { useEffect, useState } from "react";
import { ApiError, getLogs, type LogLevel, type Logs } from "../api";
import { useT, type Key } from "../i18n/i18n";
import { parseLogLine, type LogRow } from "./logfmt";

const TONES: Record<string, { tone: "green" | "amber" | "red"; word: Key }> = {
  INFO: { tone: "green", word: "logs.info" },
  WARN: { tone: "amber", word: "logs.warn" },
  ERROR: { tone: "red", word: "logs.error" },
};

interface Entry {
  row: LogRow;
  /** Lines that are not logfmt after it: its stack trace, which Log.java prints right under the event's line. */
  more: string[];
}

/** Newest first, each line that is not logfmt kept under the line it follows (a line the tail cut stands alone). */
function entriesOf(lines: string[]): Entry[] {
  const entries: Entry[] = [];
  for (const line of lines) {
    const row = parseLogLine(line);
    const last = entries[entries.length - 1];
    if (row.event === null && last) last.more.push(line);
    else entries.push({ row, more: [] });
  }
  return entries.reverse();
}

/** The local time of day, as the owner reads a log; the whole timestamp on hover. */
function timeOf(ts: string | null) {
  if (ts === null) return "";
  const date = new Date(ts);
  return Number.isNaN(date.getTime()) ? ts : date.toTimeString().slice(0, 8);
}

function EntryView({ entry }: { entry: Entry }) {
  const t = useT();
  const { row, more } = entry;
  const level = row.level === null ? undefined : TONES[row.level];
  return (
    <div className={`log-entry ${level?.tone === "green" ? "" : level?.tone ?? ""}`}>
      {row.event === null ? <pre className="log-raw mono">{row.line}</pre> : (
        <div className="log-row">
          <span className="log-time mono" title={row.ts ?? undefined}>{timeOf(row.ts)}</span>
          <span>
            <i className={`log-dot ${level?.tone ?? ""}`} aria-hidden="true" />
            <span className="sr-only">{level ? t(level.word) : row.level}</span>
          </span>
          <span className="mono">{row.event}</span>
          <span className="mono">{row.task !== null && `#${row.task}`}</span>
          <span className="log-fields mono">
            {row.fields.map(([key, value], index) => <span key={index}><em>{key}=</em>{value}</span>)}
          </span>
        </div>
      )}
      {more.length > 0 && <pre className="log-raw mono">{more.join("\n")}</pre>}
    </div>
  );
}

/**
 * The service log's last lines as rows, newest first, filtered by level, event, task and any text on the server (so a
 * filter searches the whole tail). While Follow is on it asks again {@code intervalMs} after each answer, skipping the
 * request while the tab is hidden.
 */
export default function LogsPage({ intervalMs = 2000 }: { intervalMs?: number }) {
  const t = useT();
  const [level, setLevel] = useState<LogLevel | null>(null);
  const [event, setEvent] = useState<string | null>(null);
  const [task, setTask] = useState<number | null>(null);
  const [text, setText] = useState<string | null>(null);
  const [follow, setFollow] = useState(true);
  const [logs, setLogs] = useState<Logs | null>(null);
  const [error, setError] = useState<ApiError | null>(null);

  useEffect(() => {
    let stopped = false;
    let timer: ReturnType<typeof setTimeout> | undefined;
    let first = true;
    const tick = async () => {
      if (first || document.visibilityState !== "hidden") {
        first = false;
        try {
          const answer = await getLogs({ lines: 200, level, event, task, text });
          if (!stopped) {
            setLogs(answer);
            setError(null);
          }
        } catch (e) {
          if (!stopped) setError(e instanceof ApiError ? e : new ApiError("unknown", String(e)));
        }
      }
      if (!stopped && follow) timer = setTimeout(() => void tick(), intervalMs);
    };
    void tick();
    return () => {
      stopped = true;
      clearTimeout(timer);
    };
  }, [level, event, task, text, follow, intervalMs]);

  // The server matches an event by what it contains, so "run.*" is sent as "run.".
  const searchEvent = (value: string) => setEvent(value.trim().replace(/\*+$/, "") || null);
  const typeTask = (value: string) => {
    const digits = value.trim();
    if (digits === "") setTask(null);
    else if (/^[1-9]\d*$/.test(digits)) setTask(Number(digits));
  };

  return (
    <Flex vertical gap={12}>
      <Flex justify="space-between" align="center" wrap gap={12}>
        <Typography.Title level={4} style={{ margin: 0 }}>{t("logs.title")}</Typography.Title>
        <Flex align="center" gap={8}>
          <Switch size="small" checked={follow} onChange={setFollow} aria-label={t("logs.follow")} />
          <Typography.Text>{t("logs.follow")}</Typography.Text>
        </Flex>
      </Flex>
      <Flex wrap gap={8} align="center">
        <Segmented options={[{ value: "ALL", label: t("logs.all") }, "INFO", "WARN", "ERROR"]} value={level ?? "ALL"}
                   onChange={(value) => setLevel(value === "ALL" ? null : (value as LogLevel))} />
        <Input.Search aria-label={t("logs.event")} placeholder={t("logs.eventHint")} allowClear style={{ width: 200 }}
                      onSearch={searchEvent} />
        <Input aria-label={t("logs.task")} placeholder={t("logs.taskHint")} inputMode="numeric" allowClear style={{ width: 110 }}
               onChange={(e) => typeTask(e.target.value)} />
        <Input.Search aria-label={t("logs.text")} placeholder={t("logs.textHint")} allowClear style={{ width: 200 }}
                      onSearch={(value) => setText(value.trim() || null)} />
      </Flex>
      {error && <Alert type="error" showIcon message={error.message} />}
      {!logs && !error && <Spin />}
      {logs && !logs.exists && <Empty description={t("logs.noLog")} />}
      {logs && logs.exists && logs.lines.length === 0 && <Empty description={t("logs.noLines")} />}
      {logs && logs.lines.length > 0 && (
        <div>{entriesOf(logs.lines).map((entry, index) => <EntryView key={index} entry={entry} />)}</div>
      )}
      {logs && <Typography.Text type="secondary" className="mono" style={{ fontSize: 12 }}>{logs.file}</Typography.Text>}
    </Flex>
  );
}
