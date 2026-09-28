import { render, screen } from "@testing-library/react";
import { expect, test } from "vitest";
import type { Spend } from "../../api";
import { SpendBars } from "./SpendChart";

const spend: Spend = {
  from: "2026-09-26",
  to: "2026-09-28",
  days: [
    { day: "2026-09-26", usd: { crm: "0.30" } },
    { day: "2026-09-27", usd: {} },
    { day: "2026-09-28", usd: { crm: "0.10", alm: "0.05" } },
  ],
  projects: [
    { project: "crm", usd: "0.40", runs: 3, unpriced: 0 },
    { project: "alm", usd: "0.05", runs: 1, unpriced: 0 },
    { project: "life", usd: "0.00", runs: 2, unpriced: 2 },
  ],
  totalUsd: "0.45",
};

test("each day is a bar stacked by project, the costliest day full height, and each project's total below", () => {
  const { container } = render(<SpendBars spend={spend} />);

  const bars = container.querySelectorAll(".spend-bar");
  expect(bars).toHaveLength(3);
  expect((bars[0].firstElementChild as HTMLElement).style.height).toBe("92px");
  expect(bars[1].children).toHaveLength(0);
  expect(screen.getByText("crm $0.40")).toBeInTheDocument();
  expect(screen.getByText("alm $0.05")).toBeInTheDocument();
  expect(screen.getByRole("img", { name: "Spend per day for 3 days, $0.45 in all" })).toBeInTheDocument();
});

test("a project on Codex or Gemini CLI is counted, not priced", () => {
  render(<SpendBars spend={spend} />);

  expect(screen.getByText("life: 2 runs, no cost reported")).toBeInTheDocument();
});

test("days without runs say so", () => {
  render(<SpendBars spend={{ ...spend, days: spend.days.map((day) => ({ ...day, usd: {} })), projects: [], totalUsd: "0.00" }} />);

  expect(screen.getByText("No runs in these 3 days.")).toBeInTheDocument();
});
