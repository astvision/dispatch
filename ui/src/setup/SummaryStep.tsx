import { Alert, Button, Descriptions, Result, Space, Typography } from "antd";
import { useState } from "react";
import { installService, writeSetup, type ServiceView, type SetupState, type Written } from "../api";
import { useT } from "../i18n/i18n";
import { useAction } from "../useAction";
import type { Draft } from "./SetupPage";

interface Props {
  state: SetupState;
  draft: Draft;
  back: () => void;
  onDone: () => void;
}

export default function SummaryStep({ state, draft, back, onDone }: Props) {
  const t = useT();
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
      ...(draft.workers ? { workers: draft.workers } : {}),
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
        title={t("setup.done")}
        subTitle={
          <>
            <div>{t("setup.wroteConfig")} <Typography.Text code>{written.configFile}</Typography.Text></div>
            <div>{t("setup.wroteSecrets")} <Typography.Text code>{written.secretsFile}</Typography.Text></div>
          </>
        }
        extra={
          <Space orientation="vertical" align="center">
            {!service && <Button type="primary" loading={installing.busy} onClick={() => void install()}>{t("setup.keepRunning")}</Button>}
            {service && <Alert type={service.running ? "success" : "warning"} showIcon message={`${service.name}: ${service.detail}`} />}
            {installing.error && <Alert type="error" showIcon message={t("setup.installFailed", { error: installing.error.message })} />}
            <Button onClick={onDone}>{t("setup.openOverview")}</Button>
          </Space>
        }
      />
    );
  }

  return (
    <Space orientation="vertical" size="middle" style={{ width: "100%" }}>
      <Descriptions column={1} size="small" bordered>
        <Descriptions.Item label={t("setup.bot")}>@{state.bot?.username} {t(state.team ? "setup.sharedByTeam" : "setup.justYou")}</Descriptions.Item>
        <Descriptions.Item label={t("nav.people")}>{state.members.map((m) => `${m.name} (${m.id})`).join(", ")}</Descriptions.Item>
        {state.team && <Descriptions.Item label={t("setup.groupChat")}>{state.group?.title ?? t("setup.none")}</Descriptions.Item>}
        {state.team && <Descriptions.Item label={t("setup.teamName")}>{draft.teamName}</Descriptions.Item>}
        {state.team && draft.workers && (
          <Descriptions.Item label={t("setup.workers")}>{t("setup.workersAt", { url: draft.workers.publicUrl, port: draft.workers.port })}</Descriptions.Item>
        )}
        <Descriptions.Item label={t("setup.projects")}>
          {draft.projects.map((p) => `${p.name} (${[p.baseBranch, p.model, p.effort].filter(Boolean).join(", ")})`).join(", ")}
        </Descriptions.Item>
        <Descriptions.Item label="Claude Code"><Typography.Text code>{draft.claude}</Typography.Text></Descriptions.Item>
        <Descriptions.Item label={t("setup.commits")}>{draft.authorName} &lt;{draft.authorEmail}&gt;</Descriptions.Item>
        {draft.advanced && (
          <Descriptions.Item label={t("setup.advanced")}>
            {Object.entries(draft.advanced).map(([name, value]) => `${name} ${value}`).join(", ")}
          </Descriptions.Item>
        )}
        <Descriptions.Item label={t("overview.config")}><Typography.Text code>{state.configFile}</Typography.Text></Descriptions.Item>
      </Descriptions>
      {writing.error && <Alert type="error" showIcon message={writing.error.message} />}
      <Space>
        <Button onClick={back}>{t("common.back")}</Button>
        <Button type="primary" loading={writing.busy} onClick={() => void write()}>{t("setup.write")}</Button>
      </Space>
    </Space>
  );
}
