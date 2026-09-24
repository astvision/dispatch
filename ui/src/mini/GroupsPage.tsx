import { useState } from "react";
import { unlinkGroup } from "../api";
import { MiniManaged, useMiniConfig } from "./data";
import { Row, Section } from "./List";

/** Asks on the page before unlinking, like ProjectPage's RemoveRows: Telegram's webview shows no confirm() dialog. */
function GroupRow({ name, subtitle, busy, onUnlink }: { name: string; subtitle: string; busy: boolean; onUnlink: () => void }) {
  const [asking, setAsking] = useState(false);
  if (!asking) return <Row title={name} subtitle={subtitle} onClick={() => setAsking(true)} />;
  return (
    <>
      <Row title="Энэ группт мэдэгдэл гарахаа болино, бот группээс гарна." />
      <Row title={busy ? "Салгаж байна…" : "Тийм, салгах"} danger onClick={busy ? undefined : onUnlink} />
      <Row title="Болих" onClick={() => setAsking(false)} />
    </>
  );
}

/** The groups Telegram has linked to a project, each unlinkable on the spot; how to link a new one is Telegram's own job. */
export default function GroupsPage() {
  const { config, loadError, reload, save, saving, saveError } = useMiniConfig();
  return (
    <MiniManaged config={config} loadError={loadError} saveError={saveError} reload={reload}>
      {(current) => {
        const linked = current.groups.filter((group) => group.chatId !== null);
        return (
          <>
            <Section title="Холбосон группүүд">
              {linked.length === 0 && <Row title="Холбосон групп алга" />}
              {linked.map((group) => (
                <GroupRow key={group.name} name={group.name} subtitle={group.projects.join(", ")} busy={saving}
                          onUnlink={() => void save((version) => unlinkGroup(version, group.name))} />
              ))}
            </Section>
            <Section>
              <Row title="Групп холбохдоо ботыг группт нэмнэ (эсвэл тэнд /status@бот гэж бичнэ): бот танд хувийн чатаар аль төсөл болохыг асууна." />
            </Section>
          </>
        );
      }}
    </MiniManaged>
  );
}
