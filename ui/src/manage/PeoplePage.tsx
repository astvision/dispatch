import { Alert, Button, Flex, Input, Popconfirm, Space, Switch, Table, Tag, Typography } from "antd";
import { useState } from "react";
import { removeMember, renameMember, setAdmin, unlinkGroup, type GroupView, type MemberView } from "../api";
import { useT } from "../i18n/i18n";
import ManagedPage from "./ManagedPage";
import { useManagedConfig } from "./useManagedConfig";

/** A group's own line: its name, its projects, its Telegram chat and the way to unlink it. */
function GroupHead({ group, onUnlink }: { group: GroupView; onUnlink: () => void }) {
  const t = useT();
  return (
    <Flex wrap align="center" gap={10} style={{ marginBottom: 8 }}>
      <Typography.Title level={5} style={{ margin: 0 }}>{group.name}</Typography.Title>
      {group.projects.map((project) => <Tag key={project}>{project}</Tag>)}
      {group.chatId === null ? <Typography.Text type="secondary">{t("people.noChat")}</Typography.Text> : (
        <>
          <Typography.Text type="secondary">{t("people.chat")}</Typography.Text>
          <Typography.Text code>{group.chatId}</Typography.Text>
          <Popconfirm title={t("people.unlinkAsk", { group: group.name })} description={t("people.unlinkHint")}
                      okText={t("people.unlink")} cancelText={t("common.cancel")} onConfirm={onUnlink}>
            <Button size="small" style={{ marginLeft: "auto" }}>{t("people.unlinkChat")}</Button>
          </Popconfirm>
        </>
      )}
    </Flex>
  );
}

/** One section per group, as the config has them: its chat, its projects, its members with the admin switch. */
export default function PeoplePage() {
  const t = useT();
  const { config, loadError, reload, save, saving, saveError } = useManagedConfig();
  const [renaming, setRenaming] = useState<{ id: number; name: string } | null>(null);

  const rename = async () => {
    if (!renaming || !renaming.name.trim()) return;
    if (await save((version) => renameMember(version, renaming.id, renaming.name.trim()))) setRenaming(null);
  };

  return (
    <ManagedPage cannotShow={t("people.cannotShow")} config={config} loadError={loadError} saveError={saveError} reload={reload}>
      {(current) => (
        <>
          <Typography.Title level={4} style={{ margin: 0 }}>{t("people.title")}</Typography.Title>
          <Alert type="info" showIcon message={t("people.howToJoin")} />
          {current.personal && <Alert type="info" showIcon message={t("people.personal")} />}
          {current.groups.map((group) => (
            <section key={group.name}>
              <GroupHead group={group} onUnlink={() => void save((version) => unlinkGroup(version, group.name))} />
              <Table size="small" pagination={false} rowKey="id" dataSource={group.members} scroll={{ x: "max-content" }}
                     columns={[
                       { title: t("people.name"), key: "name", render: (_: unknown, member: MemberView) => renaming?.id === member.id ? (
                           <Space.Compact>
                             <Input aria-label={t("people.newName")} value={renaming.name} onPressEnter={() => void rename()}
                                    onChange={(e) => setRenaming({ id: member.id, name: e.target.value })} />
                             <Button type="primary" loading={saving} onClick={() => void rename()}>{t("common.save")}</Button>
                             <Button onClick={() => setRenaming(null)}>{t("common.cancel")}</Button>
                           </Space.Compact>
                         ) : member.name },
                       { title: t("people.telegramId"), dataIndex: "id", render: (id: number) => <Typography.Text code>{id}</Typography.Text> },
                       // A personal bot has one member, the owner, and no admins to switch.
                       ...(current.personal ? [] : [{ title: t("people.admin"), key: "admin", render: (_: unknown, member: MemberView) => (
                           <Switch size="small" checked={member.admin} disabled={saving} aria-label={t("people.adminOf", { name: member.name })}
                                   onChange={(admin) => void save((version) => setAdmin(version, member.id, admin))} />) }]),
                       { key: "actions", render: (_: unknown, member: MemberView) => (
                           <Space wrap>
                             <Button size="small" type="text" aria-label={t("people.renameNamed", { name: member.name })}
                                     onClick={() => setRenaming({ id: member.id, name: member.name })}>{t("people.rename")}</Button>
                             <Popconfirm title={t("people.removeAsk", { name: member.name, group: group.name })} okText={t("common.remove")}
                                         cancelText={t("common.cancel")}
                                         onConfirm={() => void save((version) => removeMember(version, group.name, member.id))}>
                               <Button size="small" type="text" danger aria-label={t("common.removeNamed", { name: member.name })}>
                                 {t("common.remove")}
                               </Button>
                             </Popconfirm>
                           </Space>) },
                     ]} />
            </section>
          ))}
        </>
      )}
    </ManagedPage>
  );
}
