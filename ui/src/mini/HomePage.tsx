import {
  AppstoreOutlined, FileTextOutlined, MessageOutlined, PlusCircleOutlined, SearchOutlined, SettingOutlined, TeamOutlined,
  UnorderedListOutlined, UserOutlined,
} from "@ant-design/icons";
import { Alert, Input, Spin, theme } from "antd";
import { useState, type ReactNode } from "react";
import type { Me, ProjectSummary } from "../api";
import { useActiveCounts, useProjects } from "./data";
import { Avatar, Header, Row, Section } from "./List";
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

/** An icon on a row, the size of a project's avatar, so the rows of every section line up. */
function Icon({ children }: { children: ReactNode }) {
  const { token } = theme.useToken();
  return (
    <span aria-hidden="true" style={{ width: 40, height: 40, flex: "none", display: "grid", placeItems: "center",
                                      fontSize: 20, color: token.colorPrimary }}>
      {children}
    </span>
  );
}

/**
 * The Mini App's first screen, after BotFather's "My bots": who this bot is, the projects to pick from, and — for an
 * admin — the rest of Dispatch's setup underneath.
 */
export default function HomePage({ me, navigate }: { me: Me; navigate: (path: string) => void }) {
  const { token } = theme.useToken();
  const { projects, error } = useProjects(me.admin);
  const counts = useActiveCounts(me.admin ? "group" : "me");
  const [query, setQuery] = useState("");
  const shown = projects?.filter((project) => matches(project, query)) ?? [];

  return (
    <>
      <Header name="Dispatch" title="Dispatch"
              subtitle={<>@{me.bot} · Telegram-аар өгсөн даалгаврыг Claude Code төлөвлөж, draft PR болгон хүргэнэ.</>} />
      <Input id="project-search" aria-label="Төсөл хайх" placeholder="Хайх" allowClear size="large" variant="filled"
             prefix={<SearchOutlined style={{ color: token.colorTextTertiary }} />} value={query}
             onChange={(event) => setQuery(event.target.value)} style={{ marginTop: 12 }} />

      <Section title="Төслүүд">
        {me.admin && (
          <Row leading={<Icon><PlusCircleOutlined /></Icon>} title={<span style={{ color: token.colorPrimary }}>Төсөл нэмэх</span>}
               onClick={() => navigate(ADD_PATH)} />
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

      <Section title="Даалгавар">
        <Row leading={<Icon><UserOutlined /></Icon>} title="Миний даалгаврууд" onClick={() => navigate("/tasks")} />
        {me.admin && (
          <Row leading={<Icon><UnorderedListOutlined /></Icon>} title="Бүх даалгавар" onClick={() => navigate("/group-tasks")} />
        )}
      </Section>

      {me.admin && (
        <Section title="Dispatch">
          <Row leading={<Icon><TeamOutlined /></Icon>} title="Хүмүүс" subtitle="Гишүүд, админууд"
               onClick={() => navigate("/people")} />
          <Row leading={<Icon><MessageOutlined /></Icon>} title="Группүүд" subtitle="Төсөл бүрийн мэдэгдлийн групп"
               onClick={() => navigate("/groups")} />
          <Row leading={<Icon><SettingOutlined /></Icon>} title="Тохиргоо" subtitle="Хугацаа, төсөв, commit зохиогч"
               onClick={() => navigate("/settings")} />
          <Row leading={<Icon><FileTextOutlined /></Icon>} title="Лог" onClick={() => navigate("/logs")} />
          <Row leading={<Icon><AppstoreOutlined /></Icon>} title="Тойм" subtitle="Хувилбар, service, шалгалт"
               onClick={() => navigate("/overview")} />
        </Section>
      )}
    </>
  );
}
