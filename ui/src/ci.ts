import type { CiView } from "./api";

/** The server's CiWatch.MAX_FIX_ROUNDS. */
const MAX_FIX_ROUNDS = 2;

/** One line on how a pull request's checks stand, in the result message's own symbols; null when there is nothing to say. */
export function ciLabel(ci: CiView | null | undefined): string | null {
  if (!ci) return null;
  switch (ci.state) {
    case "PENDING":
      return "⏳ CI";
    case "PASSED":
      return "✅ CI";
    case "FIXING":
      return `❌ CI${ci.check ? `: ${ci.check}` : ""} · 🔧 ${ci.fixRounds}/${MAX_FIX_ROUNDS}`;
    case "GAVE_UP":
      return ci.check ? `❌ CI: ${ci.check}` : "⚠️ CI";
    default:
      return null;
  }
}
