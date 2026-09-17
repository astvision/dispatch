# Single Dispatch process on the development host

A Dispatch instance (one per team, see ADR 0005) runs as one process on the machine that holds its team's repositories and AI coding CLIs. The Telegram poller, task store, queue and agent subprocesses all live in that process.

We rejected a controller + runner split, where an always-on controller hands work to runners on dev machines. It would let tasks queue while those machines are offline, but at the price of runner auth, heartbeats and run state split across machines. One host per instance is enough.

## Consequences

- Telegram allows only one long-poller per bot token, so an instance cannot span hosts; spreading one team across machines means revisiting the controller/runner split.
- Telegram keeps unfetched updates for 24 hours: messages sent while the host is down longer than that are lost.
- While the host is off, the team's tasks are neither accepted nor run.
