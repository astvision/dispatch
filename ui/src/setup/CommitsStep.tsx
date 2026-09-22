import { Alert, Button, Collapse, Form, Input, InputNumber, Space, Typography } from "antd";
import { useState } from "react";
import type { SetupAdvanced, SetupState } from "../api";
import type { Draft } from "./SetupPage";

/** The answers someone typed; blank ones keep their default. */
function filledIn(advanced: SetupAdvanced): SetupAdvanced {
  return Object.fromEntries(Object.entries(advanced)
    .map(([name, value]) => [name, typeof value === "string" ? value.trim() : value])
    .filter(([, value]) => value !== undefined && value !== null && value !== "")) as SetupAdvanced;
}

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
  const [advanced, setAdvanced] = useState<SetupAdvanced>(draft.advanced ?? {});
  const change = (answer: Partial<SetupAdvanced>) => setAdvanced((current) => ({ ...current, ...answer }));

  const submit = () => {
    if (!name.trim() || !email.trim()) {
      setMissing(true);
      return;
    }
    const answers = filledIn(advanced);
    const chosen = Object.keys(answers).length > 0;
    update({
      authorName: name.trim(), authorEmail: email.trim(),
      ...(chosen ? { advanced: answers } : draft.advanced ? { advanced: undefined } : {}),
    });
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
        <Collapse size="small" style={{ marginBottom: 16 }} items={[{ key: "advanced", label: "Advanced", children: (
          <>
            <Typography.Paragraph type="secondary">Leave a field empty to keep its default.</Typography.Paragraph>
            <Form.Item label="Planning timeout per run" htmlFor="plan-timeout">
              <Input id="plan-timeout" placeholder="15m" value={advanced.planTimeout ?? ""} onChange={(e) => change({ planTimeout: e.target.value })} />
            </Form.Item>
            <Form.Item label="Planning budget per run (USD)" htmlFor="plan-budget">
              <InputNumber id="plan-budget" placeholder="2" min={0.01} value={advanced.planBudgetUsd ?? null}
                           onChange={(value) => change({ planBudgetUsd: value ?? undefined })} />
            </Form.Item>
            <Form.Item label="Execution timeout per run" htmlFor="execute-timeout">
              <Input id="execute-timeout" placeholder="60m" value={advanced.executeTimeout ?? ""}
                     onChange={(e) => change({ executeTimeout: e.target.value })} />
            </Form.Item>
            <Form.Item label="Execution budget per run (USD)" htmlFor="execute-budget">
              <InputNumber id="execute-budget" placeholder="10" min={0.01} value={advanced.executeBudgetUsd ?? null}
                           onChange={(value) => change({ executeBudgetUsd: value ?? undefined })} />
            </Form.Item>
            <Form.Item label="Maximum concurrent runs" htmlFor="max-runs">
              <InputNumber id="max-runs" placeholder={state.team ? "2" : "1"} min={1} precision={0} value={advanced.maxConcurrentRuns ?? null}
                           onChange={(value) => change({ maxConcurrentRuns: value ?? undefined })} />
            </Form.Item>
            <Form.Item label="State directory" htmlFor="state-dir">
              <Input id="state-dir" placeholder="the default for this computer" value={advanced.stateDir ?? ""}
                     onChange={(e) => change({ stateDir: e.target.value })} />
            </Form.Item>
            <Form.Item label="GitHub CLI command" htmlFor="gh-command">
              <Input id="gh-command" placeholder="gh" value={advanced.ghCommand ?? ""} onChange={(e) => change({ ghCommand: e.target.value })} />
            </Form.Item>
          </>) }]} />
        {missing && <Alert type="error" showIcon message="Both are needed." style={{ marginBottom: 16 }} />}
        <Space>
          <Button onClick={back}>Back</Button>
          <Button type="primary" htmlType="submit">Next</Button>
        </Space>
      </Form>
    </Space>
  );
}
