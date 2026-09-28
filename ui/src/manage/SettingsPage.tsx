import { Button, Card, Form, Input, InputNumber, Space, Typography } from "antd";
import { saveSettings, type Settings } from "../api";
import { useT } from "../i18n/i18n";
import ManagedPage from "./ManagedPage";
import { useManagedConfig } from "./useManagedConfig";

/** The settings grouped as limits, commits and commands, each field with a line of help. */
export default function SettingsPage() {
  const t = useT();
  const { config, loadError, reload, save, saving, saveError } = useManagedConfig();
  const required = [{ required: true, message: t("settings.needed") }];

  return (
    <ManagedPage cannotShow={t("settings.cannotShow")} config={config} loadError={loadError} saveError={saveError} reload={reload}>
      {(current) => (
        <Form<Settings> key={current.version} layout="vertical" initialValues={current.settings}
                        onFinish={(values) => void save((version) => saveSettings(version, values))}>
          <Space direction="vertical" size="large" style={{ width: "100%" }}>
            <Typography.Title level={4} style={{ margin: 0 }}>{t("settings.title")}</Typography.Title>
            <Card title={t("settings.limits")}>
              <Form.Item label={t("settings.planTimeout")} name="planTimeout" rules={required} extra={t("settings.planTimeoutHelp")}>
                <Input className="mono" />
              </Form.Item>
              <Form.Item label={t("settings.planBudget")} name="planBudgetUsd" rules={required} extra={t("settings.planBudgetHelp")}>
                <InputNumber min={0.01} step={0.5} />
              </Form.Item>
              <Form.Item label={t("settings.executeTimeout")} name="executeTimeout" rules={required} extra={t("settings.executeTimeoutHelp")}>
                <Input className="mono" />
              </Form.Item>
              <Form.Item label={t("settings.executeBudget")} name="executeBudgetUsd" rules={required} extra={t("settings.executeBudgetHelp")}>
                <InputNumber min={0.01} step={0.5} />
              </Form.Item>
              <Form.Item label={t("settings.maxConcurrent")} name="maxConcurrentRuns" rules={required} extra={t("settings.maxConcurrentHelp")}>
                <InputNumber min={1} precision={0} />
              </Form.Item>
            </Card>
            <Card title={t("settings.commits")}>
              <Form.Item label={t("settings.authorName")} name="authorName" rules={required} extra={t("settings.authorNameHelp")}>
                <Input />
              </Form.Item>
              <Form.Item label={t("settings.authorEmail")} name="authorEmail" rules={required} extra={t("settings.authorEmailHelp")}>
                <Input type="email" />
              </Form.Item>
            </Card>
            <Card title={t("settings.commands")}>
              <Form.Item label={t("settings.claudeCommand")} name="claudeCommand"
                         rules={current.settings.claudeCommand === null ? [] : required} extra={t("settings.claudeCommandHelp")}>
                <Input className="mono" />
              </Form.Item>
              <Form.Item label={t("settings.ghCommand")} name="ghCommand" rules={required} extra={t("settings.ghCommandHelp")}>
                <Input className="mono" />
              </Form.Item>
            </Card>
            <Button type="primary" htmlType="submit" loading={saving}>{t("common.save")}</Button>
          </Space>
        </Form>
      )}
    </ManagedPage>
  );
}
