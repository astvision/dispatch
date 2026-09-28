import { Alert, Button, Card, Input, InputNumber, List, QRCode, Space, Spin, Typography } from "antd";
import { useCallback, useState } from "react";
import { answerPerson, nextGroup, nextPerson, stopService, type SetupState } from "../api";
import { useT } from "../i18n/i18n";
import { useAction } from "../useAction";
import type { Draft } from "./SetupPage";
import { useLongPoll } from "./useLongPoll";

interface Props {
  state: SetupState;
  draft: Draft;
  update: (change: Partial<Draft>) => void;
  refresh: () => Promise<void>;
  next: () => void;
  back: () => void;
}

export default function PeopleStep({ state, draft, update, refresh, next, back }: Props) {
  const t = useT();
  const [phase, setPhase] = useState<"people" | "group" | "done">("people");
  const link = `https://t.me/${state.bot?.username ?? ""}`;
  const hintAfterMs = state.hintAfterSeconds * 1000;
  const askPerson = useCallback((signal: AbortSignal) => nextPerson(signal).then((r) => r.candidate), []);
  const askGroup = useCallback((signal: AbortSignal) => nextGroup(signal).then((r) => r.group), []);
  const you = state.members[0];
  // A personal bot has one member: once you're confirmed, stop asking Telegram for a next candidate, otherwise
  // whoever messages the bot next shows up as someone to add, and answering "yes" would try to add a second member.
  const polling = phase === "people" && (!you || state.team);
  const person = useLongPoll(polling, askPerson, hintAfterMs);
  const group = useLongPoll(phase === "group", askGroup, hintAfterMs);
  const answering = useAction();
  const stopping = useAction();
  const firstName = you?.name.split(/\s+/)[0]?.toLowerCase() ?? "";
  const groupTitle = group.found?.title ?? state.group?.title;
  const teamName = draft.teamName || (groupTitle ?? (firstName ? `${firstName}-team` : ""));
  const [publicUrl, setPublicUrl] = useState(draft.workers?.publicUrl ?? "");
  const [port, setPort] = useState(draft.workers?.port ?? 7880);
  // A group chat is what makes this a team whose members run their own tasks (ADR 0021); without one, nothing here.
  const needsWorkers = state.team && !!groupTitle;
  const urlOk = /^https:\/\/\S+$/.test(publicUrl.trim()) || /^http:\/\/127\.0\.0\.1(:\d+)?$/.test(publicUrl.trim());

  const answer = async (accept: boolean) => {
    if (!person.found) return;
    const id = person.found.id;
    // refresh() runs inside the same call as answerPerson() (not chained after run() resolves) so it fires on
    // the same tick as the answer, not one turn later.
    const result = await answering.run(async () => {
      await answerPerson(id, accept);
      await refresh();
      return true; // useAction.run resolves to the callback's value; awaiting refresh() alone would leave it undefined
    });
    if (result === undefined) return;
    person.reset();
  };

  const stopAndRetry = async () => {
    if ((await stopping.run(() => stopService())) === undefined) return;
    person.retry();
    group.retry();
  };

  const conflict = [person.error, group.error].find((e) => e?.code === "conflict");
  const otherError = [person.error, group.error, answering.error].find((e) => e && e.code !== "conflict");
  const question = person.found && t(you ? "setup.addToTeam" : "setup.isThisYou", { name: person.found.name });

  return (
    <Space orientation="vertical" size="middle" style={{ width: "100%" }}>
      <Space align="start" size="large" wrap>
        <QRCode type="svg" value={link} size={140} />
        <Space orientation="vertical">
          <Typography.Paragraph>
            {t("setup.openAndStart")} <Typography.Link href={link} target="_blank" rel="noreferrer">{link.replace("https://", "")}</Typography.Link>
          </Typography.Paragraph>
          {state.team && <Typography.Paragraph type="secondary">{t("setup.teammatesToo")}</Typography.Paragraph>}
        </Space>
      </Space>

      {state.members.length > 0 && (
        <List size="small" header={t(state.team ? "setup.theTeam" : "setup.you")} dataSource={state.members}
              renderItem={(m, i) => <List.Item>{t(state.team && i === 0 ? "setup.memberAdmin" : "setup.member", { name: m.name, id: m.id })}</List.Item>} />
      )}

      {polling && !person.found && !person.error && (
        <Spin description={t(you ? "setup.waitingTeammate" : "setup.waitingYou")}><div style={{ height: 48 }} /></Spin>
      )}
      {polling && person.quiet && !person.found && (
        <Alert type="warning" showIcon message={t("setup.nothingYet")}
               description={<ul style={{ margin: 0, paddingLeft: 20 }}>{state.hints.map((h) => <li key={h}>{h}</li>)}</ul>} />
      )}
      {question && (
        <Card size="small" aria-live="polite">
          <Space orientation="vertical">
            <Typography.Text strong>{question}</Typography.Text>
            <Space>
              <Button type="primary" loading={answering.busy} onClick={() => void answer(true)}>{t(you ? "setup.add" : "setup.thatsMe")}</Button>
              <Button disabled={answering.busy} onClick={() => void answer(false)}>{t(you ? "setup.skip" : "setup.notMe")}</Button>
            </Space>
          </Space>
        </Card>
      )}

      {conflict && (
        <Alert type="error" showIcon message={conflict.message}
               action={<Button danger loading={stopping.busy} onClick={() => void stopAndRetry()}>{t("setup.stopService")}</Button>} />
      )}
      {stopping.error && <Alert type="error" showIcon message={stopping.error.message} />}
      {otherError && (
        <Alert type="error" showIcon message={otherError.message}
               action={
                 <Button
                   onClick={() => {
                     // A failed answer (e.g. "that person is no longer waiting") leaves a stale candidate behind;
                     // dropping it, not just clearing the error, is what lets polling pick up the next one.
                     answering.clear();
                     person.reset();
                     person.retry();
                     group.retry();
                   }}
                 >
                   {t("common.tryAgain")}
                 </Button>
               } />
      )}

      {state.team && you && phase === "people" && (
        <Button onClick={() => setPhase("group")}>{t("setup.doneAdding")}</Button>
      )}
      {state.team && phase === "group" && (
        <Space orientation="vertical" style={{ width: "100%" }}>
          <Typography.Paragraph>{t("setup.addToGroup", { bot: state.bot?.username ?? "" })}</Typography.Paragraph>
          {groupTitle ? <Alert type="success" showIcon message={t("setup.groupFound", { title: groupTitle })} />
            : <Spin description={t("setup.waitingGroup")}><div style={{ height: 48 }} /></Spin>}
          {!groupTitle && <Button onClick={() => setPhase("done")}>{t("setup.noGroup")}</Button>}
        </Space>
      )}
      {needsWorkers && (
        <Space orientation="vertical" style={{ width: "100%" }}>
          <Typography.Paragraph type="secondary">{t("setup.workersHow")}</Typography.Paragraph>
          <label>
            <Typography.Text>{t("setup.publicUrl")}</Typography.Text>
            <Input value={publicUrl} placeholder="https://team.example.com" aria-label={t("setup.publicUrl")}
                   onChange={(e) => setPublicUrl(e.target.value)} />
          </label>
          {publicUrl.trim() !== "" && !urlOk && (
            <Typography.Text type="danger">{t("setup.urlMust")}</Typography.Text>
          )}
          <label>
            <Typography.Text>{t("setup.port")}</Typography.Text>
            <InputNumber min={1} max={65535} value={port} aria-label={t("setup.port")}
                         onChange={(value) => setPort(value ?? 7880)} />
          </label>
        </Space>
      )}
      {state.team && you && (
        <label>
          <Typography.Text>{t("setup.teamName")}</Typography.Text>
          <Input value={teamName} onChange={(e) => update({ teamName: e.target.value })} aria-label={t("setup.teamName")} />
        </label>
      )}

      <Space>
        <Button onClick={back}>{t("common.back")}</Button>
        <Button type="primary" disabled={!you || (state.team && !teamName.trim()) || (needsWorkers && !urlOk)}
                onClick={() => {
                  if (state.team) {
                    update({
                      teamName: teamName.trim(),
                      ...(needsWorkers ? { workers: { publicUrl: publicUrl.trim(), port } } : {}),
                    });
                  }
                  next();
                }}>
          {t("common.next")}
        </Button>
      </Space>
    </Space>
  );
}
