import { Alert, Button, Form, Input, Space, Typography } from "antd";
import { useState } from "react";
import { checkToken, type BotView, type SetupState } from "../api";
import { useAction } from "../useAction";

export default function BotStep({ state, next, back }: { state: SetupState; next: () => void; back: () => void }) {
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
      <Typography.Paragraph>Create a bot with @BotFather (/newbot) in Telegram, then paste its token.</Typography.Paragraph>
      <Form layout="vertical" onFinish={() => void check()}>
        <Form.Item label="Bot token" htmlFor="bot-token">
          <Input.Password id="bot-token" value={token} onChange={(e) => setToken(e.target.value)} autoComplete="off" />
        </Form.Item>
        <Button htmlType="submit" loading={busy} disabled={!token.trim()}>Check</Button>
      </Form>
      {error && <Alert type="error" showIcon message={error.message} />}
      {bot && (
        <Alert
          type="success"
          showIcon
          message={`@${bot.username}`}
          description={bot.topicsEnabled ? undefined : "Tip: turn on topics for the bot in @BotFather, and each task gets its own topic."}
        />
      )}
      <Space>
        <Button onClick={back}>Back</Button>
        <Button type="primary" disabled={!bot} onClick={next}>Next</Button>
      </Space>
    </Space>
  );
}
