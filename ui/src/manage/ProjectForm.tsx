import { Button, Form, Input, Select, Space } from "antd";
import { useState } from "react";
import type { PhaseChoice, ProjectFields } from "../api";
import { EFFORTS, MODELS, PHASE_EFFORTS, PHASE_MODELS, withCurrent } from "../options";

interface Props {
  initial: ProjectFields;
  /** Only a new project's name can be chosen; an existing one keeps its name, which its tasks refer to. */
  nameEditable: boolean;
  /** A new project's group, chosen when the config has more than one; null when editing. */
  groups: string[] | null;
  busy: boolean;
  submitLabel: string;
  onSubmit: (project: ProjectFields, group: string | null) => void;
  onCancel: () => void;
}

export default function ProjectForm({ initial, nameEditable, groups, busy, submitLabel, onSubmit, onCancel }: Props) {
  const [project, setProject] = useState<ProjectFields>(initial);
  const [group, setGroup] = useState<string | null>(groups?.[0] ?? null);
  const set = (change: Partial<ProjectFields>) => setProject((current) => ({ ...current, ...change }));
  const phase = (which: "plan" | "execute", change: Partial<PhaseChoice>) => {
    const next = { model: project[which]?.model ?? null, effort: project[which]?.effort ?? null, ...change };
    set({ [which]: next.model === null && next.effort === null ? null : next });
  };
  const submit = () => onSubmit({
    ...project, name: project.name.trim(), baseBranch: project.baseBranch.trim(), alias: project.alias?.trim() || null,
  }, group);

  return (
    <Form layout="vertical" onFinish={submit}>
      <Form.Item label="Name" htmlFor="project-name">
        <Input id="project-name" value={project.name} disabled={!nameEditable} onChange={(e) => set({ name: e.target.value })} />
      </Form.Item>
      {groups && groups.length > 1 && (
        <Form.Item label="Group">
          <Select aria-label="Group" value={group} options={groups.map((name) => ({ value: name, label: name }))} onChange={setGroup} />
        </Form.Item>
      )}
      <Form.Item label="Branch tasks start from" htmlFor="project-base">
        <Input id="project-base" value={project.baseBranch} onChange={(e) => set({ baseBranch: e.target.value })} />
      </Form.Item>
      <Form.Item label="Alias" htmlFor="project-alias" extra="A short name to use in tasks; leave it empty for none">
        <Input id="project-alias" value={project.alias ?? ""} onChange={(e) => set({ alias: e.target.value })} />
      </Form.Item>
      <Form.Item label="Model">
        <Select aria-label="Model" value={project.model} options={withCurrent(MODELS, project.model)} onChange={(model) => set({ model })} />
      </Form.Item>
      <Form.Item label="Effort">
        <Select aria-label="Effort" value={project.effort} options={EFFORTS} onChange={(effort) => set({ effort })} />
      </Form.Item>
      <Form.Item label="Planning model">
        <Select aria-label="Planning model" value={project.plan?.model ?? null}
                options={withCurrent(PHASE_MODELS, project.plan?.model ?? null)} onChange={(model) => phase("plan", { model })} />
      </Form.Item>
      <Form.Item label="Planning effort">
        <Select aria-label="Planning effort" value={project.plan?.effort ?? null} options={PHASE_EFFORTS}
                onChange={(effort) => phase("plan", { effort })} />
      </Form.Item>
      <Form.Item label="Execution model">
        <Select aria-label="Execution model" value={project.execute?.model ?? null}
                options={withCurrent(PHASE_MODELS, project.execute?.model ?? null)} onChange={(model) => phase("execute", { model })} />
      </Form.Item>
      <Form.Item label="Execution effort">
        <Select aria-label="Execution effort" value={project.execute?.effort ?? null} options={PHASE_EFFORTS}
                onChange={(effort) => phase("execute", { effort })} />
      </Form.Item>
      <Space>
        <Button onClick={onCancel}>Cancel</Button>
        <Button type="primary" htmlType="submit" loading={busy} disabled={!project.name.trim() || !project.baseBranch.trim()}>
          {submitLabel}
        </Button>
      </Space>
    </Form>
  );
}
