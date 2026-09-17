# Dispatch

Dispatch takes development tasks posted in a team's Telegram group, has an AI coding tool carry them out against the team's repositories, and reports the results back to the group.

## Language

**Team**:
The developers who share one Telegram group and one bot, served by their own Dispatch instance. A team never sees another team's tasks or projects.
_Avoid_: Tenant, organization, workspace

**Member**:
A person on the team's allowlist who may create, cancel and retry tasks. Other people in the team group can read the bot's replies but cannot act on tasks.
_Avoid_: User, operator, admin

**Requester**:
The member who created a task. Its plan is theirs to approve, correct or reject, and its details reach them in their private chat with the bot.
_Avoid_: Owner, author, assignee

**Project**:
A repository the team hands tasks for, together with how Dispatch works on it: its short alias, base branch and agent.
_Avoid_: Repo, service, workspace

**Task**:
A piece of development work on one project that a member explicitly hands to Dispatch from the team group. Its description is either the command text or the group message the command replies to.
_Avoid_: Job, ticket, request

**Plan**:
The agent's read-only analysis of a task: the cause and the changes it intends to make, posted to the team group. No code changes until a member approves the plan.
_Avoid_: Proposal, analysis

**Correction**:
A member's reply to a plan asking for changes. It produces a revised plan.
_Avoid_: Feedback, comment

**Follow-up**:
A member's reply to a finished task's result with further instructions. It changes code right away, without a new plan.
_Avoid_: Revision, amendment

**Delivery**:
Handing an execution run's changes to the team: committing them to the task's branch, pushing, and opening or updating the task's draft pull request.
_Avoid_: Publish, deploy, release

**Run**:
One invocation of an agent on a task: either a planning run (read-only) or an execution run (implementing an approved plan or a follow-up). All runs of a task continue the same agent conversation on the same branch.
_Avoid_: Attempt, execution, job
