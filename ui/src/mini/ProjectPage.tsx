import { Result, theme } from "antd";
import { useState } from "react";
import { removeProject, type ManagedProject, type Me, type PhaseChoice, type ProjectSummary } from "../api";
import { EFFORTS, MODELS } from "../options";
import { MiniManaged, useMiniConfig, useProjects } from "./data";
import { Header, Row, Section } from "./List";
import { fieldPath, PROJECTS_PATH, type Field } from "./paths";
import TasksPage from "./TasksPage";

const label = (options: { value: string | null; label: string }[], value: string | null) =>
  options.find((option) => option.value === value)?.label ?? value ?? "";

/** A phase's own model and effort, or what it falls back to when it sets neither. */
function phaseLabel(phase: PhaseChoice | null) {
  if (!phase) return "Дээрхтэй адил";
  return [phase.model && label(MODELS, phase.model), phase.effort && label(EFFORTS, phase.effort)].filter(Boolean).join(" · ");
}

function ProjectHeader({ project }: { project: ProjectSummary }) {
  const meta = [project.alias && `@${project.alias}`, `${project.baseBranch} салбараас эхэлнэ`].filter(Boolean).join(" · ");
  return <Header name={project.name} title={project.name} subtitle={meta} />;
}

/** The project's own tasks, polled as on the task pages, on the raised surface its rows sit on. */
function ProjectTasks({ name, scope }: { name: string; scope: "me" | "group" }) {
  const { token } = theme.useToken();
  return (
    <Section title="Даалгавар">
      <div style={{ padding: "4px 16px 8px", background: token.colorBgContainer }}>
        <TasksPage scope={scope} project={name} heading={false} />
      </div>
    </Section>
  );
}

function NotFound({ name }: { name: string }) {
  return <Result status="404" title="Төсөл олдсонгүй" subTitle={`"${name}" гэсэн төсөл танд алга.`} />;
}

/** A member sees the project and their own tasks in it; its settings are an admin's. */
function MemberProject({ name }: { name: string }) {
  const { projects, error } = useProjects(false);
  if (error) return <Result status="warning" title="Төслийг уншиж чадсангүй" subTitle={error.message} />;
  if (!projects) return null;
  const project = projects.find((candidate) => candidate.name === name);
  if (!project) return <NotFound name={name} />;
  return (
    <>
      <ProjectHeader project={project} />
      <ProjectTasks name={name} scope="me" />
    </>
  );
}

/** Removing asks once more on the page itself: Telegram's webview shows no confirm() dialog. */
function RemoveRows({ onRemove, busy }: { onRemove: () => void; busy: boolean }) {
  const [asking, setAsking] = useState(false);
  if (!asking) return <Row title="Төсөл хасах" danger onClick={() => setAsking(true)} />;
  return (
    <>
      <Row title="Dispatch энэ төсөлд даалгавар авахаа болино. Clone нь хэвээр үлдэнэ." />
      <Row title={busy ? "Хасаж байна…" : "Тийм, хасах"} danger onClick={busy ? undefined : onRemove} />
      <Row title="Болих" onClick={() => setAsking(false)} />
    </>
  );
}

function Settings({ project, navigate }: { project: ManagedProject; navigate: (path: string) => void }) {
  const open = (field: Field) => () => navigate(fieldPath(project.name, field));
  return (
    <Section title="Тохиргоо">
      <Row title="Эхлэх салбар" value={project.baseBranch} onClick={open("baseBranch")} />
      <Row title="Товч нэр" value={project.alias ?? "—"} onClick={open("alias")} />
      <Row title="Model" value={label(MODELS, project.model)} onClick={open("model")} />
      <Row title="Effort" value={label(EFFORTS, project.effort)} onClick={open("effort")} />
      <Row title="Төлөвлөх" value={phaseLabel(project.plan)} onClick={open("plan")} />
      <Row title="Хэрэгжүүлэх" value={phaseLabel(project.execute)} onClick={open("execute")} />
    </Section>
  );
}

/** An admin also changes the project here, a row per setting, and may remove it. */
function AdminProject({ name, navigate }: { name: string; navigate: (path: string) => void }) {
  const { config, loadError, reload, save, saving, saveError } = useMiniConfig();
  return (
    <MiniManaged config={config} loadError={loadError} saveError={saveError} reload={reload}>
      {(current) => {
        const project = current.projects.find((candidate) => candidate.name === name);
        if (!project) return <NotFound name={name} />;
        return (
          <>
            <ProjectHeader project={project} />
            <ProjectTasks name={name} scope="group" />
            <Settings project={project} navigate={navigate} />
            <Section>
              <RemoveRows busy={saving} onRemove={async () => {
                if (await save((version) => removeProject(version, name))) navigate(PROJECTS_PATH);
              }} />
            </Section>
          </>
        );
      }}
    </MiniManaged>
  );
}

export default function ProjectPage({ me, name, navigate }: { me: Me; name: string; navigate: (path: string) => void }) {
  return me.admin ? <AdminProject name={name} navigate={navigate} /> : <MemberProject name={name} />;
}
