# Setup is a one-line install, an interactive wizard and a background service

Setting Dispatch up used to mean building a jar, copying launchers and writing YAML. Now it takes three steps:
- **Install:** one command builds Dispatch from source with the Maven wrapper and installs the jar and its launcher for the current user, with `install.sh` on macOS and Linux and `install.ps1` on Windows. While the repository is private, the scripts are fetched through the authenticated GitHub CLI; once it is public, `curl ... | sh` and `irm ... | iex` work as they are.
- **Wizard:** `dispatch init` offers choices with the arrow keys, takes the bot token masked, shows progress while it waits on Telegram, and summarizes before it writes anything. It sets up a bot for just you (a group without a chat, ADR 0014) or a shared team bot, whose teammates press Start while it waits and whose group chat is found when the bot is added to it.
- **Service:** at the end, `init` offers to keep Dispatch running as a per-user service. On Linux that is a systemd user unit, on macOS a launchd agent, and on Windows a Task Scheduler task. The service records the PATH that setup ran with, so `claude`, `git` and `gh` are found, and writes its log to a file. `dispatch service status | start | stop | uninstall` manages it.

The terminal handling uses JLine, which puts macOS, Linux and Windows terminals into raw mode (on Windows through Java's FFM API). The prompts themselves are a small layer of Dispatch's own.

We chose this because a team shares a bot only when setting one up is quick, and a shared bot is only useful while it keeps running.

We rejected three alternatives:
- **Native installers per OS (jpackage):** they bundle Java, but need a build and code signing on each OS.
- **JLine's prompt module:** it pulls in JLine's builtins, shell and charset modules for a handful of prompts.
- **Plain line-based questions:** they cannot offer arrow-key choices or masked input the same way on every OS.

## Consequences

- Java 25 or later is still needed on the machine, and JLine adds about 0.7 MB to the jar.
- The service runs as the user who installed it, the same boundary as ADR 0014. On Linux it runs only while that user is logged in, unless `loginctl enable-linger` is on; `dispatch service status` says which.
- ADR 0018 later changed `install.sh` and `install.ps1` to download a release jar instead of always building from source.
