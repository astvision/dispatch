import { PlusCircleOutlined, SearchOutlined } from "@ant-design/icons";
import { Alert, Input, Spin, theme } from "antd";
import { useState } from "react";
import type { Me, ProjectSummary } from "../api";
import { useActiveCounts, useProjects } from "./data";
import { Avatar, Row, Section } from "./List";
import { ADD_PATH, projectPath } from "./paths";

/** A line under a project's name: its alias, the branch its tasks start from, and how many are under way. */
export function projectMeta(project: ProjectSummary, active?: number) {
  return [project.alias && `@${project.alias}`, project.baseBranch, active ? `${active} идэвхтэй` : null]
    .filter(Boolean).join(" · ");
}

function matches(project: ProjectSummary, query: string) {
  const wanted = query.trim().toLowerCase();
  return wanted === "" || project.name.toLowerCase().includes(wanted) || (project.alias ?? "").toLowerCase().includes(wanted);
}

/** The projects to pick from, after BotFather's "My bots": search, then a row per project, and Add for an admin. */
export default function ProjectsPage({ me, navigate }: { me: Me; navigate: (path: string) => void }) {
  const { token } = theme.useToken();
  const { projects, error } = useProjects(me.admin);
  const counts = useActiveCounts(me.admin ? "group" : "me");
  const [query, setQuery] = useState("");
  const shown = projects?.filter((project) => matches(project, query)) ?? [];

  return (
    <>
      <h1 className="mini-title">Төслүүд</h1>
      <Input id="project-search" aria-label="Төсөл хайх" placeholder="Хайх" allowClear size="large" variant="filled"
             prefix={<SearchOutlined style={{ color: token.colorTextTertiary }} />} value={query}
             onChange={(event) => setQuery(event.target.value)} />

      <Section>
        {me.admin && (
          <Row leading={<span aria-hidden="true" className="mini-row-icon"><PlusCircleOutlined /></span>}
               title={<span style={{ color: token.colorPrimary }}>Төсөл нэмэх</span>} onClick={() => navigate(ADD_PATH)} />
        )}
        {!projects && !error && <div style={{ padding: 16 }}><Spin /></div>}
        {projects && shown.map((project) => (
          <Row key={project.name} leading={<Avatar name={project.name} />} title={project.name}
               subtitle={projectMeta(project, counts[project.name])} onClick={() => navigate(projectPath(project.name))} />
        ))}
        {projects && shown.length === 0 && (
          <Row title={query.trim() ? "Ийм төсөл олдсонгүй" : "Танд төсөл алга"}
               subtitle={query.trim() ? undefined : me.admin ? "Дээрх мөрөөр анхны төслөө нэмнэ үү." : "Админаас нэмүүлнэ үү."} />
        )}
      </Section>
      {error && <Alert type="error" showIcon message={error.message} style={{ marginTop: 12 }} />}
    </>
  );
}
