---
name: receiving-code-review
description: Use when a reviewer's findings come back to you to fix, before changing any code for them - check each finding against the code, fix the real ones one at a time, and leave the code alone for a finding you have shown is wrong
---

# Receiving Code Review

## Overview

A review finding is a claim about the code, not an order. Check it before you act on it.

**Core principle:** Verify before implementing. Technical correctness over agreement.

## The Response Pattern

```
FOR the list of findings:

1. READ: The whole list first; findings may be related
2. UNDERSTAND: Restate each one in your own words
3. VERIFY: Check it against the code and the approved plan
4. EVALUATE: Is it right for THIS codebase?
5. ACT: Fix it, or leave the code and record why the finding is wrong
6. TEST: One finding at a time, test each fix
```

## Checking a Finding

Before changing code for a finding, check:
1. Is it technically correct for this codebase?
2. Would the change break existing behaviour?
3. Is there a reason for the current implementation (a test, a comment, a caller)?
4. Does it work on every platform and version the project supports?
5. Did the reviewer have the full context (the plan, the callers, the tests)?

**A finding you cannot verify:** take the most reasonable reading, act on it, and say in your summary what you could not verify.

**A finding that conflicts with the approved plan:** follow the plan, and say in your summary which finding you left and why.

## YAGNI Check

```
IF a finding asks to "implement it properly" or to add a feature:
  grep the codebase for actual usage
  IF unused: do not build it; say so in your summary
  IF used: implement it properly
```

## Implementation Order

```
1. Read and check every finding first
2. Then fix, in this order:
   - Breakage and security
   - Simple fixes (typos, imports)
   - Complex fixes (logic, structure)
3. Test each fix on its own
4. Check for regressions
```

## When a Finding Is Wrong

Leave the code as it is when the finding:
- would break existing behaviour
- misses context the reviewer did not have
- asks for something nothing uses (YAGNI)
- is technically incorrect for this stack
- conflicts with the approved plan

Never change code to satisfy a finding you have shown is wrong. In your summary, name the finding and give the technical reason in one line, pointing at the code or test that shows it.

## Reporting

Your summary says what you fixed and what you left:
```
✅ "Fixed: null check in OrderService.cancel (finding 2)."
✅ "Left finding 3: the legacy path is still called by ImportJob (ImportJob.java:88)."

❌ "You're absolutely right!"
❌ "Great point!"
❌ Thanks, apologies or praise
```

Actions speak: the diff shows what you took from the review.

## Common Mistakes

| Mistake | Fix |
|---------|-----|
| Blind implementation | Verify against the code first |
| Assuming the reviewer is right | Check whether the change breaks things |
| Batch without testing | One at a time, test each |
| Silently skipping a finding | Name it and the reason in your summary |
| Unverifiable finding, no word about it | Act on the most reasonable reading and say what you could not verify |
