import { Button, Card, Form, Input, InputNumber } from "antd";
import { saveSettings, type Settings } from "../api";
import ManagedPage from "./ManagedPage";
import { useManagedConfig } from "./useManagedConfig";

const required = [{ required: true, message: "Needed." }];

export default function SettingsPage() {
  const { config, loadError, reload, save, saving, saveError, saved } = useManagedConfig();

  return (
    <ManagedPage title="the settings" config={config} loadError={loadError} saveError={saveError} saved={saved} reload={reload}>
      {(current) => (
        <Card title="Settings">
          <Form<Settings> key={current.version} layout="vertical" initialValues={current.settings}
                          onFinish={(values) => void save((version) => saveSettings(version, values))}>
            <Form.Item label="Planning timeout per run" name="planTimeout" rules={required} extra="A number with s, m or h, e.g. 15m">
              <Input />
            </Form.Item>
            <Form.Item label="Planning budget per run (USD)" name="planBudgetUsd" rules={required}>
              <InputNumber min={0.01} step={0.5} />
            </Form.Item>
            <Form.Item label="Execution timeout per run" name="executeTimeout" rules={required}>
              <Input />
            </Form.Item>
            <Form.Item label="Execution budget per run (USD)" name="executeBudgetUsd" rules={required}>
              <InputNumber min={0.01} step={0.5} />
            </Form.Item>
            <Form.Item label="Maximum concurrent runs" name="maxConcurrentRuns" rules={required}>
              <InputNumber min={1} precision={0} />
            </Form.Item>
            <Form.Item label="Commit author name" name="authorName" rules={required}>
              <Input />
            </Form.Item>
            <Form.Item label="Commit author email" name="authorEmail" rules={required}>
              <Input type="email" />
            </Form.Item>
            <Form.Item label="Claude Code command" name="claudeCommand"
                       rules={current.settings.claudeCommand === null ? [] : required}
                       extra="Also runs ✂️ splitting and the assistant. Empty when every project runs on Codex or Gemini CLI.">
              <Input />
            </Form.Item>
            <Form.Item label="GitHub CLI command" name="ghCommand" rules={required}>
              <Input />
            </Form.Item>
            <Button type="primary" htmlType="submit" loading={saving}>Save</Button>
          </Form>
        </Card>
      )}
    </ManagedPage>
  );
}
