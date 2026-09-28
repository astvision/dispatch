import { Alert, Button, Form, Input, Space, Typography } from "antd";
import { useState } from "react";
import { checkToken, type BotView, type SetupState } from "../api";
import { useT } from "../i18n/i18n";
import { useAction } from "../useAction";

export default function BotStep({ state, next, back }: { state: SetupState; next: () => void; back: () => void }) {
  const t = useT();
  const [token, setToken] = useState("");
  const [bot, setBot] = useState<BotView | null>(state.bot);
  const { busy, error, run } = useAction();

  const check = async () => {
    const checked = await run(() => checkToken(token.trim()));
    if (checked) {
      setBot(checked);
      setToken(""); // the page keeps no copy once the server has it
    }
  };

  return (
    <Space direction="vertical" size="middle" style={{ width: "100%" }}>
      <Typography.Paragraph>{t("setup.botHow")}</Typography.Paragraph>
      <Form layout="vertical" onFinish={() => void check()}>
        <Form.Item label={t("setup.botToken")} htmlFor="bot-token">
          <Input.Password id="bot-token" value={token} onChange={(e) => setToken(e.target.value)} autoComplete="off" />
        </Form.Item>
        <Button htmlType="submit" loading={busy} disabled={!token.trim()}>{t("common.check")}</Button>
      </Form>
      {error && <Alert type="error" showIcon message={error.message} />}
      {bot && (
        <Alert
          type="success"
          showIcon
          message={`@${bot.username}`}
          description={bot.topicsEnabled ? undefined : t("setup.topicsTip")}
        />
      )}
      <Space>
        <Button onClick={back}>{t("common.back")}</Button>
        <Button type="primary" disabled={!bot} onClick={next}>{t("common.next")}</Button>
      </Space>
    </Space>
  );
}
