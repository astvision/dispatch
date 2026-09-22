import { Alert, Button, Space, Typography } from "antd";
import { useRestart, type RestartPhase } from "./useRestart";

/** What a restart is doing: waiting, done, or failed with the service log's last lines. */
export function RestartStatus({ phase, error, lines }: { phase: RestartPhase; error: string | null; lines: string[] }) {
  if (phase === "restarting") return <Alert type="info" showIcon message="Restarting…" />;
  if (phase === "done") return <Alert type="success" showIcon message="Dispatch is running again." />;
  if (phase === "failed") {
    return (
      <Alert type="error" showIcon message={error}
             description={lines.length > 0 && <pre style={{ whiteSpace: "pre-wrap", margin: 0 }}>{lines.join("\n")}</pre>} />
    );
  }
  return null;
}

/** Shown after a save: the running Dispatch reads its config when it starts. */
export default function RestartNotice({ installed }: { installed: boolean }) {
  const { phase, error, lines, restart } = useRestart();

  return (
    <Space direction="vertical" style={{ width: "100%" }}>
      {phase === "idle" && (
        <Alert type="info" showIcon message="Saved. Restart to apply"
               description={installed ? "The background service keeps the config it started with until it restarts."
                 : <Typography.Text>Dispatch does not run as a background service here: stop it and start it again where it runs.</Typography.Text>}
               action={installed && <Button onClick={() => void restart()}>Restart now</Button>} />
      )}
      <RestartStatus phase={phase} error={error} lines={lines} />
    </Space>
  );
}
