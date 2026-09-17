# Dispatch

Dispatch takes development tasks that members give it in a private chat, has an AI coding tool carry them out against their groups' repositories, and reports progress to the member and outcomes to the group.

## Language

**Group**:
A team's Telegram group together with its members and projects. One bot can serve several groups. A group sees its own projects' tasks and never another group's. A developer's personal bot has a group without a chat: just them and their projects.
_Avoid_: Team, tenant, organization, workspace

**Member**:
A person listed in one or more groups. They may give tasks for, and cancel tasks of, their groups' projects. Other people in a group chat can read the bot's announcements but cannot act on tasks.
_Avoid_: User, operator, admin

**Requester**:
The member who created a task. Its plan is theirs to approve, correct or reject, and its details reach them in their private chat with the bot.
_Avoid_: Owner, author, assignee

**Project**:
A repository a group hands tasks for, together with how Dispatch works on it: its short alias, base branch and agent. Each project belongs to exactly one group.
_Avoid_: Repo, service, workspace

**Task**:
A piece of development work on one project that a member explicitly gives Dispatch in their private chat with the bot, with a priority. One message can give several tasks when the member splits it into parts.
_Avoid_: Job, ticket, request

**Priority**:
How urgently a task should run: urgent, normal or low. More urgent tasks start first; nothing already running is interrupted.
_Avoid_: Severity, importance, rank

**Plan**:
The agent's read-only analysis of a task: the cause and the changes it intends to make, sent to the requester. No code changes until the requester approves the plan.
_Avoid_: Proposal, analysis

**Correction**:
The requester's reply to a plan asking for changes. It produces a revised plan.
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
