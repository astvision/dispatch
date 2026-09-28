# Windows and macOS (X-1 … X-4)

Status: approved design, 2026-09-28; amended the same day by X-1's first run (paths outside the ANSI code page).

## Goal

Dispatch works in every role on macOS and Windows as it does on Linux: a personal bot with its background service, a
member's computer as a worker, and the team server. It is ready for the open-source launch, whose README already promises
all three OSes. Nobody on the team has a Mac or a Windows PC, so the proof comes from GitHub's runners: the whole test
suite on every push, and an opt-in live job with real Claude Code.

In scope: CI that runs the whole suite on the three OSes and is watched; test fakes that run on every OS; starting
processes correctly on Windows; the Windows service; a live job with real agent CLIs; documentation.

Out of scope: the hardened team-server deployment (a dedicated OS user, `deploy/dispatch@.service`) stays Linux-only;
native installers (jpackage); long-path support in Dispatch itself (documented instead); the Mini App and Telegram, which
do not depend on the OS.

The milestones are X-1 … X-4. They were called W1–W4 while designing; W- already names the team-worker milestones.

## Success criteria

1. CI passes on Linux, macOS and Windows with the whole suite running on each. Windows skips only tests of things it
   does not have, each with its reason.
2. Each Windows bug is fixed where every caller passes, not at each call site.
3. The live job passes on all three OSes with real Claude Code: a plan, a correction that resumes it, and the execution.
4. A red CI on `main` is noticed and fixed by the session that pushed, not left for days.

## Where things stand (2026-09-28)

- CI on `main` has been red since 2026-09-23. Since 2026-09-25 the Java matrix has not run at all: it needs the `ui` job,
  which fails first because `PeoplePage.test.tsx` "a member is renamed and made admin" times out at 5 s on the runner
  (run 36368096140). M5, task access (ADR 0027) and the latent-bug fixes have never run on macOS or Windows.
- The last full run (36110020571, 2026-09-25): macOS passed; Linux had 2 errors; Windows had 4 failures, 2 errors and
  100 of 973 tests skipped.
- Most of the 100 skips are 9 classes (`AppTest`, `RunExecutorTest`, `JobRunnerTest`, `DeliveryTest`,
  `ClaudeCodeAgentTest`, `CodexAgentTest`, `GeminiAgentTest`, `TeamWorkersTest`, `WorkerLoopTest`) and 4 methods
  (`WorkerChecksTest` ×3, `WorkspacesTest` ×1), all because their fake CLIs are POSIX shell scripts; the rest are POSIX
  file modes, symlinks and the systemd unit's text. The task loop (plan, execute, deliver) has never run on Windows, not
  even with fakes.

Found by reading the code. Each becomes a failing test before it is fixed.

| # | Where | What goes wrong |
|---|---|---|
| 1 | Both places that start processes: `ProcessRun.start` and `Git.runProcess` | On Windows, JDK 25's `ProcessImpl` in its default legacy mode (`jdk.lang.Process.allowAmbiguousCommands=true`) quotes an argument only when it holds whitespace and never escapes an embedded `"`. Simulated with Windows' argument parsing: `claude --json-schema <plan schema> --model m` sends 4 arguments and Claude receives 60, the schema starting `{type:object,…`. Every planning, split and assistant run fails. Gemini's `-p` (it carries the schema), `gh pr create --title/--body` and `git commit -m` are mangled the same way, and a long PR body can pass Windows' 32,767-character command-line limit. |
| 2 | `ProjectAddCommand` | `--agent codex` or `gemini` writes the bare name. npm installs `codex.cmd` and `gemini.cmd`, and Windows tries only `.exe` for a bare name. |
| 3 | `AssistantHome.of`, `ProcessRun` | `System.getenv()` is a case-sensitive map on Windows (only `System.getenv(String)` ignores case), and Windows spells the variable `Path`. The assistant's `PATH` falls back to `/usr/bin:/bin` and is added beside the inherited `Path`, so its agent gets two and whichever comes first wins. |
| 4 | `WorkerInitCommand.deleteRecursively` | A failed clone's cleanup cannot delete git's read-only object files on Windows, so every retry fails with "already exists and is not an empty directory". |
| 5 | `Coordinator` → `Tasks.recordWorktree` | The server turns a worker's reported worktree into a local `Path`. A Windows server rewrites `/home/ann/…` as `\home\ann\…` and hands that back, so the worker cannot find its worktree. |
| 6 | `WindowsTaskService.restart`, called by the Mini App's Restart | `schtasks /End` kills the process that would then run `schtasks /Run`, so Dispatch stays down until the next login. |
| 7 | `WindowsTaskService.status` | `schtasks /Query /FO LIST` translates its labels and values (on Russian Windows, `Состояние: Выполняется`), so outside English Windows the status reads "unknown". |
| 8 | Tests | `ConfigFileTest` races two edits on `ForkJoinPool.commonPool`, which has one thread on this private repository's 2-vCPU runners, so the edits never overlap (fails on Linux too). `WorkerConfigLoaderTest` writes `/home/…`, which is not absolute on Windows. `ChecksTest` (M5) writes a shell stand-in with POSIX modes. `WorkerProtocolTest`'s flood admitted a fifth request on Windows. |

