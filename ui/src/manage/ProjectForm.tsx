import { Button, Form, Input, Select, Space, Typography } from "antd";
import { useState, type ReactNode } from "react";
import type { AgentType, ManagedProject, PhaseChoice, ProjectFields } from "../api";
import { useT, type Key } from "../i18n/i18n";
import { AGENTS, agentDefault, agentLabel, effortsFor, models, phaseEfforts, phaseModels, withCurrent } from "../options";

/** What an edit of {@code project} starts from: every field as the config has it now. */
export function fieldsOf(project: ManagedProject): ProjectFields {
  return { name: project.name, baseBranch: project.baseBranch, alias: project.alias, model: project.model, effort: project.effort,
    plan: project.plan, execute: project.execute };
}

interface Props {
  initial: ProjectFields;
  /** The agent an existing project runs on; undefined when adding, where the config's agent is used. */
  agent?: AgentType;
  /** Only a new project's name can be chosen; an existing one keeps its name, which its tasks refer to. */
  nameEditable: boolean;
  /** A new project's group, chosen when the config has more than one; null when editing. */
  groups: string[] | null;
  busy: boolean;
  submitLabel: string;
  onSubmit: (project: ProjectFields, group: string | null) => void;
  onCancel: () => void;
}

/** A group of fields with its heading, as the side panel shows them: where it starts, who does it, the phases. */
function Group({ title, help, children }: { title: Key; help?: Key; children: ReactNode }) {
  const t = useT();
  return (
    <section style={{ marginBottom: 8 }}>
      <Typography.Text type="secondary" style={{ display: "block", fontSize: 12 }}>{t(title)}</Typography.Text>
      {help && <Typography.Text type="secondary" style={{ display: "block", fontSize: 12, marginBottom: 8 }}>{t(help)}</Typography.Text>}
      <div style={{ marginTop: 8 }}>{children}</div>
    </section>
  );
}

export default function ProjectForm({ initial, agent: initialAgent, nameEditable, groups, busy, submitLabel, onSubmit, onCancel }: Props) {
  const t = useT();
  const [project, setProject] = useState<ProjectFields>(initial);
  const [agent, setAgent] = useState<AgentType>(initialAgent ?? "claude-code");
  const [group, setGroup] = useState<string | null>(groups?.[0] ?? null);
  const set = (change: Partial<ProjectFields>) => setProject((current) => ({ ...current, ...change }));
  const phase = (which: "plan" | "execute", change: Partial<PhaseChoice>) => {
    const next = { model: project[which]?.model ?? null, effort: project[which]?.effort ?? null, ...change };
    set({ [which]: next.model === null && next.effort === null ? null : next });
  };
  // The server drops the old agent's model and effort on a switch (ADR 0026); the form shows it before the save does.
  const switchAgent = (next: AgentType) => {
    setAgent(next);
    set({ model: null, effort: null });
  };
  const submit = () => onSubmit({
    ...project, name: project.name.trim(), baseBranch: project.baseBranch.trim(), alias: project.alias?.trim() || null,
    ...(initialAgent !== undefined && agent !== initialAgent ? { agent } : {}),
  }, group);
  const agentEfforts = effortsFor(t, agent);

  return (
    <Form layout="vertical" onFinish={submit}>
      <Group title="projects.where">
        {nameEditable && (
          <Form.Item label={t("projects.name")} htmlFor="project-name">
            <Input id="project-name" value={project.name} onChange={(e) => set({ name: e.target.value })} />
          </Form.Item>
        )}
        {groups && groups.length > 1 && (
          <Form.Item label={t("projects.group")}>
            <Select aria-label={t("projects.group")} value={group} options={groups.map((name) => ({ value: name, label: name }))}
                    onChange={setGroup} />
          </Form.Item>
        )}
        <Form.Item label={t("projects.baseBranch")} htmlFor="project-base" extra={t("projects.baseBranchHelp")}>
          <Input id="project-base" className="mono" value={project.baseBranch} onChange={(e) => set({ baseBranch: e.target.value })} />
        </Form.Item>
        <Form.Item label={t("projects.alias")} htmlFor="project-alias" extra={t("projects.aliasHelp")}>
          <Input id="project-alias" value={project.alias ?? ""} onChange={(e) => set({ alias: e.target.value })} />
        </Form.Item>
      </Group>
      <Group title="projects.who">
        {initialAgent !== undefined && (
          <Form.Item label={t("projects.agent")} extra={t("projects.agentHelp")}>
            <Select aria-label={t("projects.agent")} value={agent} options={AGENTS} onChange={switchAgent} />
          </Form.Item>
        )}
        {agent === "claude-code" ? (
          <Form.Item label={t("projects.model")}>
            <Select aria-label={t("projects.model")} value={project.model} options={withCurrent(models(t), project.model)}
                    onChange={(model) => set({ model })} />
          </Form.Item>
        ) : (
          // Codex and Gemini CLI name their models their own way; a list here would soon be out of date.
          <Form.Item label={t("projects.model")} htmlFor="project-model" extra={t("projects.modelHelp", { agent: agentLabel(agent) })}>
            <Input id="project-model" className="mono" value={project.model ?? ""} placeholder={agentDefault(t, agent)}
                   onChange={(e) => set({ model: e.target.value.trim() || null })} />
          </Form.Item>
        )}
        {agentEfforts.length > 0 && (
          <Form.Item label={t("projects.effort")} extra={t("projects.effortHelp")}>
            <Select aria-label={t("projects.effort")} value={project.effort} options={agentEfforts} onChange={(effort) => set({ effort })} />
          </Form.Item>
        )}
      </Group>
      <Group title="projects.phases" help="projects.phasesHelp">
        <Form.Item label={t("projects.planModel")}>
          <Select aria-label={t("projects.planModel")} value={project.plan?.model ?? null}
                  options={withCurrent(phaseModels(t), project.plan?.model ?? null)} onChange={(model) => phase("plan", { model })} />
        </Form.Item>
        <Form.Item label={t("projects.planEffort")}>
          <Select aria-label={t("projects.planEffort")} value={project.plan?.effort ?? null} options={phaseEfforts(t)}
                  onChange={(effort) => phase("plan", { effort })} />
        </Form.Item>
        <Form.Item label={t("projects.executeModel")}>
          <Select aria-label={t("projects.executeModel")} value={project.execute?.model ?? null}
                  options={withCurrent(phaseModels(t), project.execute?.model ?? null)} onChange={(model) => phase("execute", { model })} />
        </Form.Item>
        <Form.Item label={t("projects.executeEffort")}>
          <Select aria-label={t("projects.executeEffort")} value={project.execute?.effort ?? null} options={phaseEfforts(t)}
                  onChange={(effort) => phase("execute", { effort })} />
        </Form.Item>
      </Group>
      <Space>
        <Button type="primary" htmlType="submit" loading={busy} disabled={!project.name.trim() || !project.baseBranch.trim()}>
          {submitLabel}
        </Button>
        <Button onClick={onCancel}>{t("common.cancel")}</Button>
      </Space>
    </Form>
  );
}
