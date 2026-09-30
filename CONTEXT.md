# Dispatch

Dispatch takes development tasks that members give it in a private chat, has an AI coding tool carry them out against their groups' repositories, and reports progress to the member and outcomes to the group.

## Language

**Group**:
A team's Telegram group together with its members and projects. One bot can serve several groups. A group sees its own projects' tasks and never another group's. A developer's personal bot has a group without a chat: just them and their projects.
_Avoid_: Team, tenant, organization, workspace

**Member**:
A person listed in one or more groups. They may give tasks for their groups' projects, and approve, correct, reject, reprioritize, follow up on, retry or cancel their own task. Other people in a group chat can read the bot's announcements but cannot act on tasks.
_Avoid_: User, operator, admin

**Admin**:
A person who decides who may use a shared bot. When someone new writes to it, admins choose the group to add them to, or deny them. An admin may also cancel any task, even one in a group they are not a member of (ADR 0020).
_Avoid_: Owner, moderator

**Requester**:
The member who created a task. Its plan is theirs to approve, correct or reject, and its details reach them in their private chat with the bot. It stays theirs after they leave its project's group.
_Avoid_: Owner, author, assignee

**Project**:
A repository a group hands tasks for, together with how Dispatch works on it: its short alias, base branch and agent. A project belongs to at least one group, and may have several, one per Telegram group it is announced in.
_Avoid_: Repo, service, workspace

**Task**:
A piece of development work on one project that a member explicitly gives Dispatch in their private chat with the bot, with a priority. One message can give several tasks when the member splits it into parts.
_Avoid_: Job, ticket, request

**Draft**:
A member's message waiting to become a task: it has a project and a priority (low unless changed) and becomes the task when they send it. Their replies to its prompt add context to it until then.
_Avoid_: Addition (that is a group reply to a task's message), pending task

**Headline**:
What a member may see of someone else's task: who gave it, its project, title, priority, state and pull request link, never its plan, the agent's actions or its cost (ADR 0020).
_Avoid_: Summary, preview

**Priority**:
How urgently a task should run: urgent, normal or low. More urgent tasks start first; nothing already running is interrupted.
_Avoid_: Severity, importance, rank

**Plan**:
The agent's read-only analysis of a task: the cause and the changes it intends to make, sent to the requester. No code changes until the requester approves the plan.
_Avoid_: Proposal, analysis

**Assistant**:
The bot's side of a member's private conversation: it answers questions about their tasks and code and proposes changes, but never makes one. Each member has their own, which forgets after `/new` or 12 hours of silence.
_Avoid_: Agent (that runs tasks), chatbot, AI

**Proposal**:
A change the assistant suggests — a new task, an answer to a plan's question, approve, reject, cancel, retry or a follow-up — shown as a button. It happens only when the member taps it, once, and not at all if the task has moved on.
_Avoid_: Action, suggestion, command

**Step**:
One part of a run as the run monitor shows it: the implementation, a test run, a fix, a pause before the review, the review, or the delivery. A planning run is one step. The requester can skip the running test, fix or review, or deliver now.
_Avoid_: Stage, phase (a task's phase is its place in its life)

**Decision**:
A choice the agent made on its own in a plan, such as what an ambiguous word means, with one to three alternatives. Unlike a plan's question it never holds approval up: approving takes the agent's choice, and tapping an alternative is a correction.
_Avoid_: Assumption, question (that blocks approval)

**Correction**:
The requester's reply to a plan asking for changes, their ✏️ reply, or a decision's alternative they tapped. It produces a revised plan.
_Avoid_: Feedback, comment

**Follow-up**:
A member's reply to a finished task's result with further instructions. It changes code right away, without a new plan.
_Avoid_: Revision, amendment

**Addition**:
More instructions someone writes in a linked group by replying to the message a task came from, or to the message someone gave as a task by replying to it with a mention: whoever wrote that message, or a member of the chat's project groups. The task's requester gets it privately, and one tap turns it into a correction of the waiting plan or a follow-up of the finished task; nothing changes until they tap.
_Avoid_: Amendment, comment, note

**Delivery**:
Handing an execution run's changes to the team: committing them to the task's branch, pushing, and opening or updating the task's draft pull request. On a personal bot, its requester may then merge that pull request with one tap; a merged task takes no more commits.
_Avoid_: Publish, deploy, release

**Verification**:
What the verify loop found before delivery: whether the project's tests pass, what the reviewer left, and whether the loop stopped early. It is written into the delivery commit and the requester's result.
_Avoid_: QA, check

**Review**:
A fresh read-only agent session that judges an execution's change against the approved plan. Its blocking findings go back to the building session.
_Avoid_: Audit, inspection

**Run**:
One invocation of an agent on a task: either a planning run (read-only) or an execution run (implementing an approved plan or a follow-up). All runs of a task work on the same branch. Planning runs continue one agent conversation. Execution runs continue another, which starts from the approved plan instead of the investigation.
_Avoid_: Attempt, execution, job

**News**:
What the bot tells a task's requester and group about the task, whoever acted and wherever they acted: that it was queued, planned, cancelled or finished. The requester's news arrives in the task's own topic.
_Avoid_: Notification, announcement, event

**Refusal**:
The bot declining what someone asked of a task, with the reason: not their task, no such task, the plan moved on, a question still open, the wrong phase. Nothing about the task changes.
_Avoid_: Error, denial, rejection (a requester rejects a plan)

**Reply**:
The bot's answer to whoever acted, in the place where they acted: under their message, as a button's brief notice, or on the page they used. A refusal and its reason is always a reply, never news.
_Avoid_: Response, acknowledgement, toast
