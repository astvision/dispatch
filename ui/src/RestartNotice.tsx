import { Alert, Button, Space, Typography } from "antd";
import { useT } from "./i18n/i18n";
import { useRestart, type RestartPhase } from "./useRestart";

/** What a restart is doing: waiting, done, or failed with the service log's last lines. */
export function RestartStatus({ phase, error, lines }: { phase: RestartPhase; error: string | null; lines: string[] }) {
  const t = useT();
  if (phase === "restarting") return <Alert type="info" showIcon message={t("restart.restarting")} />;
  if (phase === "done") return <Alert type="success" showIcon message={t("restart.done")} />;
  if (phase === "failed") {
    return (
      <Alert type="error" showIcon message={error}
             description={lines.length > 0 && <pre style={{ whiteSpace: "pre-wrap", margin: 0 }}>{lines.join("\n")}</pre>} />
    );
  }
  return null;
}

/** The Mini App's notice after a save: the running Dispatch reads its config when it starts. */
export default function RestartNotice({ installed }: { installed: boolean }) {
  const t = useT();
  const { phase, error, lines, restart } = useRestart();

  return (
    <Space direction="vertical" style={{ width: "100%" }}>
      {phase === "idle" && (
        <Alert type="info" showIcon message={t("restart.saved")}
               description={installed ? t("restart.keepsConfig") : <Typography.Text>{t("restart.byHand")}</Typography.Text>}
               action={installed && <Button onClick={() => void restart()}>{t("strip.restartNow")}</Button>} />
      )}
      <RestartStatus phase={phase} error={error} lines={lines} />
    </Space>
  );
}
