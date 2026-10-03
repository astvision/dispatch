import { expect, test } from "vitest";
import { PHASES } from "../api";
import golden from "./phases.json";

// The TypeScript half of the phase contract; a Java test asserts Phase.values() is phases.json exactly. Every page literal
// is typed as Phase, so tsc fails on a misspelt one and this test when a phase is added or renamed on either side.
test("the pages know exactly the server's phases", () => {
  expect([...PHASES]).toEqual(golden);
});
