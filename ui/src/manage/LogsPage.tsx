import { Alert, Card, Empty, Input, Segmented, Space, Spin, Typography } from "antd";
import { useEffect, useState } from "react";
import { ApiError, getLogs, type LogLevel, type Logs } from "../api";

const LEVELS = ["All", "INFO", "WARN", "ERROR"];

/** The service log's last lines, asked for again {@code intervalMs} after each answer, while the page is open. */
export default function LogsPage({ intervalMs = 2000 }: { intervalMs?: number }) {
  const [level, setLevel] = useState<LogLevel | null>(null);
  const [event, setEvent] = useState<string | null>(null);
  const [logs, setLogs] = useState<Logs | null>(null);
  const [error, setError] = useState<ApiError | null>(null);

  useEffect(() => {
    let stopped = false;
    let timer: ReturnType<typeof setTimeout> | undefined;
    const tick = async () => {
      try {
        const answer = await getLogs({ lines: 200, level, event });
        if (!stopped) {
          setLogs(answer);
          setError(null);
        }
      } catch (e) {
        if (!stopped) setError(e instanceof ApiError ? e : new ApiError("unknown", String(e)));
      }
      if (!stopped) timer = setTimeout(() => void tick(), intervalMs);
    };
    void tick();
    return () => {
      stopped = true;
      clearTimeout(timer);
    };
  }, [level, event, intervalMs]);

  return (
    <Card title="Logs" extra={
      <Space wrap>
        <Segmented options={LEVELS} value={level ?? "All"} onChange={(value) => setLevel(value === "All" ? null : (value as LogLevel))} />
        <Input.Search aria-label="Event" placeholder="event, e.g. task." allowClear onSearch={(value) => setEvent(value.trim() || null)} />
      </Space>
    }>
      {error && <Alert type="error" showIcon message={error.message} />}
      {!logs && !error && <Spin />}
      {logs && !logs.exists && <Empty description="No log yet: the background service writes it once it runs." />}
      {logs && logs.exists && logs.lines.length === 0 && <Empty description="No lines match." />}
      {logs && logs.lines.length > 0 && (
        <pre style={{ whiteSpace: "pre-wrap", fontSize: 12, maxHeight: "70vh", overflow: "auto" }}>{logs.lines.join("\n")}</pre>
      )}
      {logs && <Typography.Text type="secondary">{logs.file}</Typography.Text>}
    </Card>
  );
}
