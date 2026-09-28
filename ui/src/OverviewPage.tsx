import { CheckCircleFilled, CloseCircleFilled, ExclamationCircleFilled } from "@ant-design/icons";
import { Alert, Button, Card, Descriptions, Empty, List, Result, Space, Spin, theme, Typography } from "antd";
import { useContext, useEffect } from "react";
import { installService, stopService, type Finding, type ServiceView } from "./api";
import { Lamp } from "./desktop/Shell";
import { useDesktopStatus } from "./desktop/status";
import { useT, type Key, type Translate } from "./i18n/i18n";
import { RestartContext } from "./restart";
import { RestartStatus } from "./RestartNotice";
import { useAction } from "./useAction";
import { useRestart } from "./useRestart";

const LEVELS = {
  OK: { Icon: CheckCircleFilled, colour: "colorSuccess", label: "overview.ok" },
  WARN: { Icon: ExclamationCircleFilled, colour: "colorWarning", label: "overview.warning" },
  FAIL: { Icon: CloseCircleFilled, colour: "colorError", label: "overview.problem" },
} as const satisfies Record<Finding["level"], { colour: string; label: Key; Icon: unknown }>;

/** The areas Checks names (Checks.java); an agent's area is its name in the config. */
const AREAS: Record<string, Key> = {
  config: "area.config", gh: "area.gh", bot: "area.bot", "claude-code": "area.claude", codex: "area.codex",
  gemini: "area.gemini", state: "area.state", workers: "area.workers", miniApp: "area.miniApp",
};
const PROJECT_AREA = "project ";

/** A finding's area in the page's words: its label, "project NAME" as that project, anything newer as it came. */
function areaLabel(t: Translate, area: string) {
  if (Object.hasOwn(AREAS, area)) return t(AREAS[area]);
  if (area.startsWith(PROJECT_AREA)) return t("area.project", { name: area.slice(PROJECT_AREA.length) });
  return area;
}

function LevelIcon({ level }: { level: Finding["level"] }) {
  const t = useT();
  const { token } = theme.useToken();
  const { Icon, colour, label } = LEVELS[level];
  return <Icon style={{ color: token[colour], fontSize: 16 }} aria-label={t(label)} />;
}

function ServiceState({ service }: { service: ServiceView }) {
  const t = useT();
  if (!service.installed) return <Lamp colour="quiet">{t("overview.notInstalled")}</Lamp>;
  return service.running
    ? <Lamp colour="green">{t("overview.running")}</Lamp>
    : <Lamp colour="red">{t("overview.stopped")}</Lamp>;
}

/**
 * The version and paths, the background service and the checks. The status is the one the strip shows (desktop/status):
 * Check again, and every service action, read it again for both. {@code installAndStop}: only `dispatch ui`'s server
 * installs or stops the service; the Mini App's, inside the service, restarts it and nothing more.
 */
export default function OverviewPage({ installAndStop = false }: { installAndStop?: boolean }) {
  const t = useT();
  const { overview, error, loading, reload } = useDesktopStatus();
  const { mark } = useContext(RestartContext);
  const restarting = useRestart();
  const installing = useAction();
  const stopping = useAction();

  // A restart from here applies whatever a save left waiting for one.
  useEffect(() => {
    if (restarting.phase === "done") mark(null);
  }, [restarting.phase, mark]);

  if (loading && !overview) return <Spin size="large" tip={t("overview.checking")}><div style={{ height: 200 }} /></Spin>;
  if (error) {
    return <Result status="warning" title={t("overview.cannotShow")} subTitle={error.message}
                   extra={<Button onClick={() => void reload()}>{t("common.tryAgain")}</Button>} />;
  }
  if (!overview) return <Empty />;

  const { service } = overview;
  const act = (action: ReturnType<typeof useAction>, call: () => Promise<ServiceView>) => void action.run(call).then(() => reload());
  const refused = installing.error ?? stopping.error;
  return (
    <Space direction="vertical" size="large" style={{ width: "100%" }}>
      <Typography.Title level={4} style={{ margin: 0 }}>{t("overview.title")}</Typography.Title>
      {!overview.configured && <Alert type="info" showIcon message={t("overview.notSetUp")} description={t("overview.notSetUpHint")} />}
      <Card title="Dispatch">
        <Descriptions column={1} size="small">
          <Descriptions.Item label={t("overview.version")}>{overview.version}</Descriptions.Item>
          <Descriptions.Item label={t("overview.config")}><Typography.Text code>{overview.configFile}</Typography.Text></Descriptions.Item>
          <Descriptions.Item label={t("overview.state")}><Typography.Text code>{overview.stateDir}</Typography.Text></Descriptions.Item>
        </Descriptions>
      </Card>
      <Card title={t("overview.service")} extra={<ServiceState service={service} />}>
        <Space direction="vertical" style={{ width: "100%" }}>
          <Typography.Text>{service.name}: {service.detail}</Typography.Text>
          {service.notes.map((note) => <Alert key={note} type="warning" showIcon message={note} />)}
          {!service.installed && overview.configured && <Typography.Text type="secondary">{t("restart.byHand")}</Typography.Text>}
          <Space wrap>
            {service.installed && (
              <Button loading={restarting.phase === "restarting"} onClick={() => void restarting.restart().then(() => reload())}>
                {t("overview.restart")}
              </Button>
            )}
            {installAndStop && service.installed && service.running && (
              <Button loading={stopping.busy} onClick={() => act(stopping, stopService)}>{t("overview.stop")}</Button>
            )}
            {installAndStop && !service.installed && overview.configured && (
              <Button type="primary" loading={installing.busy} onClick={() => act(installing, installService)}>{t("overview.install")}</Button>
            )}
          </Space>
          {refused && <Alert type="error" showIcon message={refused.message} />}
          <RestartStatus phase={restarting.phase} error={restarting.error} lines={restarting.lines} />
        </Space>
      </Card>
      <Card title={t("overview.checks")}
            extra={<Button loading={loading} onClick={() => void reload()}>{t("overview.checkAgain")}</Button>}>
        <List locale={{ emptyText: t("overview.noChecks") }} dataSource={overview.findings}
              renderItem={(finding) => (
                <List.Item>
                  <List.Item.Meta avatar={<LevelIcon level={finding.level} />} title={areaLabel(t, finding.area)}
                                  description={finding.message} />
                </List.Item>
              )} />
      </Card>
    </Space>
  );
}
