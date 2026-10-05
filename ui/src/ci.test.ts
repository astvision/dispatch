import { describe, expect, it } from "vitest";
import { ciLabel } from "./ci";

describe("ciLabel", () => {
  it("says nothing for a task that is not watched, or whose watch ended quietly", () => {
    expect(ciLabel(null)).toBeNull();
    expect(ciLabel(undefined)).toBeNull();
    expect(ciLabel({ state: "NONE", reason: null, fixRounds: 0, check: null })).toBeNull();
    expect(ciLabel({ state: "STOPPED", reason: "MERGED", fixRounds: 0, check: null })).toBeNull();
  });

  it("says how the checks stand in the result message's own symbols", () => {
    expect(ciLabel({ state: "PENDING", reason: null, fixRounds: 0, check: null })).toBe("⏳ CI");
    expect(ciLabel({ state: "PASSED", reason: null, fixRounds: 1, check: null })).toBe("✅ CI");
    expect(ciLabel({ state: "FIXING", reason: null, fixRounds: 1, check: "ui" })).toBe("❌ CI: ui · 🔧 1/2");
    expect(ciLabel({ state: "GAVE_UP", reason: "CAP", fixRounds: 2, check: "ui" })).toBe("❌ CI: ui");
    expect(ciLabel({ state: "GAVE_UP", reason: "STUCK", fixRounds: 0, check: null })).toBe("⚠️ CI");
  });
});
