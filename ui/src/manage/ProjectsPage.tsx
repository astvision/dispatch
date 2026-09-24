import { Button, Card, Popconfirm, Space, Table, Typography } from "antd";
import { useState } from "react";
import { addProject, editProject, removeProject, type ManagedProject, type PhaseChoice } from "../api";
import AddProject from "./AddProject";
import ManagedPage from "./ManagedPage";
import ProjectForm, { fieldsOf } from "./ProjectForm";
import { useManagedConfig } from "./useManagedConfig";

const phaseText = (phase: PhaseChoice | null) => (phase ? [phase.model, phase.effort].filter(Boolean).join(", ") : "");

export default function ProjectsPage() {
  const { config, loadError, reload, save, saving, saveError, saved } = useManagedConfig();
  const [editing, setEditing] = useState<string | null>(null);
  const [adding, setAdding] = useState(false);

  return (
    <ManagedPage title="the projects" config={config} loadError={loadError} saveError={saveError} saved={saved} reload={reload}>
      {(current) => {
        const edited = current.projects.find((project) => project.name === editing);
        return (
          <>
            <Card title="Projects" extra={!adding && <Button onClick={() => setAdding(true)}>Add a project</Button>}>
              <Table size="small" pagination={false} rowKey="name" dataSource={current.projects}
                     columns={[
                       { title: "Project", dataIndex: "name" },
                       { title: "Alias", dataIndex: "alias" },
                       { title: "Group", dataIndex: "group" },
                       { title: "Folder", dataIndex: "path", render: (path: string | null) => path && <Typography.Text code>{path}</Typography.Text> },
                       { title: "Base", dataIndex: "baseBranch" },
                       { title: "Model", dataIndex: "model", render: (model: string | null) => model ?? "default" },
                       { title: "Effort", dataIndex: "effort", render: (effort: string | null) => effort ?? "default" },
                       { title: "Planning", dataIndex: "plan", render: phaseText },
                       { title: "Execution", dataIndex: "execute", render: phaseText },
                       { title: "", key: "actions", render: (_: unknown, project: ManagedProject) => (
                           <Space>
                             <Button size="small" aria-label={`Edit ${project.name}`} onClick={() => setEditing(project.name)}>Edit</Button>
                             <Popconfirm title={`Remove ${project.name}?`} description="Dispatch stops taking tasks for it; the clone stays."
                                         okText="Remove" onConfirm={() => void save((version) => removeProject(version, project.name))}>
                               <Button size="small" danger aria-label={`Remove ${project.name}`}>Remove</Button>
                             </Popconfirm>
                           </Space>) },
                     ]} />
            </Card>
            {edited && (
              <Card title={`Edit ${edited.name}`}>
                <ProjectForm key={`${edited.name}-${current.version}`} initial={fieldsOf(edited)} nameEditable={false} groups={null}
                             busy={saving} submitLabel="Save" onCancel={() => setEditing(null)}
                             onSubmit={async (fields) => {
                               if (await save((version) => editProject(version, fields))) setEditing(null);
                             }} />
              </Card>
            )}
            {adding && (
              <AddProject groups={current.groups.map((group) => group.name)} busy={saving} onCancel={() => setAdding(false)}
                          onAdd={async (folder, group, fields) => {
                            const added = await save((version) => addProject(version, folder, group, fields));
                            if (added) setAdding(false);
                            return added;
                          }} />
            )}
          </>
        );
      }}
    </ManagedPage>
  );
}
