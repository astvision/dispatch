import { Button, Flex, Modal, Typography } from "antd";
import { useEffect, useState } from "react";
import { getConfig, type ConfigView } from "../api";
import { useT } from "../i18n/i18n";
import { chooseMember } from "./member";

/** The config's admins by the first name a group gives each: whom the desktop may act as on a team (D-2). */
export function adminsOf(config: ConfigView): { ref: string; name: string }[] {
  const names = new Map<number, string>();
  for (const member of config.groups.flatMap((group) => group.members)) {
    if (!names.has(member.id)) names.set(member.id, member.name);
  }
  return config.admins.map((id) => ({ ref: `telegram:${id}`, name: names.get(id) ?? String(id) }));
}

/**
 * Which admin the desktop acts as, asked when the desk cannot tell (a team with several admins, or a remembered one who
 * is no longer an admin). It cannot be dismissed: the desk answers nothing about tasks until it knows.
 */
export default function MemberChoice({ open, onChosen }: { open: boolean; onChosen: () => void }) {
  const t = useT();
  const [admins, setAdmins] = useState<{ ref: string; name: string }[] | null>(null);

  useEffect(() => {
    if (!open) return;
    let stopped = false;
    getConfig().then((config) => !stopped && setAdmins(adminsOf(config)), () => !stopped && setAdmins([]));
    return () => {
      stopped = true;
    };
  }, [open]);

  // Gone entirely once answered, rather than kept hidden: it is asked rarely and needs no closing animation. Nor is it
  // shown before the config names the admins, or when it names none: a dialog that cannot close must offer a choice.
  if (!open || !admins?.length) return null;
  return (
    <Modal open title={t("member.title")} closable={false} mask={{ closable: false }} keyboard={false} footer={null}>
      <Typography.Paragraph type="secondary">{t("member.hint")}</Typography.Paragraph>
      <Flex vertical gap={8}>
        {admins.map((admin) => (
          <Button key={admin.ref} block onClick={() => {
            chooseMember(admin.ref);
            onChosen();
          }}>{admin.name}</Button>
        ))}
      </Flex>
    </Modal>
  );
}
