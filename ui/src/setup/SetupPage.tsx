import { Button, Card, Result, Spin, Steps, Typography } from "antd";
import { useState } from "react";
import type { ProjectChoice } from "../api";
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
}

export default function SetupPage({ onDone }: { onDone: () => void }) {
  const { state, error, refresh } = useSetupState();
  const [step, setStep] = useState<number | null>(null);
  const [draft, setDraft] = useState<Draft>({ claude: "", projects: [], authorName: "", authorEmail: "", teamName: "" });

  if (error) {
    return <Result status="warning" title="Cannot start setup" subTitle={error.message}
                   extra={<Button onClick={() => void refresh()}>Try again</Button>} />;
  }
  if (!state) return <Spin size="large" tip="Starting setup…"><div style={{ height: 200 }} /></Spin>;

  // Resume where the server left off: Claude Code, Projects and Commits live only on this
  // page, so a reload can't tell those apart and always resumes at Claude Code (step 3).
  if (step === null) {
    setStep(state.bot == null ? 0 : state.members.length === 0 ? 2 : 3);
    return <Spin size="large" tip="Starting setup…"><div style={{ height: 200 }} /></Spin>;
  }

  const update = (change: Partial<Draft>) => setDraft((current) => ({ ...current, ...change }));
  const next = () => {
    void refresh();
    setStep((current) => (current ?? 0) + 1);
  };
  const back = () => setStep((current) => Math.max(0, (current ?? 0) - 1));
  const props = { state, draft, update, refresh, next, back, onDone };

  const steps = [
    { title: "Who", content: <WhoStep {...props} /> },
    { title: "Bot", content: <BotStep {...props} /> },
    { title: state.team ? "Your team" : "You", content: <PeopleStep {...props} /> },
    { title: "Claude Code", content: <ClaudeStep {...props} /> },
    { title: "Projects", content: <ProjectsStep {...props} /> },
    { title: "Commits", content: <CommitsStep {...props} /> },
    { title: "Summary", content: <SummaryStep {...props} /> },
  ];

  return (
    <Card title="Set up Dispatch">
      <Typography.Paragraph type="secondary">Nothing is written until you confirm the summary at the end.</Typography.Paragraph>
      <Steps current={step} size="small" items={steps.map((s) => ({ title: s.title }))} />
      <div style={{ marginTop: 24 }}>{steps[step].content}</div>
    </Card>
  );
}