Also known, and deliberate until now: the assistant's `dispatch` command is a POSIX script (`AssistantHome`'s `ponytail:`
note), and stopping the Windows service kills Dispatch outright.

## Decisions

| Question | Decision | Why |
|---|---|---|
| Who it is for | Every role on every OS: members' laptops as workers, personal bots, the team server, the owner's own future Mac or PC; open-source ready | The owner chose all four |
| Native Windows or WSL2 | Native | The README promises a PowerShell install; WSL2 would ask every member to move their repositories and logins into Linux |
| How it is proven | The whole suite on Linux, macOS and Windows runners on every push, plus an opt-in live job with real Claude Code | No Mac or Windows PC is available |
| Approach | Portable fakes so that Windows runs the whole suite; fixes where every caller passes | Rejected: fixing only what review found and keeping the 100 skips (Windows stays blind on every push, which is how #1 went unnoticed); WSL2 only (adoption cost for the launch) |
| Agents | Claude Code must work everywhere. Codex and Gemini get the same fixes and fake tests, and run live when their keys exist | Codex's quota and the missing Gemini key block their live check today |
| Delivery in the live job | Not repeated live | `DeliveryTest` covers it on every OS with real git, a local bare remote and the fake `gh`, which records `gh`'s arguments exactly; real pull requests would be noise |
| Stopping the Windows service | Stays a forced kill, documented | Dispatch is crash-safe (SQLite's journal, the outbox of ADR 0010, Recovery of ADR 0008); a graceful stop would need a supervisor process |
| Keeping CI honest | A session that pushes watches the run to the end and fixes red first | The red went unnoticed for five days; branch protection would block the owner's direct pushes to `main` |

## Starting processes (X-2)

A new class `dispatch.CommandLine`, next to `ProcessTrees`, turns an argument list into what `ProcessBuilder` must be
given so that the program receives exactly those arguments, and merges environment variables. `ProcessRun.start` (the
agents) and `Git.runProcess` (git, gh, `schtasks`, `launchctl`, `systemctl`, the `--version` checks) both use it. These
are the only two places that start processes. On macOS and Linux it leaves the list as it is.

On Windows:
1. **Lookup.** A program named without a directory (`codex`) is looked up on Dispatch's own PATH with PATHEXT, reusing
   `Executables`, which moves to the `dispatch` package beside it. A program that is not found is passed on unchanged
   and fails to start as it does today.
2. **Quoting.** An argument that is empty or contains whitespace (as Java's `Character.isWhitespace` or `isSpaceChar`
   defines it) or `"` is wrapped in `"…"`. Inside, `"` becomes `\"`, and a run of backslashes is doubled where it
   precedes a `"` or the closing quote. Other arguments pass as they are. JDK 25's legacy mode passes an argument that
   begins and ends with `"` through untouched, so the child receives exactly the original. With
   `jdk.lang.Process.allowAmbiguousCommands=false` the JDK would refuse such arguments; Dispatch's launchers never set it.
3. **`.cmd` and `.bat`.** Such a program runs as `cmd.exe /d /s /c "<program> <arguments>"`, the program always quoted
   and the arguments quoted by rule 2. `/d` skips AutoRun commands, which could print into an agent's JSON stream; `/s`
   makes `cmd.exe` strip only the outer quotes. Before that, the program or any argument containing `%`, `!`, `^`, `&`,
   `|`, `<`, `>`, CR or LF is refused, with an error naming the program and the character: `cmd.exe` expands `%` even
   inside quotes and acts on the others outside them, and refusing is safer than escaping. Prompts go on stdin and never
   meet this check. Dispatch's own arguments, the schemas included, contain none of these characters, which the Windows
   CI proves on every push.
4. **Environment.** Adding a variable first removes every other spelling of its name (`PATH` replaces `Path`), and
   withholding a secret (`TELEGRAM_BOT_TOKEN`, `GH_TOKEN`, `DISPATCH_WORKER_KEY`) removes every spelling.

On every OS, free text leaves the command line. `Gh.createDraftPullRequest` passes the body as `--body-file -` on stdin,
and `Delivery` commits with `git commit -F -`, the whole message on stdin; `Git.runProcess` takes an optional stdin text
for this. Short text from people (the PR title, branch names) stays on the command line, quoted by rule 2.

Unchanged: cancelling an agent on Windows is a forced kill, since Windows has no SIGTERM. The grace period applies on
macOS and Linux.

**The check:** `CommandLineTest` runs on every OS. It starts a real child, `java` running a test main that prints its
arguments, directly and, on Windows, through a `.cmd`. It sends the plan schema, `a"b`, `"quoted"`, a trailing `\`,
`\\"`, an empty string, spaces and Mongolian text, and asserts that each arrives byte for byte. It also asserts that `%`
is refused for a `.cmd`.

## The assistant on Windows (X-2)

`AssistantHome.of` looks PATH up in the environment it is given ignoring the name's case, and the launch helper merges it
by rule 4, so the assistant's agent has one PATH with the member's bin first. The member's `dispatch` command stays a POSIX
script: on Windows, Claude Code's Bash tool runs Git Bash, which runs it. The script writes its Java and class-path
entries with forward slashes and sets `MSYS_NO_PATHCONV=1` before starting Java, so Git Bash passes them unchanged. The
`ponytail:` note in `AssistantHome` goes.

## Tests on every OS (X-2, and CI in X-1)

**One fake.** `dispatch.testing.FakeCli`, a main in test scope, replaces `fake-claude.sh`, `fake-codex.sh`,
`fake-gemini.sh`, `fake-gh.sh` and the stand-ins written inline in `WorkspacesTest` (a git that hangs),
`WorkerChecksTest` (a command that succeeds) and `ChecksTest` (a Codex that is not logged in). `FakeCli.install(dir,
name)` writes a launcher and returns its path:
- on macOS and Linux, `name`: an `sh` script that `exec`s the test JVM's `java -XX:TieredStopAtLevel=1 -cp <test
  classes> dispatch.testing.FakeCli <name> "$@"`;
- on Windows, `name.cmd`: the same with `%*`, the shape of npm's shims.

`FakeClaude`, `FakeAgents` and `FakeGh` keep their names and call it.

**Same contract.** It writes `fake-<name>.args` (one argument per line), `.env` and `.prompt` in its working directory,
reads `SCENARIO:` markers from the prompt and fixtures from `FAKE_CLAUDE_FIXTURES`, answers `--version`, and does what
each script did in each scenario, so the tests' assertions stay as they are. What were shell tricks become Java: the
sleeping child is a second fake JVM whose pid goes to `fake-<name>.child`; ignoring SIGTERM is a shutdown hook that never
returns; the assistant scenario runs `dispatch ask tasks` with `bash -c`, on Windows the Git Bash beside `git.exe`
(`<Git>\bin\bash.exe`), as Claude Code does. Because it records its arguments as a real program receives them, every
assertion on a recorded argument checks Windows quoting.

On Windows every fake is a `.cmd`, so the `cmd.exe` path (rule 3) is the one used most; `CommandLineTest` covers the
`.exe` path. The real `gh` is an `.exe`, so the `gh.cmd` fake is stricter than production: a test's PR title must not
contain rule 3's characters.

**Un-skipped:** the 9 classes and the methods that were skipped for a shell fake. **Still skipped on Windows,** each with
its reason: POSIX file modes (`OwnerOnlyTest` checks Windows ACLs), the systemd unit's text, symlinks (they need
elevation), and timing that depends on SIGTERM.

**CI** (`.github/workflows/ci.yml`, X-1):
- Windows runs the tests with `TMP` and `TEMP` set to `%RUNNER_TEMP%\dispatch тест`, so every temporary path has a space
  and Cyrillic, as under `C:\Users\Батбаяр`.
- No test depends on the number of cores or on timing. Work that must overlap gets its own threads, not the common pool.
  The flood test's fifth admitted request is investigated as a possible limiter bug before the test is touched. vitest's
  `testTimeout` becomes 15 s.
- The job layout stays: the Java matrix still needs the UI build from the `ui` job. Rejected for now: separating them;
  the timeout and watching every push cover the failure we had.

## Other fixes (X-1)

- Failed-clone cleanup clears the read-only attribute and retries a delete that Windows refused. What it still cannot
  remove is named in its warning instead of being ignored.
- A worker's worktree is text on the server: stored and handed back exactly as the worker sent it. Only the machine that
  owns a worktree turns it into a `Path`.
- `ConfigFileTest`, `WorkerConfigLoaderTest` and `WorkerProtocolTest` as in row 8.
- Whatever else the first full run since 2026-09-25 shows: a test bug or a small product bug is fixed in X-1; a test
  that needs a shell fake is skipped with the fake's reason until X-2 runs it (`ChecksTest`'s Codex stand-in is one).

## Paths outside the ANSI code page (found by X-1's first run, 2026-09-28)

X-1's first Windows run with temporary files under `dispatch тест` (run 36385991295) found two JDK limits that Dispatch
inherits. Windows hands a path to native code in its ANSI code page (Java's `native.encoding`), and letters outside it
arrive as `?`: Cyrillic on English Windows, and Ө and Ү even on Mongolian Windows, whose code page is 1251. A path can
still be given by its 8.3 short name (`C:\Users\5C0E~1`), whose letters every code page has; Windows keeps short names on
its system drive, where user folders are.

1. **Loading a native library.** The SQLite driver unpacks its DLL into the temporary folder and loads it from there. Under
   such a folder the load fails ("Can't find dependent libraries") and no database opens, so Dispatch cannot start at all.
   A short name does not help here: the JDK spells a library's path out in full before loading it, expanding 8.3 names
   again (run 36395743054 loaded `C:\Users\runneradmin\…\dispatch ????` from `C:\Users\RUNNER~1\…`). **X-1:** on
   Windows, when the temporary folder's full name does not fit the code page, `Database.open` has the driver unpack into
   `%ProgramData%\dispatch-<hash of the user's name>`, created by and checked to belong to the user Dispatch runs as, so no
   one else can change what is unpacked there. When that fails too, Dispatch stops with an error that says to set `TMP`
   to a folder named in plain letters.
2. **java.exe's own arguments.** java.exe reads its command line in the code page, so a jar or class path with such
   letters is not found ("Unable to access jarfile …????…"). Java is started by `dispatch.cmd` (`install.ps1`), the Windows
   task (X-3), the assistant's `dispatch` script (X-2) and the test fakes (X-2). **X-2 and X-3:** each passes the paths it
   gives java.exe by their 8.3 short names, which java.exe opens as given (`AnsiPaths`, first written in X-1 as 936ac25
   and brought back with its first use). What a person types after `dispatch` keeps the limit, documented in X-4. The fake and
   `CommandLineTest`'s child read their arguments from their own command line as Windows keeps it, in UTF-16
   (`ProcessHandle.current().info().commandLine()`), split by Windows' `CommandLineToArgvW`, and never from `main`'s
   arguments.

CI keeps Maven's own temporary files on an ASCII path (`MAVEN_OPTS=-Djava.io.tmpdir=…`), because surefire starts the
tests' JVM with a jar from there; the tests' JVM takes the Cyrillic `TMP`, created on the system drive (`%TEMP%`), where
short names exist as they do under a member's user folder.

## Services (X-3)

- **Restart.** The Windows task's `MultipleInstancesPolicy` becomes `StopExisting`, and `restart()` becomes one
  `schtasks /Run`: Task Scheduler itself stops the running instance and starts a new one, so the Mini App's Restart works
  from inside the instance it replaces. `start()` does nothing when the task is already running.
- **Status.** Whether it is installed stays `schtasks /Query`'s exit code. Its state comes from
  `powershell -NoProfile -NonInteractive -Command "(Get-ScheduledTask -TaskName '<name>').State"`, whose values
  (`Ready`, `Running`, `Disabled`) are the same in every Windows language.
- **Stop.** `schtasks /End` kills Dispatch outright. Nothing is lost: runs become INTERRUPTED and leftover agents are
  killed when it next starts (Recovery, ADR 0008). Documented, not changed.
- **After a crash.** Task Scheduler's restart-on-failure is documented for a task that fails to start. X-3 checks once
  on a Windows runner what it does when Dispatch exits with an error, and the README promises only what that shows.
- **macOS:** no change expected. The launchd agent already records PATH and is kept alive after a failure.
- **Smoke test in CI** on the macOS and Windows runners, on every push: install the service with a throwaway config whose
  bot token Telegram refuses; wait for Dispatch's own startup line in the service's log file, which proves that the Java,
  jar, config and log paths and their quoting reached the OS; restart and wait for a second startup line; uninstall and
  check that nothing is registered. Linux is left out: the runner has no user systemd, and the live bot runs on Linux. If
  a hosted runner cannot run an `InteractiveToken` task, or has no `gui/<uid>` domain, that OS's smoke is dropped and
  ADR 0028 says why.

## The live job (X-4)

`.github/workflows/live.yml`, run by hand (`workflow_dispatch`) and on `v*` tags, on Linux, macOS and Windows:
- installs Claude Code with its native installer (`claude.exe` on Windows) and runs `LiveAgentsTest` with a new Claude
  Code case beside Codex and Gemini: a plan, a correction that resumes the session, and the execution, in a scratch
  repository, through the real adapter and `CommandLine`;
- runs Claude Code on `haiku` with `--max-budget-usd 0.50` per run, under a dollar for the three OSes;
- needs `ANTHROPIC_API_KEY`, which the owner adds (`gh secret set ANTHROPIC_API_KEY`). Codex and Gemini run when
  `OPENAI_API_KEY` or `GEMINI_API_KEY` exists, and show as skipped otherwise.

Not live: Telegram (plain HTTP), the assistant's `dispatch ask` (the fake runs it through Git Bash), delivery (see
Decisions).

## Documentation (X-4)

- README, Mongolian and English. On Windows: Git for Windows (git, and the Git Bash that Claude Code needs), Java 25,
  `gh`; Claude Code's native `claude.exe` preferred; Codex and Gemini from npm work. Known limits: on Windows, stopping
  the service is a crash (retry the task) and cancelling an agent is a forced kill (resuming its session may fail; retry);
  long paths (`git config --global core.longpaths true`); on macOS, repositories under Desktop, Documents or iCloud Drive
  can trigger privacy prompts for the background service; a sleeping laptop pauses everything.
- ADR 0028, how Dispatch starts processes on Windows: quoting, `.cmd` through `cmd.exe /d /s /c` with the refusal,
  environment names, free text on stdin, and restart through `StopExisting`.
- ARCHITECTURE.md: process launching, the CI matrix, the live job. SECURITY.md: the `.cmd` refusal guards against
  command injection. ADR 0016's service consequences amended.
- After X-4 the owner tags v0.2.0: `install.sh` and `install.ps1` still hand out v0.1.0 from 2026-09-22.

## Milestones

| Milestone | Contents | Done when |
|---|---|---|
| X-1 CI tells the truth | vitest timeout; Windows temp path with a space and Cyrillic; SQLite under such a temp folder (`AnsiPaths`); test bugs; failed-clone cleanup; worker worktrees as text; whatever else the first full run shows | CI passes on the three OSes, Windows still skipping the tests that need a shell fake; macOS is proven for `main` |
| X-2 The task loop on Windows | `FakeCli` (reading its own command line); un-skipping; `CommandLine`; PR body and commit message on stdin; the assistant's PATH and short paths in its script | CI passes on the three OSes with only the stated skips; merged in one go so that `main` never goes red |
| X-3 Services | `StopExisting` restart; status that ignores the Windows language; short paths to java.exe in the task and `dispatch.cmd`; the crash-restart check; the service smoke in CI | The smoke passes on macOS and Windows, or is dropped with its reason recorded |
| X-4 Proven live, documented | `live.yml`; the Claude Code live case; README, ADR 0028, ARCHITECTURE, SECURITY | The live job passes on the three OSes with Claude Code |

Each milestone gets its own branch and a local merge, and every push is watched to the end.

## Risks

- Rule 2 relies on the legacy mode passing quoted arguments through. `CommandLineTest` fails on Windows if a JDK changes
  that.
- Claude Code on Windows needs Git Bash, which Git for Windows includes. A machine with only MinGit fails at the first
  Bash call, visibly.
- Each fake call starts a JVM, a few tenths of a second per agent run in the suite.
- The live job costs money on every tag and manual run; the budget cap bounds it.
