import { Alert, Button, Descriptions, Result, Space, Typography } from "antd";
import { useState } from "react";
import { installService, writeSetup, type ServiceView, type SetupState, type Written } from "../api";
import { useAction } from "../useAction";
import type { Draft } from "./SetupPage";

interface Props {
  state: SetupState;
  draft: Draft;
  back: () => void;
  onDone: () => void;
}

export default function SummaryStep({ state, draft, back, onDone }: Props) {
  const [written, setWritten] = useState<Written | null>(null);
  const [service, setService] = useState<ServiceView | null>(null);
  const writing = useAction();
  const installing = useAction();

  const write = async () => {
    const result = await writing.run(() => writeSetup({
      teamName: state.team ? draft.teamName : null,
      claude: draft.claude,
      authorName: draft.authorName,
      authorEmail: draft.authorEmail,
      projects: draft.projects,
      ...(draft.advanced ? { advanced: draft.advanced } : {}),
    }));
    if (result) setWritten(result);
  };

  const install = async () => {
    const status = await installing.run(() => installService());
    if (status) setService(status);
  };

  if (written) {
    return (
      <Result
        status="success"
        title="Dispatch is set up"
        subTitle={<>Wrote <Typography.Text code>{written.configFile}</Typography.Text> and <Typography.Text code>{written.secretsFile}</Typography.Text> (only you can read it).</>}
        extra={
          <Space orientation="vertical" align="center">
            {!service && <Button type="primary" loading={installing.busy} onClick={() => void install()}>Keep it running in the background</Button>}
            {service && <Alert type={service.running ? "success" : "warning"} showIcon message={`${service.name}: ${service.detail}`} />}
            {installing.error && <Alert type="error" showIcon message={`The background service could not be installed: ${installing.error.message}`} />}
            <Button onClick={onDone}>Open the overview</Button>
          </Space>
        }
      />
    );
  }

  return (
    <Space orientation="vertical" size="middle" style={{ width: "100%" }}>
      <Descriptions column={1} size="small" bordered>
        <Descriptions.Item label="Bot">@{state.bot?.username} {state.team ? "(shared by your team)" : "(just you)"}</Descriptions.Item>
        <Descriptions.Item label="People">{state.members.map((m) => `${m.name} (${m.id})`).join(", ")}</Descriptions.Item>
        {state.team && <Descriptions.Item label="Group">{state.group?.title ?? "none"}</Descriptions.Item>}
        {state.team && <Descriptions.Item label="Team name">{draft.teamName}</Descriptions.Item>}
        <Descriptions.Item label="Projects">
          {draft.projects.map((p) => `${p.name} (${[p.baseBranch, p.model, p.effort].filter(Boolean).join(", ")})`).join(", ")}
        </Descriptions.Item>
        <Descriptions.Item label="Claude Code"><Typography.Text code>{draft.claude}</Typography.Text></Descriptions.Item>
        <Descriptions.Item label="Commits">{draft.authorName} &lt;{draft.authorEmail}&gt;</Descriptions.Item>
        {draft.advanced && (
          <Descriptions.Item label="Advanced">
            {Object.entries(draft.advanced).map(([name, value]) => `${name} ${value}`).join(", ")}
          </Descriptions.Item>
        )}
        <Descriptions.Item label="Config"><Typography.Text code>{state.configFile}</Typography.Text></Descriptions.Item>
      </Descriptions>
      {writing.error && <Alert type="error" showIcon message={writing.error.message} />}
      <Space>
        <Button onClick={back}>Back</Button>
        <Button type="primary" loading={writing.busy} onClick={() => void write()}>Write this setup</Button>
      </Space>
    </Space>
  );
}
