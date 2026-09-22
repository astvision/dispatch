import { Alert, Button, Card, Input, List, QRCode, Space, Spin, Typography } from "antd";
import { useCallback, useState } from "react";
import { answerPerson, nextGroup, nextPerson, stopService, type SetupState } from "../api";
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
  const question = person.found && (you ? `Add ${person.found.name} to the team?` : `Is ${person.found.name} you?`);

  return (
    <Space orientation="vertical" size="middle" style={{ width: "100%" }}>
      <Space align="start" size="large" wrap>
        <QRCode type="svg" value={link} size={140} />
        <Space orientation="vertical">
          <Typography.Paragraph>
            Open <Typography.Link href={link} target="_blank" rel="noreferrer">{link.replace("https://", "")}</Typography.Link> and press Start.
          </Typography.Paragraph>
          {state.team && <Typography.Paragraph type="secondary">Teammates do the same. Anyone can also ask later: you approve them in Telegram.</Typography.Paragraph>}
        </Space>
      </Space>

      {state.members.length > 0 && (
        <List size="small" header={state.team ? "The team" : "You"} dataSource={state.members}
              renderItem={(m, i) => <List.Item>{m.name} ({m.id}){state.team && i === 0 ? ", admin" : ""}</List.Item>} />
      )}

      {polling && !person.found && !person.error && (
        <Spin description={you ? "Waiting for a teammate to press Start" : "Waiting for you to press Start"}><div style={{ height: 48 }} /></Spin>
      )}
      {polling && person.quiet && !person.found && (
        <Alert type="warning" showIcon message="Nothing has reached the bot yet. If Start was pressed:"
               description={<ul style={{ margin: 0, paddingLeft: 20 }}>{state.hints.map((h) => <li key={h}>{h}</li>)}</ul>} />
      )}
      {question && (
        <Card size="small" aria-live="polite">
          <Space orientation="vertical">
            <Typography.Text strong>{question}</Typography.Text>
            <Space>
              <Button type="primary" loading={answering.busy} onClick={() => void answer(true)}>{you ? "Add" : "That's me"}</Button>
              <Button disabled={answering.busy} onClick={() => void answer(false)}>{you ? "Skip" : "Not me"}</Button>
            </Space>
          </Space>
        </Card>
      )}

      {conflict && (
        <Alert type="error" showIcon message={conflict.message}
               action={<Button danger loading={stopping.busy} onClick={() => void stopAndRetry()}>Stop the background service</Button>} />
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
                   Try again
                 </Button>
               } />
      )}

      {state.team && you && phase === "people" && (
        <Button onClick={() => setPhase("group")}>Done adding teammates</Button>
      )}
      {state.team && phase === "group" && (
        <Space orientation="vertical" style={{ width: "100%" }}>
          <Typography.Paragraph>
            Add @{state.bot?.username} to the team's group now (in the group: Add members). Its members see one line per task there.
          </Typography.Paragraph>
          {groupTitle ? <Alert type="success" showIcon message={`Group: ${groupTitle}`} />
            : <Spin description="Waiting for the bot to be added to a group"><div style={{ height: 48 }} /></Spin>}
          {!groupTitle && <Button onClick={() => setPhase("done")}>No group</Button>}
        </Space>
      )}
      {state.team && you && (
        <label>
          <Typography.Text>Team name</Typography.Text>
          <Input value={teamName} onChange={(e) => update({ teamName: e.target.value })} aria-label="Team name" />
        </label>
      )}

      <Space>
        <Button onClick={back}>Back</Button>
        <Button type="primary" disabled={!you || (state.team && !teamName.trim())}
                onClick={() => { if (state.team) update({ teamName: teamName.trim() }); next(); }}>
          Next
        </Button>
      </Space>
    </Space>
  );
}
