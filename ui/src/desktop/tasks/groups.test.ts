import { expect, test } from "vitest";
import type { TaskRow } from "../../api";
import { grouped, type Filter } from "./groups";

const row = (over: Partial<TaskRow>): TaskRow => ({ taskId: 1, project: "crm", title: "Fix it", state: "running",
  priority: "NORMAL", requester: "Bold", mine: true, actions: [], ...over });

test("a task waits on you only when it is yours; finished ones older than 30 days are left out", () => {
  const now = new Date("2026-09-28T12:00:00Z");
  const rows = [
    row({ taskId: 14, state: "awaitingApproval", mine: true }),
    row({ taskId: 15, state: "awaitingApproval", mine: false, requester: "Ali" }),
    row({ taskId: 13, state: "running" }),
    row({ taskId: 12, state: "queued" }),
    row({ taskId: 11, state: "finished", completedAt: "2026-09-20T10:00:00Z" }),
    row({ taskId: 3, state: "finished", completedAt: "2026-07-01T10:00:00Z" }),
  ];

  expect(grouped(rows, { project: null, person: null, text: "" }, now).map(([group, list]) => [group, list.map((r) => r.taskId)]))
    .toEqual([["waitingOnYou", [14]], ["running", [13]], ["queued", [12]], ["waitingOnOthers", [15]], ["finished", [11]]]);
});

test("the search finds a task by its number or its words, and the filters by project and person", () => {
  const rows = [row({ taskId: 14, title: "Fix the login timeout" }), row({ taskId: 13, title: "PDF export", project: "alm", requester: "Ali" })];
  const found = (filter: Filter) => grouped(rows, filter, new Date()).flatMap(([, list]) => list.map((r) => r.taskId));

  expect(found({ project: null, person: null, text: "#14" })).toEqual([14]);
  expect(found({ project: null, person: null, text: "pdf" })).toEqual([13]);
  expect(found({ project: "alm", person: null, text: "" })).toEqual([13]);
  expect(found({ project: null, person: "Ali", text: "" })).toEqual([13]);
});
