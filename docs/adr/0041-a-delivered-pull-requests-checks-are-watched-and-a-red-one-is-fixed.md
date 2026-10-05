# A delivered pull request's checks are watched, and a red one is fixed

Amends ADR 0007 (Dispatch delivers a draft pull request and stops there).

A bot that delivers on its own machine keeps watching each pull request it delivered. A delivery arms a watch on its
commit; a background pass asks GitHub with `gh` once a minute. Green: the result says so and a reply tells the
requester the pull request is ready to merge. Red on Dispatch's own commit: the failed checks and the end of their log
become one more execution in the task's building session, whose delivery pushes onto the same pull request and is
watched in turn. After two such fix runs in a row, or when a fix run changes nothing, the pull request goes back to the
requester with the reason. A member's follow-up starts the count afresh. A human still decides every merge.

We chose this over:
- **A webhook.** A webhook and a secret per repository, a public endpoint on every bot, and polling still needed for the
  hours a laptop sleeps.
- **Keeping the execution run alive until the checks end.** A personal bot runs one task at a time, so the queue would
  wait through every CI run, and a restart would fail the run (ADR 0008).
- **A button instead of an automatic fix.** The verify loop already fixes test failures without asking (ADR 0033); a
  red check is the same failure, found on another machine.

Consequences: a watched pull request costs one `gh pr view` and one `gh pr checks` a minute until its checks end. A fix
run costs what any execution does and counts toward the task's budget. A commit someone else pushed to the branch ends
the watch, as the branch guard would refuse it (ADR 0032). A pull request merged on GitHub is now noticed within a
minute, not at the next follow-up. A team bot watches nothing: its members' computers hold the credentials that
delivered each pull request (ADR 0021). `ci: off`, on the instance or a project, turns the watch off.
