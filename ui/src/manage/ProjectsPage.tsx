import { Button, Drawer, Flex, Popconfirm, Space, Table, Tag, Typography } from "antd";
import { useEffect, useState } from "react";
import { addProject, editProject, removeProject, type AgentType, type ManagedProject } from "../api";
import { useT } from "../i18n/i18n";
import { agentLabel } from "../options";
import AddProject from "./AddProject";
import ManagedPage from "./ManagedPage";
import ProjectForm, { fieldsOf } from "./ProjectForm";
import { useManagedConfig } from "./useManagedConfig";

const NARROW = "(max-width: 640px)";

/** Below 640 px the side panel takes the whole width. */
function useNarrow() {
  const [narrow, setNarrow] = useState(() => window.matchMedia(NARROW).matches);
  useEffect(() => {
    const list = window.matchMedia(NARROW);
    const change = () => setNarrow(list.matches);
    list.addEventListener("change", change);
    return () => list.removeEventListener("change", change);
  }, []);
  return narrow;
}

type Panel = { kind: "edit"; name: string } | { kind: "add" } | null;

/** The projects as a table; a row, or Edit, opens it in the side panel, and "Add a project" opens the panel at the folder browser. */
export default function ProjectsPage() {
  const t = useT();
  const narrow = useNarrow();
  const { config, loadError, reload, save, saving, saveError } = useManagedConfig();
  const [panel, setPanel] = useState<Panel>(null);
  const close = () => setPanel(null);

  return (
    <ManagedPage cannotShow={t("projects.cannotShow")} config={config} loadError={loadError} saveError={saveError} reload={reload}>
      {(current) => {
        const edited = panel?.kind === "edit" ? current.projects.find((project) => project.name === panel.name) : undefined;
        return (
          <>
            <div>
              <Flex justify="space-between" align="center" gap={12}>
                <Typography.Title level={4} style={{ margin: 0 }}>{t("projects.title")}</Typography.Title>
                <Button type="primary" onClick={() => setPanel({ kind: "add" })}>{t("projects.addAProject")}</Button>
              </Flex>
              <Typography.Text type="secondary">{t("projects.sub")}</Typography.Text>
            </div>
            <Table size="small" pagination={false} rowKey="name" dataSource={current.projects} scroll={{ x: "max-content" }}
                   onRow={(project) => ({ onClick: () => setPanel({ kind: "edit", name: project.name }), style: { cursor: "pointer" } })}
                   columns={[
                     { title: t("projects.project"), dataIndex: "name", render: (name: string, project: ManagedProject) => (
                         <Space size={6}>{name}{project.alias && <Tag>{project.alias}</Tag>}</Space>) },
                     { title: t("projects.group"), dataIndex: "group" },
                     { title: t("projects.folder"), dataIndex: "path",
                       render: (path: string | null) => path && <Typography.Text code>{path}</Typography.Text> },
                     { title: t("projects.branch"), dataIndex: "baseBranch", render: (branch: string) => <Typography.Text code>{branch}</Typography.Text> },
                     { title: t("projects.agent"), dataIndex: "agent",
                       render: (agent: AgentType) => <Typography.Text type="secondary">{agentLabel(agent)}</Typography.Text> },
                     { key: "actions", render: (_: unknown, project: ManagedProject) => (
                         // The row opens the panel; its buttons (and the question Remove asks, in its popup) must not.
                         <Space onClick={(event) => event.stopPropagation()}>
                           <Button size="small" aria-label={t("projects.editNamed", { name: project.name })}
                                   onClick={() => setPanel({ kind: "edit", name: project.name })}>{t("projects.edit")}</Button>
                           <Popconfirm title={t("projects.removeAsk", { name: project.name })} description={t("projects.removeHint")}
                                       okText={t("projects.remove")} cancelText={t("projects.cancel")}
                                       onConfirm={() => void save((version) => removeProject(version, project.name))}>
                             <Button size="small" danger aria-label={t("projects.removeNamed", { name: project.name })}>{t("projects.remove")}</Button>
                           </Popconfirm>
                         </Space>) },
                   ]} />
            <Drawer open={panel !== null} onClose={close} placement="right" size={narrow ? "100%" : 420} destroyOnHidden
                    title={edited
                      ? <div><div>{edited.name}</div><Typography.Text type="secondary" style={{ fontSize: 12, fontWeight: 400 }}>
                          {t("projects.ofGroup", { group: edited.group })}</Typography.Text></div>
                      : t("projects.addTitle")}>
              {edited && (
                <ProjectForm key={`${edited.name}-${current.version}`} initial={fieldsOf(edited)} agent={edited.agent} nameEditable={false}
                             groups={null} busy={saving} submitLabel={t("projects.save")} onCancel={close}
                             onSubmit={async (fields) => {
                               if (await save((version) => editProject(version, fields))) close();
                             }} />
              )}
              {panel?.kind === "add" && (
                <AddProject groups={current.groups.map((group) => group.name)} busy={saving} onCancel={close}
                            onAdd={async (folder, group, fields) => {
                              const added = await save((version) => addProject(version, folder, group, fields));
                              if (added) close();
                              return added;
                            }} />
              )}
            </Drawer>
          </>
        );
      }}
    </ManagedPage>
  );
}
