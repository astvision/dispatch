import { Button, Input, Result, Typography } from "antd";
import { useState } from "react";
import { editProject, type ManagedProject, type PhaseChoice, type ProjectFields } from "../api";
import { fieldsOf } from "../manage/ProjectForm";
import { AGENTS, agentLabel, effortsFor, MODELS, PHASE_EFFORTS, PHASE_MODELS, withCurrent } from "../options";
import { MiniManaged, useMiniConfig } from "./data";
import { Row, Section } from "./List";
import type { Field } from "./paths";

const TITLES: Record<Field, string> = {
  agent: "Агент",
  baseBranch: "Эхлэх салбар",
  alias: "Товч нэр",
  model: "Model",
  effort: "Effort",
  plan: "Төлөвлөх үе шат",
  execute: "Хэрэгжүүлэх үе шат",
};

type Save = (change: Partial<ProjectFields>) => Promise<boolean>;

/** A text setting: type it, then Save goes back to the project. */
function TextField({ id, initial, hint, required, busy, onSave }: {
  id: string;
  initial: string;
  hint: string;
  required: boolean;
  busy: boolean;
  onSave: (value: string) => void;
}) {
  const [value, setValue] = useState(initial);
  const trimmed = value.trim();
  return (
    <form onSubmit={(event) => { event.preventDefault(); onSave(trimmed); }}>
      <Input id={id} aria-label={hint} size="large" value={value} onChange={(event) => setValue(event.target.value)}
             style={{ marginTop: 16 }} />
      <Typography.Paragraph type="secondary" style={{ margin: "8px 4px 16px" }}>{hint}</Typography.Paragraph>
      <Button type="primary" htmlType="submit" size="large" block loading={busy}
              disabled={(required && trimmed === "") || trimmed === initial}>
        Хадгалах
      </Button>
    </form>
  );
}

/** A choice: tapping an option saves it at once and ticks it, as Telegram's own settings do. */
export function Choices<T extends string | null>({ title, options, value, busy, onPick }: {
  title?: string;
  options: { value: T; label: string }[];
  value: T;
  busy: boolean;
  onPick: (value: T) => void;
}) {
  return (
    <Section title={title}>
      {options.map((option) => (
        <Row key={option.value ?? "default"} title={option.label} checked={option.value === value}
             onClick={() => {
               if (!busy && option.value !== value) onPick(option.value);
             }} />
      ))}
    </Section>
  );
}

function Editor({ project, field, busy, save, back }: {
  project: ManagedProject;
  field: Field;
  busy: boolean;
  save: Save;
  back: () => void;
}) {
  const saveThenBack = async (change: Partial<ProjectFields>) => {
    if (await save(change)) back();
  };
  // A phase keeps whichever of its two choices is not being changed; with neither set it falls back to the project's.
  const phase = (which: "plan" | "execute", change: Partial<PhaseChoice>) => {
    const next = { model: project[which]?.model ?? null, effort: project[which]?.effort ?? null, ...change };
    return save({ [which]: next.model === null && next.effort === null ? null : next });
  };

  switch (field) {
    case "agent":
      // The server then drops the model and effort chosen for the old agent (ADR 0026).
      return <Choices options={AGENTS} value={project.agent} busy={busy} onPick={(agent) => void saveThenBack({ agent })} />;
    case "baseBranch":
      return <TextField id="field-base-branch" initial={project.baseBranch} required busy={busy}
                        hint="Шинэ даалгавар бүрийн worktree энэ салбараас эхэлнэ."
                        onSave={(value) => void saveThenBack({ baseBranch: value })} />;
    case "alias":
      return <TextField id="field-alias" initial={project.alias ?? ""} required={false} busy={busy}
                        hint="Даалгаварт төслийг нэрлэх богино нэр. Хоосон бол байхгүй."
                        onSave={(value) => void saveThenBack({ alias: value === "" ? null : value })} />;
    case "model":
      if (project.agent !== "claude-code") {
        // Codex and Gemini CLI name their models in their own way, and a list here would soon be out of date.
        return <TextField id="field-model" initial={project.model ?? ""} required={false} busy={busy}
                          hint={`Хоосон бол ${agentLabel(project.agent)} өөрийн үндсэн model-ийг хэрэглэнэ.`}
                          onSave={(value) => void saveThenBack({ model: value === "" ? null : value })} />;
      }
      return <Choices options={withCurrent(MODELS, project.model)} value={project.model} busy={busy}
                      onPick={(model) => void save({ model })} />;
    case "effort":
      return <Choices options={effortsFor(project.agent)} value={project.effort} busy={busy}
                      onPick={(effort) => void save({ effort })} />;
    case "plan":
    case "execute":
      return (
        <>
          <Choices title="Model" options={withCurrent(PHASE_MODELS, project[field]?.model ?? null)}
                   value={project[field]?.model ?? null} busy={busy} onPick={(model) => void phase(field, { model })} />
          <Choices title="Effort" options={PHASE_EFFORTS} value={project[field]?.effort ?? null} busy={busy}
                   onPick={(effort) => void phase(field, { effort })} />
        </>
      );
  }
}

/** One of a project's settings on a screen of its own, as BotFather edits a bot's name or description. */
export default function FieldEditPage({ name, field, back }: { name: string; field: Field; back: () => void }) {
  const { config, loadError, reload, save, saving, saveError } = useMiniConfig();
  return (
    <MiniManaged config={config} loadError={loadError} saveError={saveError} reload={reload}>
      {(current) => {
        const project = current.projects.find((candidate) => candidate.name === name);
        if (!project) return <Result status="404" title="Төсөл олдсонгүй" />;
        return (
          <>
            <Typography.Title level={4} style={{ margin: "16px 4px 0" }}>{TITLES[field]}</Typography.Title>
            <Typography.Text type="secondary" style={{ margin: "0 4px" }}>{name}</Typography.Text>
            <Editor project={project} field={field} busy={saving} back={back}
                    save={(change) => save((version) => editProject(version, { ...fieldsOf(project), ...change }))} />
          </>
        );
      }}
    </MiniManaged>
  );
}
