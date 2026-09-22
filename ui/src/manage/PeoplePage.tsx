import { Alert, Button, Card, Input, Popconfirm, Space, Table, Tag } from "antd";
import { useState } from "react";
import { removeMember, renameMember, setAdmin, type MemberView } from "../api";
import ManagedPage from "./ManagedPage";
import { useManagedConfig } from "./useManagedConfig";

export default function PeoplePage() {
  const { config, loadError, reload, save, saving, saveError, saved } = useManagedConfig();
  const [renaming, setRenaming] = useState<{ id: number; name: string } | null>(null);

  const rename = async () => {
    if (!renaming || !renaming.name.trim()) return;
    if (await save((version) => renameMember(version, renaming.id, renaming.name.trim()))) setRenaming(null);
  };

  return (
    <ManagedPage title="the people" config={config} loadError={loadError} saveError={saveError} saved={saved} reload={reload}>
      {(current) => (
        <>
          <Alert type="info" showIcon
                 message="New people join from Telegram: they write to the bot, and an admin approves them there." />
          {current.personal && <Alert type="info" showIcon message="A personal bot has one member, you, and no admins." />}
          {current.groups.map((group) => (
            <Card key={group.name} title={`Group ${group.name}`}>
              <Table size="small" pagination={false} rowKey="id" dataSource={group.members}
                     columns={[
                       { title: "Name", key: "name", render: (_: unknown, member: MemberView) => renaming?.id === member.id ? (
                           <Space.Compact>
                             <Input aria-label="New name" value={renaming.name} onPressEnter={() => void rename()}
                                    onChange={(e) => setRenaming({ id: member.id, name: e.target.value })} />
                             <Button type="primary" loading={saving} onClick={() => void rename()}>Save</Button>
                             <Button onClick={() => setRenaming(null)}>Cancel</Button>
                           </Space.Compact>
                         ) : <Space>{member.name}{member.admin && <Tag color="blue">admin</Tag>}</Space> },
                       { title: "Telegram id", dataIndex: "id" },
                       { title: "", key: "actions", render: (_: unknown, member: MemberView) => (
                           <Space wrap>
                             <Button size="small" aria-label={`Rename ${member.name}`}
                                     onClick={() => setRenaming({ id: member.id, name: member.name })}>Rename</Button>
                             {!current.personal && (
                               <Button size="small" aria-label={member.admin ? `Remove ${member.name} as admin` : `Make ${member.name} admin`}
                                       onClick={() => void save((version) => setAdmin(version, member.id, !member.admin))}>
                                 {member.admin ? "Remove admin" : "Make admin"}
                               </Button>
                             )}
                             <Popconfirm title={`Remove ${member.name} from ${group.name}?`} okText="Remove"
                                         onConfirm={() => void save((version) => removeMember(version, group.name, member.id))}>
                               <Button size="small" danger aria-label={`Remove ${member.name}`}>Remove</Button>
                             </Popconfirm>
                           </Space>) },
                     ]} />
            </Card>
          ))}
        </>
      )}
    </ManagedPage>
  );
}
