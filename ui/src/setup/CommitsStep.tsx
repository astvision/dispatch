import { Alert, Button, Form, Input, Space, Typography } from "antd";
import { useState } from "react";
import type { SetupState } from "../api";
import type { Draft } from "./SetupPage";

interface Props {
  state: SetupState;
  draft: Draft;
  update: (change: Partial<Draft>) => void;
  next: () => void;
  back: () => void;
}

export default function CommitsStep({ state, draft, update, next, back }: Props) {
  const firstName = state.members[0]?.name.split(/\s+/)[0] ?? "";
  const [name, setName] = useState(draft.authorName || `Dispatch (${firstName})`);
  const [email, setEmail] = useState(draft.authorEmail || state.authorEmail || "");
  const [missing, setMissing] = useState(false);

  const submit = () => {
    if (!name.trim() || !email.trim()) {
      setMissing(true);
      return;
    }
    update({ authorName: name.trim(), authorEmail: email.trim() });
    next();
  };

  return (
    <Space direction="vertical" size="middle" style={{ width: "100%" }}>
      <Typography.Paragraph>Dispatch commits each task's changes as:</Typography.Paragraph>
      <Form layout="vertical" onFinish={submit}>
        <Form.Item label="Author name" htmlFor="author-name">
          <Input id="author-name" value={name} onChange={(e) => setName(e.target.value)} />
        </Form.Item>
        <Form.Item label="Author email" htmlFor="author-email">
          <Input id="author-email" type="email" value={email} onChange={(e) => setEmail(e.target.value)} />
        </Form.Item>
        {missing && <Alert type="error" showIcon message="Both are needed." style={{ marginBottom: 16 }} />}
        <Space>
          <Button onClick={back}>Back</Button>
          <Button type="primary" htmlType="submit">Next</Button>
        </Space>
      </Form>
    </Space>
  );
}
