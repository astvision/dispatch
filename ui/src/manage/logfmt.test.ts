import { expect, test } from "vitest";
import { parseLogLine } from "./logfmt";

test("a line becomes its fields, in order, with time, level, event and task picked out", () => {
  const row = parseLogLine('ts=2026-09-28T06:25:24.949Z level=INFO event=run.finished task=12 run=1 status=FAILED cost_usd=null');
  expect(row.ts).toBe("2026-09-28T06:25:24.949Z");
  expect(row.level).toBe("INFO");
  expect(row.event).toBe("run.finished");
  expect(row.task).toBe("12");
  expect(row.fields).toEqual([["run", "1"], ["status", "FAILED"], ["cost_usd", "null"]]);
});

test("a quoted value keeps its spaces, quotes, backslashes and line breaks", () => {
  const row = parseLogLine('ts=t level=ERROR event=outbox.failed error="Bad \\"Request\\": x\\\\y\\nnext" kind=TASK_MERGED');
  expect(row.fields).toEqual([["error", 'Bad "Request": x\\y\nnext'], ["kind", "TASK_MERGED"]]);
});

test("an empty value is kept as empty", () => {
  expect(parseLogLine('ts=t level=INFO event=e detail=""').fields).toEqual([["detail", ""]]);
});

test("a line that is not logfmt comes back as it is", () => {
  const row = parseLogLine("\tat dispatch.core.Coordinator.execute(Coordinator.java:60)");
  expect(row).toEqual({ line: "\tat dispatch.core.Coordinator.execute(Coordinator.java:60)", ts: null, level: null,
    event: null, task: null, fields: [] });
});

test("an unterminated quote ends the line instead of throwing", () => {
  expect(parseLogLine('ts=t level=WARN event=e error="cut off').fields).toEqual([["error", "cut off"]]);
});
