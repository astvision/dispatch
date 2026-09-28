# Task commands write a task's news; each channel writes its own reply

Status: proposed

A task command (correct, follow up, retry, cancel, give a task) used to answer whoever asked in the chat itself. A refused
`/cancel` wrote its reason under the command, and a successful one wrote "cancelled" there too. That suited Telegram, the
only channel at the time. Six channels now call the commands: text commands, buttons, the assistant, additions in groups,
the Mini App and the desk. A page must not answer in the chat, so `TasksApi` asks TaskAccess before four of the commands,
answers a refusal itself, and throws if the command then disagrees. The assistant and group additions carry comments
about what the command will already have said.

So a command changes the task, writes the task's **news**, and returns what happened: done, or refused with the reason.
News is what the requester and the group are told whoever acted and wherever: the group announcement, the requester's
line in the task's topic, reactions, and a question message redrawn with its answer. The command no longer writes a
**reply**. Each channel answers whoever acted in its own medium: under the message or as a button's notice in Telegram,
as a note from the assistant, as an HTTP answer on a page. Approve, reject and reprioritize already work this way;
this brings the text commands into line with them.

The commands form one module, `TaskCommands`. A channel builds a command value (give, approve, reject, correct,
answer, reprioritize, cancel, retry, follow up) and runs it. It gets back one of four results:
- done, and whether the news already told the actor;
- a task given: a give, or a merged task's follow-up;
- unchanged: the priority it already had;
- refused, with one reason from one vocabulary. That is TaskAccess's reasons plus the commands' own: empty, unknown
  project, project unavailable.

A refusal writes nothing. `check` answers what `run` would refuse without writing, so the assistant offers a proposal,
and Telegram a prompt, only when it would run. A command value gives buttons and proposals one thing to store, check
and log.

We rejected:
- **A method per command returning a flat result.** Its refusal was null when done, and "unchanged" was a refusal:
  both implicit, where a sealed result says each case outright.
- **Each channel words a refusal.** Four tables (Telegram's lines, its button notices, the pages, the assistant) must
  keep in step. They didn't: "answer the questions in order" had four wordings.
- **Two wordings per refusal**, a chat line and a short one. Fewer tables, but the pair can still drift apart.
- **Channels write the news too.** The desk, the Mini App, the assistant and Telegram would each announce to the group
  and the requester, the same rules copied four times.
- **Commands keep replying, with a quiet flag for pages.** The smallest change, but every command keeps a reply target
  and a chat in its interface, and "quiet" is a mode every caller must know.

## Consequences

- A command's confirmations are news in the task's topic, whatever channel acted: retry queued, correction queued,
  follow-up queued or turned into a new task, cancelled. An action on a page therefore shows in the chat as news, as
  approving from the Mini App already did. The requester's line already went to the task's topic from wherever the
  command was typed, so Telegram adds no second message on success.
- A requester now hears when someone else cancels their task: an admin's cancel reaches the task's topic as well as the
  group. The admin gets a reply where they acted.
- A refusal is only ever a reply. It never reaches the chat from a page.
- A refusal has one wording: a `Text`, raised where it is refused (as ADR 0029 keys a page's messages), naming the task
  as far as its headline allows. Every channel shows those words, so a chat's refusal line and a page's message are
  the same sentence. Telegram adds a command to type (`/retry 5`) after the few refusals that have one. Every wording
  fits a button's notice, 200 characters, in both languages.
- Every task command follows this: cancel, retry, correct, follow up, answering a plan's questions, approve, reject,
  reprioritize, and giving a task. Giving a task was two commands, one for the chat and one for the desk; it is now
  one. The chat's own views (`/status`, `/history`, a task's timeline, `/stats`) and the "type your answer" prompt are
  replies, so the Telegram channel posts them. Drafts keep answering in the chat until they get a module of their own.
- The commands are tested once, at `TaskCommands`' interface, on a real SQLite file. Each command's effect and news is
  covered, and one table maps situations to refusals. Some rules hold for every command and are proven once: a refusal
  writes nothing, news never lands under the actor's message, and every refusal has its words in both languages. The
  channels' tests only prove that they ask and relay. TaskAccess keeps its own table (ADR 0027).
- A question's redraw names the question's outbox row, not its Telegram message. An answer given before the question is
  delivered, from the Mini App or the assistant, then redraws it once it is sent instead of sending it twice.
- A merged task's follow-up is refused exactly as giving a task is. An unavailable project, or a requester who has left
  the project's group, is a refusal on every channel, where a page used to answer 500.
- Merging a delivered task's pull request stays in `Merges`. It already returns its outcome and writes no reply, and it
  asks GitHub in the background, outside the one transaction a command runs in.
