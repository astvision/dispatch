import { Alert, Button, Card, Popconfirm, Space, Table, Typography } from "antd";
import { useState } from "react";
import { addProject, editProject, probeProject, removeProject, type ManagedProject, type PhaseChoice, type ProjectView } from "../api";
import FolderBrowser from "../setup/FolderBrowser";
import { useAction } from "../useAction";
import ManagedPage from "./ManagedPage";
import ProjectForm from "./ProjectForm";
import { useManagedConfig } from "./useManagedConfig";

const phaseText = (phase: PhaseChoice | null) => (phase ? [phase.model, phase.effort].filter(Boolean).join(", ") : "");

function fieldsOf(project: ManagedProject) {
  return { name: project.name, baseBranch: project.baseBranch, alias: project.alias, model: project.model, effort: project.effort,
    plan: project.plan, execute: project.execute };
}

export default function ProjectsPage() {
  const { config, loadError, reload, save, saving, saveError, saved } = useManagedConfig();
  const [editing, setEditing] = useState<string | null>(null);
  const [adding, setAdding] = useState(false);
  const [probe, setProbe] = useState<ProjectView | null>(null);
  const probing = useAction();

  const pick = async (folder: string) => {
    const found = await probing.run(() => probeProject(folder));
    if (found) setProbe(found);
  };
  const stopAdding = () => {
    setAdding(false);
    setProbe(null);
  };

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
            {adding && !probe && (
              <Card title="Choose a clone" extra={<Button onClick={stopAdding}>Cancel</Button>}>
                <FolderBrowser onPick={(folder) => void pick(folder)} />
                {probing.error && <Alert type="error" showIcon message={probing.error.message} style={{ marginTop: 12 }} />}
              </Card>
            )}
            {adding && probe && (
              <Card title={probe.folder}>
                {probe.originHadCredentials && <Alert type="warning" showIcon style={{ marginBottom: 12 }}
                                                      message="origin's URL holds credentials; it is not copied into the config" />}
                <ProjectForm initial={{ name: probe.name, baseBranch: probe.baseBranch ?? "", alias: null, model: null, effort: null,
                                        plan: null, execute: null }}
                             nameEditable groups={current.groups.map((group) => group.name)} busy={saving} submitLabel="Add project"
                             onCancel={stopAdding}
                             onSubmit={async (fields, group) => {
                               if (await save((version) => addProject(version, probe.folder, group, fields))) stopAdding();
                             }} />
              </Card>
            )}
          </>
        );
      }}
    </ManagedPage>
  );
}
