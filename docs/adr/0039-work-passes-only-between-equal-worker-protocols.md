# Work passes only between equal worker protocols

A worker and its team machine each carry one number, `WorkerProtocol.VERSION`, for the shape of what they send each
other: jobs, results, readiness and progress. The worker says its number on every poll and the team machine answers
with its own. On a difference, neither side passes work: the team machine gives that computer no job, remembers what it
said, and tells the requester once that the computer needs the team's version of Dispatch (the `version` blocker, beside
`claude` and `gh`); the worker takes no job and logs which side to update. A side that says no number counts as 0, which
no version is. `WireContractTest` pins every field on the wire, so a change to them fails until the number is raised.

Before this, each new field carried its own compatibility: nullable, left out of the JSON when unset, a constructor for
the shape before it, and a note that a team and its workers upgrade together, which nothing checked. A job with a field
the worker did not know was handed out anyway, could not be read, and hung until its lease ran out.

We chose this over:
- **Per-field compatibility, as before.** Every feature paid for it, and it still failed in the cases its notes named.
- **A lowest version the team machine accepts.** A newer worker would keep working, but its results would have to stay
  readable by an older team machine, which keeps the per-field code on that side.
- **The build's own version as the number.** Every deploy of the team machine would then stop every worker, even one
  that changed nothing on the wire.

Consequences: updating a team machine whose change raises the number stops its members' computers until they update;
their queued runs wait and say why. A team machine from before the
number still hands a job to a newer worker, which will not take it: that one run fails when its lease runs out, and
is retried once both sides are updated. The number is raised by hand, so a wire change that slips past the contract test
(a renamed JSON key inside an untyped value) still needs someone to raise it. The per-field compatibility already in
`Job`, `JobResult` and the worker API can be removed once every installation runs a version with the number.

Amended 2026-10-03 (protocol 2): the wire is `Wire`'s records plus `Job`, `JobResult`, `Readiness`, `Progress` and `Reply`,
written and read by the same code on both sides. The per-field compatibility that preceded the number (constructors for
earlier shapes, fields left out when unset, defaults for a side that said nothing) is gone: a side on another number is
given nothing, so there is nothing for such fields to protect.
