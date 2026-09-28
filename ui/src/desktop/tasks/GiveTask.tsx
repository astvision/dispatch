import { Alert, Button, Flex, Input, Radio, Select } from "antd";
import { useState } from "react";
import { ApiError, giveTask, type Priority } from "../../api";
import { useT, type Key } from "../../i18n/i18n";

const PRIORITIES: Priority[] = ["URGENT", "NORMAL", "LOW"];

/**
 * Даалгавар өгөх (D-2b): a task of the desktop's admin, in one of their projects. The bot checks it again and answers a
 * refusal here only; given, the new task opens in the side panel. The button is busy until the bot answers, so a double
 * click gives one task.
 */
export default function GiveTask({ projects, onGiven }: { projects: string[]; onGiven: (taskId: number) => void }) {
  const t = useT();
  const [project, setProject] = useState<string | null>(projects.length === 1 ? projects[0] : null);
  const [text, setText] = useState("");
  const [priority, setPriority] = useState<Priority>("NORMAL");
  const [busy, setBusy] = useState(false);
  const [refusal, setRefusal] = useState<string | null>(null);

  if (projects.length === 0) return <Alert type="info" showIcon message={t("give.noProjects")} />;

  const give = async () => {
    if (busy || !project || !text.trim()) return;
    setBusy(true);
    setRefusal(null);
    try {
      onGiven((await giveTask(project, text.trim(), priority)).taskId);
    } catch (e) {
      setRefusal(e instanceof ApiError ? e.message : String(e));
    } finally {
      setBusy(false);
    }
  };

  return (
    <Flex vertical gap={12}>
      <Select aria-label={t("give.project")} value={project} placeholder={t("give.chooseProject")}
              options={projects.map((name) => ({ value: name, label: name }))} onChange={setProject} />
      <Input.TextArea aria-label={t("give.text")} autoSize={{ minRows: 4, maxRows: 12 }} value={text}
                      placeholder={t("give.textHint")} onChange={(e) => setText(e.target.value)} />
      <Radio.Group aria-label={t("give.priority")} optionType="button" value={priority} onChange={(e) => setPriority(e.target.value)}
                   options={PRIORITIES.map((value) => ({ value, label: t(`tasks.priority.${value}` as Key) }))} />
      {refusal && <Alert type="error" showIcon message={refusal} />}
      <Button type="primary" loading={busy} disabled={!project || !text.trim()} onClick={() => void give()}>
        {t("give.submit")}
      </Button>
    </Flex>
  );
}
