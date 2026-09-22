import { Alert, Button, Card, Form, Input, Select, Space, Table, Typography } from "antd";
import { useState } from "react";
import { probeProject, type Effort, type Model, type ProjectChoice, type ProjectView } from "../api";
import { useAction } from "../useAction";
import FolderBrowser from "./FolderBrowser";
import type { Draft } from "./SetupPage";

const MODELS: { value: Model | null; label: string }[] = [
  { value: null, label: "Claude Code's default" }, { value: "sonnet", label: "Sonnet" }, { value: "opus", label: "Opus" }, { value: "fable", label: "Fable" },
];
const EFFORTS: { value: Effort | null; label: string }[] = [
  { value: null, label: "Claude Code's default" }, { value: "low", label: "Low" }, { value: "medium", label: "Medium" },
  { value: "high", label: "High" }, { value: "xhigh", label: "Extra high" }, { value: "max", label: "Max" },
];

interface Props {
  draft: Draft;
  update: (change: Partial<Draft>) => void;
  next: () => void;
  back: () => void;
}

export default function ProjectsStep({ draft, update, next, back }: Props) {
  const [picking, setPicking] = useState(draft.projects.length === 0);
  const [probe, setProbe] = useState<ProjectView | null>(null);
  const [choice, setChoice] = useState<ProjectChoice | null>(null);
  const [duplicate, setDuplicate] = useState(false);
  const { busy, error, run } = useAction();

  const pick = async (folder: string) => {
    const found = await run(() => probeProject(folder));
    if (!found) return;
    setProbe(found);
    setChoice({ folder: found.folder, name: found.name, baseBranch: found.baseBranch ?? "", model: null, effort: null });
  };

  const add = () => {
    if (!choice) return;
    if (draft.projects.some((p) => p.name.toLowerCase() === choice.name.trim().toLowerCase())) {
      setDuplicate(true);
      return;
    }
    update({ projects: [...draft.projects, { ...choice, name: choice.name.trim(), baseBranch: choice.baseBranch.trim() }] });
    setProbe(null);
    setChoice(null);
    setDuplicate(false);
    setPicking(false);
  };

  return (
    <Space orientation="vertical" size="middle" style={{ width: "100%" }}>
      <Typography.Paragraph>The git clones on this machine that Dispatch may work in.</Typography.Paragraph>
      {draft.projects.length > 0 && (
        <Table size="small" pagination={false} rowKey="name" dataSource={draft.projects}
               columns={[
                 { title: "Project", dataIndex: "name" },
                 { title: "Folder", dataIndex: "folder", render: (f: string) => <Typography.Text code>{f}</Typography.Text> },
                 { title: "Base", dataIndex: "baseBranch" },
                 { title: "Model", dataIndex: "model", render: (m: Model | null) => m ?? "default" },
                 { title: "Effort", dataIndex: "effort", render: (e: Effort | null) => e ?? "default" },
                 { title: "", key: "remove", render: (_: unknown, p: ProjectChoice) => (
                     <Button size="small" aria-label={`Remove ${p.name}`}
                             onClick={() => update({ projects: draft.projects.filter((x) => x.name !== p.name) })}>Remove</Button>) },
               ]} />
      )}
      {picking && !choice && <Card size="small" title="Choose a clone"><FolderBrowser onPick={(f) => void pick(f)} /></Card>}
      {error && <Alert type="error" showIcon message={error.message} />}
      {probe && choice && (
        <Card size="small" title={probe.folder}>
          {probe.originHadCredentials && <Alert type="warning" showIcon message="origin's URL holds credentials; it is not copied into the config" style={{ marginBottom: 12 }} />}
          <Form layout="vertical" onFinish={add}>
            <Form.Item label="Name" htmlFor="project-name">
              <Input id="project-name" value={choice.name} onChange={(e) => setChoice({ ...choice, name: e.target.value })} />
            </Form.Item>
            <Form.Item label="Branch tasks start from" htmlFor="project-base">
              <Input id="project-base" value={choice.baseBranch} onChange={(e) => setChoice({ ...choice, baseBranch: e.target.value })} />
            </Form.Item>
            <Form.Item label="Model">
              <Select aria-label="Model" value={choice.model} options={MODELS} onChange={(model) => setChoice({ ...choice, model })} />
            </Form.Item>
            <Form.Item label="Effort">
              <Select aria-label="Effort" value={choice.effort} options={EFFORTS} onChange={(effort) => setChoice({ ...choice, effort })} />
            </Form.Item>
            {duplicate && <Alert type="error" showIcon message="You already added a project with this name." style={{ marginBottom: 12 }} />}
            <Space>
              <Button onClick={() => { setProbe(null); setChoice(null); }}>Cancel</Button>
              <Button type="primary" htmlType="submit" loading={busy} disabled={!choice.name.trim() || !choice.baseBranch.trim()}>Add project</Button>
            </Space>
          </Form>
        </Card>
      )}
      {!picking && !choice && <Button onClick={() => setPicking(true)}>Add another project</Button>}
      <Space>
        <Button onClick={back}>Back</Button>
        <Button type="primary" disabled={draft.projects.length === 0 || choice !== null} onClick={next}>Next</Button>
      </Space>
    </Space>
  );
}
