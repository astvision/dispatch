import { expect, test } from "vitest";
import type { PlanDecisionView, PlanQuestionView, PlanView } from "../api";
import golden from "./plan-view.json";

// The TypeScript half of the plan view's contract; TasksApiTest asserts the server produces plan-view.json exactly.
// One entry per field of each type: tsc fails when a type gains or loses a field, and this test when the server does.
const PLAN: Record<keyof PlanView, true> = {
  planSeq: true, current: true, understanding: true, findings: true, steps: true, risks: true, questions: true,
  decisions: true, plugins: true, result: true, answer: true,
};
const QUESTION: Record<keyof PlanQuestionView, true> = { index: true, text: true, options: true, answer: true };
const DECISION: Record<keyof PlanDecisionView, true> = { text: true, chosen: true, alternatives: true };

// tsc checks every field's type here, not only its name; JSON has no literal types, so result is narrowed by hand.
const typed: PlanView = { ...golden, result: golden.result as PlanView["result"] };

const fields = (value: object) => Object.keys(value).sort();

test("the server's plan view has exactly the fields the pages are typed against", () => {
  expect(fields(typed)).toEqual(fields(PLAN));
  expect(fields(golden.questions[0])).toEqual(fields(QUESTION));
  expect(fields(golden.decisions[0])).toEqual(fields(DECISION));
});
