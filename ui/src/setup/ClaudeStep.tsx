import { Alert, Button, Form, Input, Space, Typography } from "antd";
import { useState } from "react";
import { checkClaude, type SetupState } from "../api";
import { useT } from "../i18n/i18n";
import { useAction } from "../useAction";
import type { Draft } from "./SetupPage";

interface Props {
  state: SetupState;
  draft: Draft;
  update: (change: Partial<Draft>) => void;
  next: () => void;
  back: () => void;
}

export default function ClaudeStep({ state, draft, update, next, back }: Props) {
  const t = useT();
  const [command, setCommand] = useState(draft.claude || state.claudeFound || "");
  const [version, setVersion] = useState<string | null>(null);
  const { busy, error, run } = useAction();

  const check = async () => {
    const checked = await run(() => checkClaude(command.trim()));
    if (checked) {
      setVersion(checked.version);
      update({ claude: checked.command });
    }
  };

  return (
    <Space direction="vertical" size="middle" style={{ width: "100%" }}>
      {!state.claudeFound && <Typography.Paragraph>{t("setup.claudeMissing")}</Typography.Paragraph>}
      <Form layout="vertical" onFinish={() => void check()}>
        <Form.Item label={t("setup.claudeCommand")} htmlFor="claude-command">
          <Input id="claude-command" value={command} onChange={(e) => { setCommand(e.target.value); setVersion(null); }} />
        </Form.Item>
        <Button htmlType="submit" loading={busy} disabled={!command.trim()}>{t("common.check")}</Button>
      </Form>
      {error && <Alert type="error" showIcon message={error.message} />}
      {version && <Alert type="success" showIcon message={version} />}
      <Space>
        <Button onClick={back}>{t("common.back")}</Button>
        <Button type="primary" disabled={!version} onClick={next}>{t("common.next")}</Button>
      </Space>
    </Space>
  );
}
