import { Alert, Button, Collapse, Form, Input, InputNumber, Space, Typography } from "antd";
import { useState } from "react";
import type { SetupAdvanced, SetupState } from "../api";
import { useT } from "../i18n/i18n";
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
  const t = useT();
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
      <Typography.Paragraph>{t("setup.commitsAs")}</Typography.Paragraph>
      <Form layout="vertical" onFinish={submit}>
        <Form.Item label={t("setup.authorName")} htmlFor="author-name">
          <Input id="author-name" value={name} onChange={(e) => setName(e.target.value)} />
        </Form.Item>
        <Form.Item label={t("setup.authorEmail")} htmlFor="author-email">
          <Input id="author-email" type="email" value={email} onChange={(e) => setEmail(e.target.value)} />
        </Form.Item>
        <Collapse size="small" style={{ marginBottom: 16 }} items={[{ key: "advanced", label: t("setup.advanced"), children: (
          <>
            <Typography.Paragraph type="secondary">{t("setup.keepDefault")}</Typography.Paragraph>
            <Form.Item label={t("settings.planTimeout")} htmlFor="plan-timeout">
              <Input id="plan-timeout" placeholder="15m" value={advanced.planTimeout ?? ""} onChange={(e) => change({ planTimeout: e.target.value })} />
            </Form.Item>
            <Form.Item label={t("settings.planBudget")} htmlFor="plan-budget">
              <InputNumber id="plan-budget" placeholder="2" min={0.01} value={advanced.planBudgetUsd ?? null}
                           onChange={(value) => change({ planBudgetUsd: value ?? undefined })} />
            </Form.Item>
            <Form.Item label={t("settings.executeTimeout")} htmlFor="execute-timeout">
              <Input id="execute-timeout" placeholder="60m" value={advanced.executeTimeout ?? ""}
                     onChange={(e) => change({ executeTimeout: e.target.value })} />
            </Form.Item>
            <Form.Item label={t("settings.executeBudget")} htmlFor="execute-budget">
              <InputNumber id="execute-budget" placeholder="10" min={0.01} value={advanced.executeBudgetUsd ?? null}
                           onChange={(value) => change({ executeBudgetUsd: value ?? undefined })} />
            </Form.Item>
            <Form.Item label={t("settings.maxConcurrent")} htmlFor="max-runs">
              <InputNumber id="max-runs" placeholder={state.team ? "2" : "1"} min={1} precision={0} value={advanced.maxConcurrentRuns ?? null}
                           onChange={(value) => change({ maxConcurrentRuns: value ?? undefined })} />
            </Form.Item>
            <Form.Item label={t("setup.stateDir")} htmlFor="state-dir">
              <Input id="state-dir" placeholder={t("setup.stateDirDefault")} value={advanced.stateDir ?? ""}
                     onChange={(e) => change({ stateDir: e.target.value })} />
            </Form.Item>
            <Form.Item label={t("settings.ghCommand")} htmlFor="gh-command">
              <Input id="gh-command" placeholder="gh" value={advanced.ghCommand ?? ""} onChange={(e) => change({ ghCommand: e.target.value })} />
            </Form.Item>
          </>) }]} />
        {missing && <Alert type="error" showIcon message={t("setup.bothNeeded")} style={{ marginBottom: 16 }} />}
        <Space>
          <Button onClick={back}>{t("common.back")}</Button>
          <Button type="primary" htmlType="submit">{t("common.next")}</Button>
        </Space>
      </Form>
    </Space>
  );
}
