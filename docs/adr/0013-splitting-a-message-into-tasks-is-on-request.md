# Splitting a message into several tasks is on request

A member sometimes writes several unrelated tasks in one private message. The prompt that asks for its project and priority has a ✂️ button. Pressing it asks a small model which independent tasks the message holds, and the prompt then lists them. The member either splits the message, so each part becomes its own draft with its own project and priority, or keeps it as one task. When the model finds one topic, the prompt says so and keeps its buttons.

The split runs Claude Code with Haiku, no tools, a short system prompt of its own and a structured answer. It runs outside any task and worktree, and no session is saved. Measured on Mongolian messages, it costs about $0.015 and takes about 3 seconds, capped at $0.25 and one minute. A split that fails, or is cut off by a restart, is shown as failed on the prompt, like an interrupted run (ADR 0008), and the member can press ✂️ again.

We chose this because splitting is rarely needed. Doing it for every message would add about $0.02 and 5 seconds to every prompt.

We rejected three alternatives:
- **Splitting every message automatically:** the cost and delay above, on every message.
- **Asking members to send one task per message:** no cost, but members asked Dispatch to handle several topics in one message.
- **Letting the planning agent split:** a plan belongs to one project, and each part may need a different project.

## Consequences

- Each part carries the reference of the message it came from, plus its part number, so its task still replies under that message.
- The model may shorten or rephrase a topic. The member sees each part's text before choosing to split.
- A prompt that must change after the button press (the proposal, a failure) is edited through the outbox (ADR 0010), so the change survives Telegram errors and restarts.
