import { Alert, Button, Card } from "antd";
import { useState } from "react";
import { probeProject, type ProjectFields, type ProjectView } from "../api";
import { useT } from "../i18n/i18n";
import FolderBrowser from "../setup/FolderBrowser";
import { useAction } from "../useAction";
import ProjectForm from "./ProjectForm";

interface Props {
  /** The config's groups; a new project is asked for one when there is more than one. */
  groups: string[];
  busy: boolean;
  /** @returns whether it was added */
  onAdd: (folder: string, group: string | null, fields: ProjectFields) => Promise<boolean>;
  onCancel: () => void;
}

/** Adding a project: pick its clone, then name it and set it up. Shared by the Projects page and the Mini App. */
export default function AddProject({ groups, busy, onAdd, onCancel }: Props) {
  const t = useT();
  const [probe, setProbe] = useState<ProjectView | null>(null);
  const probing = useAction();

  const pick = async (folder: string) => {
    const found = await probing.run(() => probeProject(folder));
    if (found) setProbe(found);
  };

  if (!probe) {
    return (
      <Card title={t("projects.chooseClone")} extra={<Button onClick={onCancel}>{t("common.cancel")}</Button>}>
        <FolderBrowser onPick={(folder) => void pick(folder)} />
        {probing.error && <Alert type="error" showIcon message={probing.error.message} style={{ marginTop: 12 }} />}
      </Card>
    );
  }
  return (
    <Card title={probe.folder}>
      {probe.originHadCredentials && <Alert type="warning" showIcon style={{ marginBottom: 12 }}
                                            message={t("projects.credentials")} />}
      <ProjectForm initial={{ name: probe.name, baseBranch: probe.baseBranch ?? "", alias: null, model: null, effort: null,
                              plan: null, execute: null }}
                   nameEditable groups={groups} busy={busy} submitLabel={t("projects.addProject")}
                   onCancel={() => { setProbe(null); onCancel(); }}
                   onSubmit={(fields, group) => void onAdd(probe.folder, group, fields)} />
    </Card>
  );
}
