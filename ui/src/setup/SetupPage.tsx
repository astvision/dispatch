import { Button, Card, Result, Spin, Steps, Typography } from "antd";
import { useState } from "react";
import type { ProjectChoice, SetupAdvanced, WorkersChoice } from "../api";
import { useT } from "../i18n/i18n";
import { useSetupState } from "../useSetupState";
import BotStep from "./BotStep";
import ClaudeStep from "./ClaudeStep";
import CommitsStep from "./CommitsStep";
import PeopleStep from "./PeopleStep";
import ProjectsStep from "./ProjectsStep";
import SummaryStep from "./SummaryStep";
import WhoStep from "./WhoStep";

/** What the page collects itself; the server keeps the rest (team, bot, people, group) until Write. */
export interface Draft {
  claude: string;
  projects: ProjectChoice[];
  authorName: string;
  authorEmail: string;
  teamName: string;
  /** A team with a group chat: where its members' computers reach this machine. */
  workers?: WorkersChoice;
  /** The Advanced section's instance answers; left out when it is not used. */
  advanced?: SetupAdvanced;
}

export default function SetupPage({ onDone }: { onDone: () => void }) {
  const t = useT();
  const { state, error, refresh } = useSetupState();
  const [step, setStep] = useState<number | null>(null);
  const [draft, setDraft] = useState<Draft>({ claude: "", projects: [], authorName: "", authorEmail: "", teamName: "" });

  if (error) {
    return <Result status="warning" title={t("setup.cannotStart")} subTitle={error.message}
                   extra={<Button onClick={() => void refresh()}>{t("common.tryAgain")}</Button>} />;
  }
  if (!state) return <Spin size="large" tip={t("setup.starting")}><div style={{ height: 200 }} /></Spin>;

  // Resume where the server left off: Claude Code, Projects and Commits live only on this page, so a reload
  // can't tell those apart and always resumes at Claude Code (step 3). A team setup resumes at People (step 2)
  // instead, even with members already confirmed, because the team name field (and its default) live there;
  // skipping straight to step 3 would leave teamName empty and fail Write.
  if (step === null) {
    setStep(state.bot == null ? 0 : state.team || state.members.length === 0 ? 2 : 3);
    return <Spin size="large" tip={t("setup.starting")}><div style={{ height: 200 }} /></Spin>;
  }

  const update = (change: Partial<Draft>) => setDraft((current) => ({ ...current, ...change }));
  const next = () => {
    void refresh();
    setStep((current) => (current ?? 0) + 1);
  };
  const back = () => setStep((current) => Math.max(0, (current ?? 0) - 1));
  const props = { state, draft, update, refresh, next, back, onDone };

  const steps = [
    { title: t("setup.who"), content: <WhoStep {...props} /> },
    { title: t("setup.bot"), content: <BotStep {...props} /> },
    { title: t(state.team ? "setup.yourTeam" : "setup.you"), content: <PeopleStep {...props} /> },
    { title: "Claude Code", content: <ClaudeStep {...props} /> },
    { title: t("setup.projects"), content: <ProjectsStep {...props} /> },
    { title: t("setup.commits"), content: <CommitsStep {...props} /> },
    { title: t("setup.summary"), content: <SummaryStep {...props} /> },
  ];

  return (
    <Card title={t("setup.title")}>
      <Typography.Paragraph type="secondary">{t("setup.nothingWritten")}</Typography.Paragraph>
      <Steps current={step} size="small" items={steps.map((s) => ({ title: s.title }))} />
      <div style={{ marginTop: 24 }}>{steps[step].content}</div>
    </Card>
  );
}
