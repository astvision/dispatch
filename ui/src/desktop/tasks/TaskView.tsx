import { Alert, Button, Flex, Input, Popconfirm, Space, Spin, Typography } from "antd";
import { useState } from "react";
import {
  answerQuestion, ApiError, approvePlan, cancelTask, correctPlan, getTaskDetail, rejectPlan, retryTask, type Answer, type TaskAction,
} from "../../api";
import { useT, type Key } from "../../i18n/i18n";
import { usePolling } from "../usePolling";

interface Props {
  taskId: number;
  /** "panel" beside the list, ending with Details; "page" on the task's own page. */
  layout?: "panel" | "page";
  /** After an action took effect: the list reads itself again. */
  onChanged?: () => void;
  onDetails?: () => void;
}

/**
 * One task as the owner reads and decides it (D-2): its plan, the question it waits on, and the actions the bot says the
 * owner may take now (ADR 0027). A button whose action is not in the task's list is not shown; a refusal says why.
 */
export default function TaskView({ taskId, layout = "panel", onChanged, onDetails }: Props) {
  const t = useT();
  const { data: task, error, reload } = usePolling((signal) => getTaskDetail(taskId, signal), 5000, taskId);
  const [busy, setBusy] = useState(false);
  const [refusal, setRefusal] = useState<string | null>(null);
  const [correcting, setCorrecting] = useState(false);
  const [correction, setCorrection] = useState("");
  const [ownAnswer, setOwnAnswer] = useState("");

  const act = async (call: () => Promise<unknown>) => {
    setBusy(true);
    setRefusal(null);
    try {
      await call();
      setCorrecting(false);
      setCorrection("");
      setOwnAnswer("");
      onChanged?.();
    } catch (e) {
      setRefusal(e instanceof ApiError ? e.message : String(e));
    } finally {
      setBusy(false);
      reload();
    }
  };

  if (!task) return error ? <Alert type="warning" showIcon message={error.message} /> : <Spin />;
  const can = (action: TaskAction) => task.actions.includes(action);
  const plan = task.plan;
  const question = plan && plan.current > 0 ? plan.questions.find((asked) => asked.index === plan.current) : undefined;
  const answer = (given: Answer) => plan && question && act(() => answerQuestion(task.taskId, plan.planSeq, question.index, given));
  const facts = [task.project, task.requester, task.priority && t(`tasks.priority.${task.priority}` as Key),
    task.costUsd && `$${task.costUsd}`].filter(Boolean).join(" · ");

  return (
    <Flex vertical gap={12}>
      <div>
        <Typography.Title level={5} style={{ margin: 0 }}>#{task.taskId} {task.title}</Typography.Title>
        <Typography.Text type="secondary">{facts}</Typography.Text>
      </div>
      {refusal && <Alert type="error" showIcon message={refusal} />}
      {plan ? (
        <section>
          <Typography.Text type="secondary" className="task-label">{t("tasks.plan")}</Typography.Text>
          {plan.understanding && <Typography.Paragraph style={{ margin: "4px 0" }}>{plan.understanding}</Typography.Paragraph>}
          <ol className="task-steps">{plan.steps.map((step, index) => <li key={index}>{step}</li>)}</ol>
          {plan.risks.length > 0 && (
            <>
              <Typography.Text type="secondary" className="task-label">{t("tasks.risks")}</Typography.Text>
              <ul className="task-steps">{plan.risks.map((risk, index) => <li key={index}>{risk}</li>)}</ul>
            </>
          )}
        </section>
      ) : task.phase === "PLANNING" && <Typography.Text type="secondary">{t("tasks.noPlan")}</Typography.Text>}
      {plan && question && can("answer") && (
        <section className="task-question">
          <Typography.Text type="secondary">{t("tasks.question", { index: question.index, count: plan.questions.length })}</Typography.Text>
          <Typography.Paragraph strong style={{ margin: "4px 0 8px" }}>{question.text}</Typography.Paragraph>
          <Flex wrap gap={6}>
            {question.options.map((option, index) => (
              <Button key={index} disabled={busy} onClick={() => void answer({ option: index })}>{option}</Button>
            ))}
            <Button disabled={busy} onClick={() => void answer({ decide: true })}>{t("tasks.youDecide")}</Button>
          </Flex>
          <Space.Compact style={{ width: "100%", marginTop: 8 }}>
            <Input aria-label={t("tasks.yourAnswer")} placeholder={t("tasks.yourAnswer")} value={ownAnswer}
                   onChange={(e) => setOwnAnswer(e.target.value)} />
            <Button disabled={busy || !ownAnswer.trim()} onClick={() => void answer({ text: ownAnswer.trim() })}>{t("tasks.send")}</Button>
          </Space.Compact>
        </section>
      )}
      {plan && correcting && (
        <Flex vertical gap={6}>
          <Input.TextArea aria-label={t("tasks.correction")} placeholder={t("tasks.correction")} value={correction}
                          autoSize={{ minRows: 2 }} onChange={(e) => setCorrection(e.target.value)} />
          <Button type="primary" disabled={busy || !correction.trim()} style={{ alignSelf: "flex-start" }}
                  onClick={() => void act(() => correctPlan(task.taskId, plan.planSeq, correction.trim()))}>{t("tasks.send")}</Button>
        </Flex>
      )}
      <Flex wrap gap={8} align="center">
        {plan && can("approve") && (
          <Button type="primary" loading={busy} onClick={() => void act(() => approvePlan(task.taskId, plan.planSeq))}>
            {t("tasks.approve")}
          </Button>
        )}
        {plan && can("correct") && !correcting && <Button onClick={() => setCorrecting(true)}>{t("tasks.correct")}</Button>}
        {plan && can("reject") && (
          <Popconfirm title={t("tasks.rejectAsk")} okText={t("tasks.reject")} cancelText={t("common.cancel")}
                      onConfirm={() => void act(() => rejectPlan(task.taskId, plan.planSeq))}>
            <Button danger>{t("tasks.reject")}</Button>
          </Popconfirm>
        )}
        {can("cancel") && (
          <Popconfirm title={t("tasks.cancelAsk", { id: task.taskId })} okText={t("tasks.cancel")} cancelText={t("common.cancel")}
                      onConfirm={() => void act(() => cancelTask(task.taskId))}>
            <Button danger>{t("tasks.cancel")}</Button>
          </Popconfirm>
        )}
        {can("retry") && <Button onClick={() => void act(() => retryTask(task.taskId))}>{t("tasks.retry")}</Button>}
        {task.prUrl && <Typography.Link href={task.prUrl} target="_blank" rel="noreferrer">{t("tasks.pullRequest")}</Typography.Link>}
      </Flex>
      {layout === "panel" && onDetails && (
        <Button type="link" onClick={onDetails} style={{ alignSelf: "flex-start", paddingInline: 0 }}>{t("tasks.details")} →</Button>
      )}
    </Flex>
  );
}
