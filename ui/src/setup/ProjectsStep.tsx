import { Alert, Button, Card, Collapse, Form, Input, Select, Space, Table, Typography } from "antd";
import { useState } from "react";
import { probeProject, type Effort, type Model, type PhaseChoice, type ProjectChoice, type ProjectView } from "../api";
import { useT } from "../i18n/i18n";
import { efforts, models, phaseEfforts, phaseModels } from "../options";
import { useAction } from "../useAction";
import FolderBrowser from "./FolderBrowser";
import type { Draft } from "./SetupPage";

/** The Advanced section of one project: closed and empty unless someone opens it. */
interface Extra {
  alias: string;
  plan: PhaseChoice;
  execute: PhaseChoice;
}

const NO_EXTRA: Extra = { alias: "", plan: { model: null, effort: null }, execute: { model: null, effort: null } };
const chosen = (phase: PhaseChoice) => (phase.model === null && phase.effort === null ? null : phase);

interface Props {
  draft: Draft;
  update: (change: Partial<Draft>) => void;
  next: () => void;
  back: () => void;
}

export default function ProjectsStep({ draft, update, next, back }: Props) {
  const t = useT();
  const [picking, setPicking] = useState(draft.projects.length === 0);
  const [probe, setProbe] = useState<ProjectView | null>(null);
  const [choice, setChoice] = useState<ProjectChoice | null>(null);
  const [duplicate, setDuplicate] = useState(false);
  const [extra, setExtra] = useState<Extra>(NO_EXTRA);
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
    const alias = extra.alias.trim();
    const plan = chosen(extra.plan);
    const execute = chosen(extra.execute);
    update({ projects: [...draft.projects, {
      ...choice, name: choice.name.trim(), baseBranch: choice.baseBranch.trim(),
      ...(alias ? { alias } : {}), ...(plan ? { plan } : {}), ...(execute ? { execute } : {}),
    }] });
    setExtra(NO_EXTRA);
    setProbe(null);
    setChoice(null);
    setDuplicate(false);
    setPicking(false);
  };

  return (
    <Space orientation="vertical" size="middle" style={{ width: "100%" }}>
      <Typography.Paragraph>{t("setup.clonesHere")}</Typography.Paragraph>
      {draft.projects.length > 0 && (
        <Table size="small" pagination={false} rowKey="name" dataSource={draft.projects}
               columns={[
                 { title: t("projects.project"), dataIndex: "name" },
                 { title: t("projects.folder"), dataIndex: "folder", render: (f: string) => <Typography.Text code>{f}</Typography.Text> },
                 { title: t("projects.branch"), dataIndex: "baseBranch", render: (b: string) => <Typography.Text code>{b}</Typography.Text> },
                 { title: t("projects.model"), dataIndex: "model", render: (m: Model | null) => m ?? t("setup.default") },
                 { title: t("projects.effort"), dataIndex: "effort", render: (e: Effort | null) => e ?? t("setup.default") },
                 { title: "", key: "remove", render: (_: unknown, p: ProjectChoice) => (
                     <Button size="small" aria-label={t("common.removeNamed", { name: p.name })}
                             onClick={() => update({ projects: draft.projects.filter((x) => x.name !== p.name) })}>{t("common.remove")}</Button>) },
               ]} />
      )}
      {picking && !choice && <Card size="small" title={t("projects.chooseClone")}><FolderBrowser onPick={(f) => void pick(f)} /></Card>}
      {error && <Alert type="error" showIcon message={error.message} />}
      {probe && choice && (
        <Card size="small" title={probe.folder}>
          {probe.originHadCredentials && <Alert type="warning" showIcon message={t("projects.credentials")} style={{ marginBottom: 12 }} />}
          <Form layout="vertical" onFinish={add}>
            <Form.Item label={t("projects.name")} htmlFor="project-name">
              <Input id="project-name" value={choice.name} onChange={(e) => setChoice({ ...choice, name: e.target.value })} />
            </Form.Item>
            <Form.Item label={t("projects.baseBranch")} htmlFor="project-base" extra={t("projects.baseBranchHelp")}>
              <Input id="project-base" value={choice.baseBranch} onChange={(e) => setChoice({ ...choice, baseBranch: e.target.value })} />
            </Form.Item>
            <Form.Item label={t("projects.model")}>
              <Select aria-label={t("projects.model")} value={choice.model} options={models(t)}
                      onChange={(model) => setChoice({ ...choice, model: model as Model | null })} />
            </Form.Item>
            <Form.Item label={t("projects.effort")}>
              <Select aria-label={t("projects.effort")} value={choice.effort} options={efforts(t)} onChange={(effort) => setChoice({ ...choice, effort })} />
            </Form.Item>
            <Collapse size="small" style={{ marginBottom: 16 }} items={[{ key: "advanced", label: t("setup.advanced"), children: (
              <>
                <Form.Item label={t("projects.alias")} htmlFor="project-alias" extra={t("projects.aliasHelp")}>
                  <Input id="project-alias" value={extra.alias} onChange={(e) => setExtra({ ...extra, alias: e.target.value })} />
                </Form.Item>
                <Form.Item label={t("projects.planModel")}>
                  <Select aria-label={t("projects.planModel")} value={extra.plan.model} options={phaseModels(t)}
                          onChange={(model) => setExtra({ ...extra, plan: { ...extra.plan, model } })} />
                </Form.Item>
                <Form.Item label={t("projects.planEffort")}>
                  <Select aria-label={t("projects.planEffort")} value={extra.plan.effort} options={phaseEfforts(t)}
                          onChange={(effort) => setExtra({ ...extra, plan: { ...extra.plan, effort } })} />
                </Form.Item>
                <Form.Item label={t("projects.executeModel")}>
                  <Select aria-label={t("projects.executeModel")} value={extra.execute.model} options={phaseModels(t)}
                          onChange={(model) => setExtra({ ...extra, execute: { ...extra.execute, model } })} />
                </Form.Item>
                <Form.Item label={t("projects.executeEffort")}>
                  <Select aria-label={t("projects.executeEffort")} value={extra.execute.effort} options={phaseEfforts(t)}
                          onChange={(effort) => setExtra({ ...extra, execute: { ...extra.execute, effort } })} />
                </Form.Item>
              </>) }]} />
            {duplicate && <Alert type="error" showIcon message={t("setup.duplicate")} style={{ marginBottom: 12 }} />}
            <Space>
              <Button onClick={() => { setProbe(null); setChoice(null); }}>{t("common.cancel")}</Button>
              <Button type="primary" htmlType="submit" loading={busy} disabled={!choice.name.trim() || !choice.baseBranch.trim()}>{t("projects.addProject")}</Button>
            </Space>
          </Form>
        </Card>
      )}
      {!picking && !choice && <Button onClick={() => setPicking(true)}>{t("setup.addAnother")}</Button>}
      <Space>
        <Button onClick={back}>{t("common.back")}</Button>
        <Button type="primary" disabled={draft.projects.length === 0 || choice !== null} onClick={next}>{t("common.next")}</Button>
      </Space>
    </Space>
  );
}
