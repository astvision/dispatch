import { Typography } from "antd";
import { getSpend, type Spend } from "../../api";
import { PROJECT_COLOURS } from "../../board";
import { useT } from "../../i18n/i18n";
import { usePolling } from "../usePolling";

const DAYS = 30;
const HEIGHT = 92;

const sum = (usd: Record<string, string>) => Object.values(usd).reduce((total, amount) => total + Number(amount), 0);

/** Spend per day as bars, one colour per project (D-2b); a project on Codex or Gemini CLI is counted, not priced. */
export function SpendBars({ spend }: { spend: Spend }) {
  const t = useT();
  if (spend.projects.length === 0) {
    return <Typography.Text type="secondary">{t("spend.none", { days: spend.days.length })}</Typography.Text>;
  }
  const colour = new Map(spend.projects.map((entry, i) => [entry.project, PROJECT_COLOURS[i % PROJECT_COLOURS.length]]));
  const highest = Math.max(0, ...spend.days.map((day) => sum(day.usd)));
  return (
    <>
      <div className="spend-bars" role="img" aria-label={t("spend.summary", { days: spend.days.length, usd: spend.totalUsd })}>
        {spend.days.map((day) => (
          <div key={day.day} className="spend-bar" title={t("spend.day", { day: day.day, usd: sum(day.usd).toFixed(2) })}>
            {Object.entries(day.usd).map(([project, usd]) => (
              <span key={project}
                    style={{ height: highest ? Math.round((Number(usd) / highest) * HEIGHT) : 0, background: colour.get(project) }} />
            ))}
          </div>
        ))}
      </div>
      <div className="spend-legend">
        {spend.projects.map((entry) => (
          <span key={entry.project}>
            <i style={{ background: colour.get(entry.project) }} aria-hidden="true" />
            {Number(entry.usd) === 0 && entry.unpriced > 0
              ? t("spend.unpriced", { project: entry.project, count: entry.unpriced })
              : `${entry.project} $${entry.usd}`}
          </span>
        ))}
      </div>
    </>
  );
}

/** The Overview's chart: the last 30 days, read with the rest of the page while it is in view. */
export default function SpendChart() {
  const t = useT();
  const { data } = usePolling((signal) => getSpend(DAYS, signal));
  return (
    <section className="panel-box">
      <div className="panel-head">
        <span>{t("spend.title")}</span>
        {data && <span className="hint">{t("spend.window", { days: DAYS, usd: data.totalUsd })}</span>}
      </div>
      {data && <SpendBars spend={data} />}
    </section>
  );
}
