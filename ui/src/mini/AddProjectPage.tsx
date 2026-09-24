import { Typography } from "antd";
import { addProject } from "../api";
import AddProject from "../manage/AddProject";
import { MiniManaged, useMiniConfig } from "./data";
import { projectPath, PROJECTS_PATH } from "./paths";

/** "Төсөл нэмэх" on the home screen: the same steps as the Projects page, then on to the new project's own page. */
export default function AddProjectPage({ navigate }: { navigate: (path: string) => void }) {
  const { config, loadError, reload, save, saving, saveError } = useMiniConfig();
  return (
    <MiniManaged config={config} loadError={loadError} saveError={saveError} reload={reload}>
      {(current) => (
        <>
          <Typography.Title level={4} style={{ margin: "16px 4px 12px" }}>Төсөл нэмэх</Typography.Title>
          <AddProject groups={current.groups.map((group) => group.name)} busy={saving} onCancel={() => navigate(PROJECTS_PATH)}
                      onAdd={async (folder, group, fields) => {
                        const added = await save((version) => addProject(version, folder, group, fields));
                        if (added) navigate(projectPath(fields.name));
                        return added;
                      }} />
        </>
      )}
    </MiniManaged>
  );
}
