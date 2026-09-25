# Graph Report - dispatch  (2026-09-25)

## Corpus Check
- 425 files · ~377,664 words
- Verdict: corpus is large enough that graph structure adds value.

## Summary
- 4604 nodes · 18460 edges · 188 communities (111 shown, 73 thin omitted)
- Extraction: 85% EXTRACTED · 15% INFERRED · 0% AMBIGUOUS · INFERRED: 2765 edges (avg confidence: 0.81)
- Token cost: 1,012,489 input · 0 output

## Community Hubs (Navigation)
- Run Execution Tests
- Drafts and Task Splitting
- Domain Records and Test Clock
- Worker Store Transactions
- Task and Run Model
- App Wiring and Workspaces
- JSON and Task Service Core
- Worker API Test Fixture
- Mini App Shell and Routing
- Agent Runs and Activity
- Init and Worker Init Commands
- Bot API and Database Tests
- Telegram Message Rendering Tests
- Task Service Collaborators
- Management API Tests
- Update Handler Tests
- Management API Saving
- Setup Wizard UI
- Task Lifecycle Tests
- Frontend API Client
- Telegram Message Renderer
- Outbox Message Kinds
- Design Specs
- Service Locations and Overview
- Assistant Conversation
- End-to-End App Tests
- OS Service Installers
- Task Commands
- Mini App Home and Ticket Sheet
- Mention Handling Tests
- Config Loading Tests
- Database and App Startup
- Update Handler Messages
- UI Package Dependencies
- Secrets File Tests
- Worker Pairing Keys
- Mini App Task Pages
- Project Forms UI
- Architecture Decisions
- Claude Stream Parser
- Status and History
- Bot API Client
- Outbox Sender
- Scripted Terminal Tests
- Remote Workers Tests
- Setup Writer
- Setup API
- Outbox Sender Tests
- Active Runs Registry
- Plan Questions Flow
- Test Teardown Hooks
- Job Runner
- Config Edit in Place
- Worker HTTP API
- Mini App Theme and Palette
- Run Coordinator
- Group Linking
- Groups and Membership Rules
- Setup API Tests
- Config Validation
- UI HTTP Server
- Interactive Terminal
- UI Server Tests
- Config Edit Tests
- Worker Readiness
- Worker Command
- Mini App Tasks API
- Overview and Restart UI
- Database Row Mapping
- TypeScript Config
- Project Add Command
- Job Runner Tests
- Secret Redaction
- Tasks API Tests
- Worker Sweeper
- Health Checks
- Scheduler Tests
- Health Check Tests
- CLI Parsing
- Config File Handling
- Service Spec Tests
- Worker Loop
- Assistant Actions Tests
- Telegram Auth Tests
- Worker Checks
- Service Control
- Config Text Tests
- Config Text Editing
- Ask Command
- Plan Answers
- Stats
- Membership Requests
- CLI Tests
- Mini App Server Tests
- Product and Security Docs
- Team Config and Workers
- Web UI Plan Classes
- UI Command
- Group Writer Tests
- Approve and Create Results
- Remote Workers
- E2E Test Harness
- One-Time Link Auth
- App Shutdown
- Executable Lookup
- Assistant Actions
- Telegram Usernames
- Coordinator Tests
- Group Acknowledgement Tests
- Group Acknowledgements
- Telegram Mini App Auth
- Advanced Init Tests
- Init Command Tests
- Domain Glossary
- Overview API Tests
- Setup Wizard Plan
- Config Text Lines
- Group Writer
- Process Tree Cleanup
- Worker Checks Tests
- Team Worker Plans
- Setup Decisions
- Management Pages Plan
- Run Limits Config
- Check Command Tests
- Execution Architecture
- Terminal Option Tests
- Maven Wrapper
- Member Writer
- Mini App Server
- Check Command Teardown
- Join Decisions
- Split State
- Init Recording Service
- Manage API Stub Service
- Member Preferences Page
- Worker Config Loader
- Project Add Tests
- Priority Results
- Reject Results
- Personal Group Tests
- Run Claim Tests
- Setup API Stub Service
- Worker Config Tests
- Follow-Up Results
- Group Ack Preference
- Run Causes
- Telegram Poller
- Folder Browser
- Cancel Results
- Retry Results
- Web UI Plans and Specs
- Unix Installer
- Check Levels
- Mini App Server Teardown
- Assistant Evals
- Frontend Test Setup
- Mini App Decisions
- Windows Installer
- Database Exception
- Test Sleeper
- CI and Release
- Launcher Script
- Fake Claude Script
- Fake gh Script
- HTML Entry Pages
- Setup E2E Spec
- Single-Process Decision
- SQLite Decision
- Build vs Adopt Decision
- Telegram Offset Decision
- Private Task Details Decision
- Dot-and-Word Rule
- Printed Numerals Rule
- No Channel Interface
- CliException Plan Note
- Duration Parsing Plan Note
- Maven Project
- Stray Playwright Snapshot A
- Stray Playwright Snapshot B
- Principle: Dense Not Busy
- Principle: Live State
- Principle: One Decision
- Secret Redaction Rule

## God Nodes (most connected - your core abstractions)
1. `Tx` - 249 edges
2. `Requester` - 174 edges
3. `UpdateHandlerTest` - 155 edges
4. `TaskService` - 138 edges
5. `Database` - 122 edges
6. `Config` - 106 edges
7. `UpdateHandler` - 100 edges
8. `Groups` - 88 edges
9. `BotApi` - 88 edges
10. `ActiveRuns` - 87 edges

## Surprising Connections (you probably didn't know these)
- `RunExecutor (legacy monolithic executor, deleted in W-2)` --conceptually_related_to--> `JobRunner`  [EXTRACTED]
  docs/superpowers/plans/2026-09-23-team-workers-w2.md → src/main/java/dispatch/core/JobRunner.java
- `Help the agent: project CLAUDE.md convention` --semantically_similar_to--> `Dispatch assistant instructions (CLAUDE.md)`  [INFERRED] [semantically similar]
  README.md → src/main/resources/assistant/CLAUDE.md
- `deploy/example.yaml team instance config template` --semantically_similar_to--> `personal.yaml: fixture of dispatch init personal-instance config`  [INFERRED] [semantically similar]
  deploy/example.yaml → src/test/resources/personal.yaml
- `Ruling: Service gains one Kind axis instead of a second set of service classes` --rationale_for--> `Kind`  [EXTRACTED]
  docs/superpowers/plans/2026-09-23-team-workers-w4.md → src/main/java/dispatch/cli/Service.java
- `Ruling: team mode triggers on more than one distinct member across all groups` --rationale_for--> `Workers`  [EXTRACTED]
  docs/superpowers/plans/2026-09-23-team-workers-w3.md → src/main/java/dispatch/config/Config.java

## Import Cycles
- None detected.

## Hyperedges (group relationships)
- **Core task lifecycle: plan, approve, implement, deliver** — context_plan, context_task, context_delivery, context_run [EXTRACTED 1.00]
- **Worker execution boundary: Coordinator hands an immutable Job to a Worker (JobRunner/RemoteWorkers)** — docs_architecture_coordinator, docs_architecture_jobrunner, docs_architecture_worker_interface, docs_architecture_remoteworkers [EXTRACTED 1.00]
- **Home screen FIRST VIEWPORT: Pass, Rail, Served** — impeccable_surfaces_ui_src_mini_homepage_tsx_pass, impeccable_surfaces_ui_src_mini_homepage_tsx_rail, impeccable_surfaces_ui_src_mini_homepage_tsx_served [INFERRED 0.85]
- **Durable Delivery and Explicit Retry Across Telegram, Outbox and Message Splitting** — docs_adr_0008_interrupted_runs_fail_and_are_retried_explicitly_decision, docs_adr_0010_durable_telegram_input_and_outcome_outbox_decision, docs_adr_0013_splitting_a_message_into_tasks_is_on_request_decision [INFERRED 0.85]
- **Progressive Narrowing of Who Sees Task Detail** — docs_adr_0011_task_details_go_to_the_requester_privately_decision, docs_adr_0012_one_bot_for_several_groups_tasks_given_privately_decision, docs_adr_0020_members_see_only_the_headline_of_each_others_tasks_decision [INFERRED 0.85]
- **Member-Owned Worker Execution Model** — docs_adr_0021_team_members_tasks_run_on_their_own_computers_decision, docs_adr_0022_a_task_has_an_assignee_and_a_worker_proves_it_is_ready_decision, docs_adr_0023_a_personal_bot_may_link_a_group_per_project_decision [INFERRED 0.80]
- **Mini App request authentication and task API pipeline** — docs_superpowers_specs_2026_09_22_mini_app_design_telegramauth, docs_superpowers_plans_2026_09_23_mini_app_ui_3b_tasksapi, docs_superpowers_specs_2026_09_22_web_ui_design_uiauth [INFERRED 0.85]
- **Group linking mechanism: personal/team rule, GroupWriter and GroupLinks** — docs_superpowers_specs_2026_09_24_group_linking_design_isteam_rule, docs_superpowers_plans_2026_09_24_group_linking_g1_groupwriter, docs_superpowers_plans_2026_09_24_group_linking_g1_grouplinks [INFERRED 0.85]
- **ADR amendments enabling task assignment** — docs_superpowers_specs_2026_09_23_assignment_and_readiness_design_adr0022, docs_superpowers_specs_2026_09_23_assignment_and_readiness_design_adr0011_amendment, docs_superpowers_specs_2026_09_23_assignment_and_readiness_design_adr0012_amendment, docs_superpowers_specs_2026_09_22_team_workers_design_adr0021 [INFERRED 0.75]
- **The setup wizard's steps, sharing SetupPage's state** — docs_superpowers_plans_2026_09_22_web_ui_2_setuppage, docs_superpowers_plans_2026_09_22_web_ui_2_whostep, docs_superpowers_plans_2026_09_22_web_ui_2_botstep, docs_superpowers_plans_2026_09_22_web_ui_2_claudestep, docs_superpowers_plans_2026_09_22_web_ui_2_commitsstep, docs_superpowers_plans_2026_09_22_web_ui_2_peoplestep, docs_superpowers_plans_2026_09_22_web_ui_2_projectsstep, docs_superpowers_plans_2026_09_22_web_ui_2_summarystep [EXTRACTED 1.00]
- **The management pages sharing ManagedPage's load/save/restart frame** — docs_superpowers_plans_2026_09_22_web_ui_3a_managedpage, docs_superpowers_plans_2026_09_22_web_ui_3a_settingspage, docs_superpowers_plans_2026_09_22_web_ui_3a_projectspage, docs_superpowers_plans_2026_09_22_web_ui_3a_peoplepage, docs_superpowers_plans_2026_09_22_web_ui_3a_logspage, docs_superpowers_plans_2026_09_22_web_ui_3a_usemanagedconfig [EXTRACTED 1.00]
- **The config's edit-in-place mechanism: node marks, targeted edits, versioned validated save** — docs_superpowers_plans_2026_09_22_web_ui_3a_configtext, docs_superpowers_plans_2026_09_22_web_ui_3a_configedit, docs_superpowers_plans_2026_09_22_web_ui_3a_manageapi [INFERRED 0.85]
- **OS service writers (systemd/launchd/Task Scheduler) driven by one Service.Kind axis** — src_main_java_dispatch_cli_service_kind, src_main_java_dispatch_cli_systemdservice_systemdservice, src_main_java_dispatch_cli_launchdservice_launchdservice, src_main_java_dispatch_cli_windowstaskservice_windowstaskservice, src_main_java_dispatch_cli_servicecommand_servicecommand [EXTRACTED 1.00]
- **Worker interface and its two implementations forming the run-execution seam (in-process and remote)** — src_main_java_dispatch_core_worker_worker, src_main_java_dispatch_core_jobrunner_jobrunner, src_main_java_dispatch_worker_remoteworkers_remoteworkers, src_main_java_dispatch_core_coordinator_coordinator [EXTRACTED 1.00]
- **Remote worker pairing and job-offer/lease protocol between the team machine and a member's computer** — src_main_java_dispatch_worker_remoteworkers_remoteworkers, src_main_java_dispatch_worker_workerapi_workerapi, src_main_java_dispatch_worker_workerclient_workerclient, src_main_java_dispatch_worker_workerloop_workerloop, src_main_java_dispatch_worker_workerkeys_workerkeys [EXTRACTED 1.00]

## Communities (188 total, 73 thin omitted)

### Community 0 - "Run Execution Tests"
Cohesion: 0.06
Nodes (13): Commit, Result, Result, Project, ReentrantLock, PreparedWorktree, WorktreeState, RunExecutorTest (+5 more)

### Community 1 - "Drafts and Task Splitting"
Cohesion: 0.05
Nodes (26): DraftChoice, ALREADY_CREATED, ALREADY_SPLIT, CANNOT_SPLIT, CHOOSE_PROJECT_FIRST, CREATED, EXPIRED, KEPT_WHOLE (+18 more)

### Community 2 - "Domain Records and Test Clock"
Cohesion: 0.11
Nodes (27): AgentOutcome, BUDGET_EXCEEDED, FAILED, SUCCEEDED, AgentResult, Config, ClaimedRun, InvalidPlanException (+19 more)

### Community 3 - "Worker Store Transactions"
Cohesion: 0.05
Nodes (10): java.sql.Connection, java.sql.PreparedStatement, Attachments, JoinRequest, JoinRequests, FunctionalInterface, RowMapper, Tx (+2 more)

### Community 4 - "Task and Run Model"
Cohesion: 0.04
Nodes (23): Prompts, Statistics, Attachment, FailureReason, AGENT, BUDGET, DELIVERY, INTERNAL (+15 more)

### Community 5 - "App Wiring and Workspaces"
Cohesion: 0.08
Nodes (17): java.nio.file.attribute.PosixFilePermission, java.util.concurrent.locks.ReentrantLock, java.util.regex.Pattern, Agent, ClaudeCodeAgent, Log, OwnerOnly, Redactor (+9 more)

### Community 6 - "JSON and Task Service Core"
Cohesion: 0.07
Nodes (26): com.fasterxml.jackson.databind.node.ArrayNode, com.fasterxml.jackson.databind.ObjectMapper, com.sun.net.httpserver.HttpServer, java.net.http.HttpClient, java.net.http.HttpRequest, java.net.URI, GroupReaction, COMPLETED (+18 more)

### Community 7 - "Worker API Test Fixture"
Cohesion: 0.07
Nodes (12): java.net.http.HttpResponse, org.junit.jupiter.params.ParameterizedTest, org.junit.jupiter.params.provider.ValueSource, Captured, ClientFactory, FunctionalInterface, Project, Raw (+4 more)

### Community 8 - "Mini App Shell and Routing"
Cohesion: 0.05
Nodes (56): Dispatch Mini App Design System, addProject(), Me, ProjectSummary, unlinkGroup(), AddProjectPage, App(), FieldEditPage (+48 more)

### Community 9 - "Agent Runs and Activity"
Cohesion: 0.05
Nodes (21): AgentActivity, AgentStartException, Override, ProcessHandle, RunHandle, RunRequest, Draft, DraftStatus (+13 more)

### Community 10 - "Init and Worker Init Commands"
Cohesion: 0.09
Nodes (10): InitCommand, Project, Workers, ServiceCommand, Terminal, ProjectInfo, Project, Project (+2 more)

### Community 11 - "Bot API and Database Tests"
Cohesion: 0.05
Nodes (8): org.junit.jupiter.api.Test, PlanTest, DatabaseTest, Sample, BotApiTest, PollerTest, TelegramNamesTest, FoldersTest

### Community 13 - "Task Service Collaborators"
Cohesion: 0.09
Nodes (10): java.util.function.LongConsumer, org.junit.jupiter.api.BeforeEach, Override, Limits, Member, Secrets, ActiveRuns, Projects (+2 more)

### Community 14 - "Management API Tests"
Cohesion: 0.07
Nodes (8): Builder, LeaseExpiredException, RejectedException, RevokedException, Setup, WorkerClient, ManageApiTest, WorkerClientTest

### Community 15 - "Update Handler Tests"
Cohesion: 0.12
Nodes (3): Membership, Group, UpdateHandlerTest

### Community 16 - "Management API Saving"
Cohesion: 0.10
Nodes (15): com.fasterxml.jackson.databind.JsonNode, CliException, PhaseSettings, ConfigView, GroupView, Project, Logs, ManageApi (+7 more)

### Community 17 - "Setup Wizard UI"
Cohesion: 0.07
Nodes (45): ADR-0021, answerPerson(), BotView, checkClaude(), checkToken(), chooseTeam(), FolderListing, getSetupState() (+37 more)

### Community 19 - "Frontend API Client"
Cohesion: 0.07
Nodes (44): ApiError, ConfigView, FolderEntry, get(), getConfig(), getLogs(), getMe(), GroupView (+36 more)

### Community 20 - "Telegram Message Renderer"
Cohesion: 0.15
Nodes (5): java.util.ResourceBundle, Button, Document, Rendered, Renderer

### Community 22 - "Outbox Message Kinds"
Cohesion: 0.04
Nodes (54): OutboxKind, ASSISTANT_REPLY, CANCEL_REFUSED, CORRECTION_QUEUED, CORRECTION_REFUSED, DRAFT_EXPIRED, DRAFT_PROMPT, EXECUTION_QUEUED (+46 more)

### Community 23 - "Design Specs"
Cohesion: 0.06
Nodes (50): ADR 0011: task details go to the requester privately (superseded in part by ADR 0020), ADR 0020: members see only the headline of each other's tasks, W-1 Privacy implementation plan (team workers), TaskService requester-only action rules (cancel/retry/followUp/statusPayload/history/timeline/statsPayload), Checks class: dispatch check's logic returning findings for CLI and web UI, Web UI milestone UI-1 implementation plan, UiServer/UiAuth implementation: loopback-only HTTP server with one-time login, Mini App milestone UI-3b implementation plan (+42 more)

### Community 24 - "Service Locations and Overview"
Cohesion: 0.09
Nodes (11): Service, Locations, RunCommand, SecretsFile, Finding, Overview, OverviewApi, ServiceView (+3 more)

### Community 25 - "Assistant Conversation"
Cohesion: 0.10
Nodes (5): Assistant, Spent, TurnFailed, AssistantHome, AssistantTest

### Community 27 - "OS Service Installers"
Cohesion: 0.10
Nodes (12): Override, LaunchdService, Commands, FunctionalInterface, Kind, DISPATCH, WORKER, Override (+4 more)

### Community 28 - "Task Commands"
Cohesion: 0.12
Nodes (9): CorrectResult, CORRECTED, EMPTY, NOT_ALLOWED, REFUSED, PlanAnswers, NewRun, NewTask (+1 more)

### Community 29 - "Mini App Home and Ticket Sheet"
Cohesion: 0.09
Nodes (38): Answer, answerQuestion(), approvePlan(), getTaskDetail(), listTasks(), PlanQuestionView, post(), rejectPlan() (+30 more)

### Community 32 - "Database and App Startup"
Cohesion: 0.09
Nodes (10): App, Scheduler, AttachmentSource, DraftExpiry, Scheduler, Signal, SplitFailed, Splitter (+2 more)

### Community 33 - "Update Handler Messages"
Cohesion: 0.13
Nodes (5): GroupOrigin, Command, Project, Mention, UpdateHandler

### Community 34 - "UI Package Dependencies"
Cohesion: 0.05
Nodes (40): @ant-design/icons, antd, jsdom, @playwright/test, react, react-dom, @testing-library/jest-dom, @testing-library/react (+32 more)

### Community 35 - "Secrets File Tests"
Cohesion: 0.10
Nodes (6): org.junit.jupiter.api.condition.DisabledOnOs, org.junit.jupiter.api.condition.EnabledOnOs, WorkerPair, SecretsFileTest, OwnerOnlyTest, WorkerCommandTest

### Community 36 - "Worker Pairing Keys"
Cohesion: 0.13
Nodes (6): Override, NewKey, WorkerKeys, TasksTest, WorkerKeysTest, Ruling: an unknown key and a revoked key answer with the identical 401 message

### Community 37 - "Mini App Task Pages"
Cohesion: 0.08
Nodes (29): cancelTask(), PlanView, retryTask(), TaskDetail, TaskState, taskTimeline(), Timeline, TasksPage (+21 more)

### Community 38 - "Project Forms UI"
Cohesion: 0.11
Nodes (32): editProject(), Effort, ManagedProject, Model, PhaseChoice, probeProject(), ProjectFields, ProjectView (+24 more)

### Community 39 - "Architecture Decisions"
Cohesion: 0.09
Nodes (38): Single Dispatch Process Per Team, Telegram Platform, Plain Java Without a Framework, Spring Boot (rejected default stack), SQLite for Task and Run State, MongoDB (rejected default stack), SQLite (embedded state store), claude-code-telegram (overwirehq project) (+30 more)

### Community 40 - "Claude Stream Parser"
Cohesion: 0.13
Nodes (5): ClaudeRun, Override, ProcessHandle, StreamParser, StreamParserTest

### Community 41 - "Status and History"
Cohesion: 0.17
Nodes (4): ActivityHandle, Override, ProcessHandle, StatusAndHistoryTest

### Community 42 - "Bot API Client"
Cohesion: 0.12
Nodes (4): Bot, Override, BotApi, BotCommand

### Community 43 - "Outbox Sender"
Cohesion: 0.13
Nodes (6): Message, Override, OutboxSender, Refs, TelegramException, RefsTest

### Community 44 - "Scripted Terminal Tests"
Cohesion: 0.15
Nodes (4): Override, ScriptedTerminal, Override, WorkerInitCommandTest

### Community 45 - "Remote Workers Tests"
Cohesion: 0.25
Nodes (4): Progress, Paired, Project, RemoteWorkersTest

### Community 46 - "Setup Writer"
Cohesion: 0.10
Nodes (8): Project, Advanced, Answers, Chat, Workers, Setup, ClaudeVersion, SetupTest

### Community 47 - "Setup API"
Cohesion: 0.14
Nodes (11): ConflictException, Updates, BotView, Candidate, Group, GroupFound, ServiceView, Person (+3 more)

### Community 50 - "Active Runs Registry"
Cohesion: 0.14
Nodes (9): ActiveRun, StopReason, CANCELLED, INTERRUPTED, TIMEOUT, ActiveRunsTest, CountingHandle, Override (+1 more)

### Community 53 - "Job Runner"
Cohesion: 0.15
Nodes (10): JobEvents, JobResult, Outcome, CANCELLED, FAILED, SUCCEEDED, Project, JobRunner (+2 more)

### Community 54 - "Config Edit in Place"
Cohesion: 0.18
Nodes (9): org.yaml.snakeyaml.nodes.Node, At, ConfigEdit, Item, Override, Key, Step, Value (+1 more)

### Community 55 - "Worker HTTP API"
Cohesion: 0.14
Nodes (4): Check, FunctionalInterface, RouteAction, WorkerApi

### Community 56 - "Mini App Theme and Palette"
Cohesion: 0.11
Nodes (23): Creative North Star: BotFather's own screens, The One Palette Rule, The Pass: full-width tickets waiting on the owner, The Rail: horizontally scrolling in-progress tickets, Served: compact stack of recent delivered/failed tickets, THESIS: Tasks are tickets on a rail (Pass metaphor), Product Principle: Telegram-native, MiniShell() (+15 more)

### Community 57 - "Run Coordinator"
Cohesion: 0.14
Nodes (7): Coordinator, Project, Project, RunExecutor (legacy monolithic executor, deleted in W-2), Worker, Project, Ruling: a Worker that throws is treated as an INTERNAL crash by the Coordinator

### Community 58 - "Group Linking"
Cohesion: 0.14
Nodes (8): GroupLinks, Prompt, Result, CONFIG_FAILED, DECLINED, LINKED, NOT_ALLOWED, STALE

### Community 59 - "Groups and Membership Rules"
Cohesion: 0.14
Nodes (3): Groups, Group, GroupsTest

### Community 61 - "Config Validation"
Cohesion: 0.13
Nodes (7): Reference, Agent, Delivery, Group, ConfigLoader, Delivery, Project

### Community 62 - "UI HTTP Server"
Cohesion: 0.19
Nodes (3): com.sun.net.httpserver.HttpExchange, Auth, UiServer

### Community 63 - "Interactive Terminal"
Cohesion: 0.22
Nodes (4): org.jline.reader.LineReader, org.jline.utils.AttributedStyle, Override, JLineTerminal

### Community 66 - "Worker Readiness"
Cohesion: 0.17
Nodes (6): Blocker, Check, Readiness, WorkersReadinessTest, Check, ReadinessTest

### Community 67 - "Worker Command"
Cohesion: 0.14
Nodes (6): java.nio.channels.FileChannel, Prepared, Result, Main, WorkerCommand, RunCommandTest

### Community 68 - "Mini App Tasks API"
Cohesion: 0.28
Nodes (4): ApiException, TasksApi, Caller, ObjectNode

### Community 69 - "Overview and Restart UI"
Cohesion: 0.14
Nodes (15): Finding, getOverview(), Overview, restartService(), ServiceView, OverviewPage, levelIcon, OverviewPage() (+7 more)

### Community 70 - "Database Row Mapping"
Cohesion: 0.23
Nodes (4): java.sql.ResultSet, Draft, Row, Run

### Community 71 - "TypeScript Config"
Cohesion: 0.09
Nodes (22): DOM, DOM.Iterable, ES2022, src, @testing-library/jest-dom, vite/client, vite.config.ts, vitest/globals (+14 more)

### Community 72 - "Project Add Command"
Cohesion: 0.16
Nodes (3): ProjectAddCommand, ProjectProbe, ProjectView

### Community 73 - "Job Runner Tests"
Cohesion: 0.21
Nodes (5): Override, Override, Project, JobRunnerTest, Recorder

### Community 76 - "Worker Sweeper"
Cohesion: 0.13
Nodes (5): Override, Override, Override, WorkerSweeper, Ruling: the worker sweeps idle worktrees by file age, not by task state

### Community 77 - "Health Checks"
Cohesion: 0.19
Nodes (9): Answer, DISPATCH, NONE, OTHER, Checks, MiniApp, Result, Workers (+1 more)

### Community 80 - "CLI Parsing"
Cohesion: 0.21
Nodes (10): Arguments, Check, Cli, Help, Init, Invocation, Run, WorkerInit (+2 more)

### Community 82 - "Service Spec Tests"
Cohesion: 0.21
Nodes (5): Spec, Override, Result, Recorder, ServiceTest

### Community 83 - "Worker Loop"
Cohesion: 0.20
Nodes (5): Job, Override, WorkerLoop, JobJsonTest, Ruling: Coordinator builds the prompt; Job carries only the finished string

### Community 86 - "Worker Checks"
Cohesion: 0.24
Nodes (5): CheckCommand, EnvironmentResult, Finding, WorkerChecks, WorkerConfig

### Community 89 - "Config Text Editing"
Cohesion: 0.24
Nodes (5): org.yaml.snakeyaml.nodes.MappingNode, org.yaml.snakeyaml.nodes.NodeTuple, org.yaml.snakeyaml.nodes.SequenceNode, ConfigException, ConfigText

### Community 90 - "Ask Command"
Cohesion: 0.28
Nodes (4): AskCommand, Ask, Answer, AskCommandTest

### Community 91 - "Plan Answers"
Cohesion: 0.13
Nodes (10): AnswerResult, ALREADY_ANSWERED, ANSWERED, EMPTY, NOT_ALLOWED, NOT_FOUND, NOT_REQUESTER, PROMPT_OPEN (+2 more)

### Community 93 - "Membership Requests"
Cohesion: 0.23
Nodes (5): JoinRequestResult, PENDING, RECENTLY_DENIED, REQUESTED, MembershipTest

### Community 96 - "Product and Security Docs"
Cohesion: 0.15
Nodes (17): ADR 0006: Read-only plan, then member approval, then execution, ADR 0007: One commit per run, push dispatch/<id>, draft PR, ADR 0024: Assistant: per-member Claude Code session, propose-only, Admin (domain term), Assistant (domain term), Group (domain term), Member (domain term), Project (domain term) (+9 more)

### Community 97 - "Team Config and Workers"
Cohesion: 0.17
Nodes (8): com.fasterxml.jackson.annotation.JsonCreator, MiniApp, Scheduler, Telegram, Workers, Worktrees, ConfigFile, Ruling: team mode triggers on more than one distinct member across all groups

### Community 98 - "Web UI Plan Classes"
Cohesion: 0.15
Nodes (17): ui/src/api.ts (post client + setup calls), dispatch.ui.ApiException, dispatch.ui.Folders (folder browser listing), ProjectAddCommand.Project record, ProjectProbe (opened up for SetupApi), SecretsFile (beside made public), ServiceCommand.specFor / runningJar, dispatch.ui.SetupApi (setup's HTTP steps) (+9 more)

### Community 99 - "UI Command"
Cohesion: 0.22
Nodes (4): Ui, Override, NoService, UiCommandTest

### Community 101 - "Approve and Create Results"
Cohesion: 0.11
Nodes (15): ApproveResult, APPROVED, NOT_ALLOWED, NOT_FOUND, NOT_REQUESTER, OPEN_QUESTIONS, STALE_PLAN, WRONG_STATE (+7 more)

### Community 102 - "Remote Workers"
Cohesion: 0.27
Nodes (4): Override, Offer, RemoteWorkers, Paired

### Community 103 - "E2E Test Harness"
Cohesion: 0.21
Nodes (10): config(), paths, PATHS_FILE, PORT, STATE_FILE, clone(), globalSetup(), killAndWait() (+2 more)

### Community 104 - "One-Time Link Auth"
Cohesion: 0.23
Nodes (3): java.security.SecureRandom, Override, UiAuth

### Community 107 - "Assistant Actions"
Cohesion: 0.27
Nodes (7): AssistantActions, Checked, Outcome, DONE, NOT_ALLOWED, STALE, USED

### Community 108 - "Telegram Usernames"
Cohesion: 0.18
Nodes (3): TelegramUsers, Timestamps, TelegramUsersTest

### Community 113 - "Advanced Init Tests"
Cohesion: 0.25
Nodes (4): InitCommandAdvancedTest, JsonNode, Override, NoService

### Community 115 - "Domain Glossary"
Cohesion: 0.24
Nodes (14): Correction (domain term), Delivery (domain term), Follow-up (domain term), Plan (domain term), Priority (domain term), Requester (domain term), Run (domain term), Task (domain term) (+6 more)

### Community 116 - "Overview API Tests"
Cohesion: 0.25
Nodes (3): Override, OverviewApiTest, StubService

### Community 117 - "Setup Wizard Plan"
Cohesion: 0.18
Nodes (13): App.tsx (Setup or Overview root), BotStep component (bot token check), ClaudeStep component (claude command check), CommitsStep component (commit author), PeopleStep component (confirm people, QR code), SetupPage component (setup shell), SummaryStep component (confirm and write), useAction hook (one call's busy/error state) (+5 more)

### Community 118 - "Config Text Lines"
Cohesion: 0.32
Nodes (3): org.yaml.snakeyaml.error.Mark, Insert, Lines

### Community 120 - "Process Tree Cleanup"
Cohesion: 0.26
Nodes (3): ProcessHandle, ProcessTrees, LocalAgents

### Community 122 - "Team Worker Plans"
Cohesion: 0.27
Nodes (12): ADR 0002: Plain Java 25, no framework, Maven wrapper, ADR 0008: Restarts fail active runs as interrupted, ADR 0013: Splitting on request via Haiku with no tools, ADR 0020: Task privacy: headline-only view; admin may cancel, ADR 0021: Worker: a member's tasks run on their own computer, ADR 0022: Worker readiness checks gate scheduling, systemd KillMode=mixed: Dispatch stops its own agents on SIGTERM, Worker key revocation mechanism (+4 more)

### Community 123 - "Setup Decisions"
Cohesion: 0.17
Nodes (12): ADR 0014: Personal instances (developer's own bot/machine), ADR 0016: One-line install, arrow-key dispatch init, background service, ADR 0018: dispatch ui setup/management in a browser, InitCommand (terminal front end to Setup), dispatch.cli.Setup (shared setup logic), Cli.Init(configFile, force, advanced) / --advanced switch, InitCommand advanced questions (per-project + Advanced step), Setup.Advanced record + defaults-diff render (+4 more)

### Community 124 - "Management Pages Plan"
Cohesion: 0.23
Nodes (12): FolderBrowser component, OverviewPage (modified, points to setup), ProjectsStep component (pick clones), ManagedPage component (shared load/save/restart frame), options.ts (MODELS/EFFORTS/PHASE_MODELS/PHASE_EFFORTS), PeoplePage component, ProjectForm component (shared add/edit project form), ProjectsPage component (+4 more)

### Community 127 - "Execution Architecture"
Cohesion: 0.18
Nodes (11): ADR 0005: OS user + scoped GitHub token as the hard boundary, ADR 0009: Plan mode / auto mode permissions; OS user is the hard boundary, ADR 0012: Groups, private tasks, priority queue, ADR 0017: Planning session and building session per task, Agent interface (RunHandle start(RunRequest)), Coordinator: drives one run into an immutable Job, JobRunner: in-process Worker (worktree, agent, delivery), RemoteWorkers: Worker over HTTP to a member's computer (+3 more)

### Community 129 - "Maven Wrapper"
Cohesion: 0.38
Nodes (8): mvnw script, clean(), die(), exec_maven(), hash_string(), set_java_home(), trim(), verbose()

### Community 130 - "Member Writer"
Cohesion: 0.20
Nodes (3): FunctionalInterface, MemberWriter, MemberWriterTest

### Community 133 - "Join Decisions"
Cohesion: 0.22
Nodes (8): JoinDecision, ALREADY_DECIDED, APPROVED, CONFIG_FAILED, DENIED, NOT_ADMIN, NOT_FOUND, UNKNOWN_GROUP

### Community 134 - "Split State"
Cohesion: 0.25
Nodes (6): SplitState, FAILED, KEPT, ONE_TOPIC, PROPOSED, SPLITTING

### Community 137 - "Member Preferences Page"
Cohesion: 0.31
Nodes (8): getPrefs(), GroupAck, savePrefs(), PrefsPage, Choices(), asApiError(), OPTIONS, PrefsPage()

### Community 138 - "Worker Config Loader"
Cohesion: 0.32
Nodes (4): com.fasterxml.jackson.dataformat.yaml.YAMLMapper, Project, WorkerConfigLoader, WorkerFile

### Community 140 - "Priority Results"
Cohesion: 0.25
Nodes (7): PriorityResult, CHANGED, FINISHED, NOT_ALLOWED, NOT_FOUND, NOT_REQUESTER, UNCHANGED

### Community 141 - "Reject Results"
Cohesion: 0.25
Nodes (7): RejectResult, NOT_ALLOWED, NOT_FOUND, NOT_REQUESTER, REJECTED, STALE_PLAN, WRONG_STATE

### Community 146 - "Follow-Up Results"
Cohesion: 0.29
Nodes (6): FollowUpResult, EMPTY, NOT_ALLOWED, NOT_FOUND, QUEUED, REFUSED

### Community 147 - "Group Ack Preference"
Cohesion: 0.33
Nodes (5): fromValue(), GroupAck, REACTION, REACTION_AND_LINE, SILENT

### Community 148 - "Run Causes"
Cohesion: 0.29
Nodes (6): RunCause, APPROVAL, CORRECTION, FOLLOW_UP, RETRY, TASK

### Community 150 - "Folder Browser"
Cohesion: 0.48
Nodes (3): Entry, Folders, Listing

### Community 151 - "Cancel Results"
Cohesion: 0.33
Nodes (5): CancelResult, CANCELLED, NOT_ALLOWED, NOT_FOUND, REFUSED

### Community 152 - "Retry Results"
Cohesion: 0.33
Nodes (5): RetryResult, NOT_ALLOWED, NOT_FOUND, REFUSED, RETRIED

### Community 153 - "Web UI Plans and Specs"
Cohesion: 0.50
Nodes (5): ADR 0015: Joining a shared bot via admin approval in Telegram, Web UI milestone UI-2 Implementation Plan, Web UI milestone UI-3a Implementation Plan, Mini App design spec (Advanced setup, Pages, Architecture, Testing, Milestones), Web UI design spec (Screens and data flow, Security, Error handling, Milestones)

### Community 154 - "Unix Installer"
Cohesion: 0.80
Nodes (4): download_release(), fail(), install.sh script, step()

### Community 155 - "Check Levels"
Cohesion: 0.40
Nodes (4): Level, FAIL, OK, WARN

### Community 157 - "Assistant Evals"
Cohesion: 0.70
Nodes (4): grade(), home(), main(), run()

### Community 159 - "Mini App Decisions"
Cohesion: 0.50
Nodes (4): ADR 0019: Telegram Mini App management from a phone, ADR 0023: Linking/unlinking a Telegram group to a project, Manage it from Telegram (Mini App), Mini App auth: Telegram signed launch data + admin list

### Community 163 - "CI and Release"
Cohesion: 0.67
Nodes (3): CI: test job (matrix ubuntu/macos/windows, mvnw verify, install.sh/install.ps1 smoke), CI: ui job (build web UI, upload artifact), Release: build jar+UI, verify tag matches pom.xml, gh release create

## Knowledge Gaps
- **354 isolated node(s):** `dispatch:dispatch`, `SUCCEEDED`, `FAILED`, `BUDGET_EXCEEDED`, `OK` (+349 more)
  These have ≤1 connection - possible missing edges or undocumented components. (Counts symbols only; 595 node(s) total have ≤1 connection when file, concept and rationale nodes are included.)
- **73 thin communities (<3 nodes) omitted from report** — run `graphify query` to explore isolated nodes.

## Suggested Questions
_Questions this graph is uniquely positioned to answer:_

- **Why does `W-4 Worker Setup Implementation Plan` connect `Team Worker Plans` to `Init and Worker Init Commands`, `Setup Decisions`?**
  _High betweenness centrality (0.034) - this node is a cross-community bridge._
- **Why does `Database` connect `Database and App Startup` to `Run Execution Tests`, `Drafts and Task Splitting`, `Domain Records and Test Clock`, `Worker Store Transactions`, `Task and Run Model`, `App Wiring and Workspaces`, `JSON and Task Service Core`, `Mini App Server`, `Worker API Test Fixture`, `Agent Runs and Activity`, `Bot API and Database Tests`, `Task Service Collaborators`, `Personal Group Tests`, `Run Claim Tests`, `Update Handler Tests`, `Task Lifecycle Tests`, `Assistant Conversation`, `Task Commands`, `Mention Handling Tests`, `Update Handler Messages`, `Worker Pairing Keys`, `Status and History`, `Outbox Sender`, `Remote Workers Tests`, `Outbox Sender Tests`, `Plan Questions Flow`, `Test Teardown Hooks`, `Worker HTTP API`, `Run Coordinator`, `Group Linking`, `Groups and Membership Rules`, `Worker Readiness`, `Mini App Tasks API`, `Tasks API Tests`, `Scheduler Tests`, `Assistant Actions Tests`, `Ask Command`, `Stats`, `Membership Requests`, `Mini App Server Tests`, `Remote Workers`, `Telegram Usernames`, `Coordinator Tests`, `Group Acknowledgement Tests`, `Group Acknowledgements`?**
  _High betweenness centrality (0.032) - this node is a cross-community bridge._
- **Why does `Tx` connect `Worker Store Transactions` to `Drafts and Task Splitting`, `Task and Run Model`, `App Wiring and Workspaces`, `JSON and Task Service Core`, `Split State`, `Agent Runs and Activity`, `Task Lifecycle Tests`, `Group Reply Handling Tests`, `Assistant Conversation`, `Task Commands`, `Mention Handling Tests`, `Update Handler Messages`, `Worker Pairing Keys`, `Status and History`, `Outbox Sender`, `Group Link Callbacks`, `Group Linking`, `Groups and Membership Rules`, `Mini App Tasks API`, `Scheduler Tests`, `Plan Answers`, `Stats`, `Membership Requests`, `Assistant Actions`, `Telegram Usernames`, `Group Acknowledgements`?**
  _High betweenness centrality (0.030) - this node is a cross-community bridge._
- **Are the 7 inferred relationships involving `Requester` (e.g. with `.task()` and `.version7DraftsSurviveTheRebuildThatAddsSplitting()`) actually correct?**
  _`Requester` has 7 INFERRED edges - model-reasoned connections that need verification._
- **What connects `dispatch:dispatch`, `SUCCEEDED`, `FAILED` to the rest of the system?**
  _354 weakly-connected nodes found - possible documentation gaps or missing edges._
- **Should `Run Execution Tests` be split into smaller, more focused modules?**
  _Cohesion score 0.05733005733005733 - nodes in this community are weakly interconnected._
- **Should `Drafts and Task Splitting` be split into smaller, more focused modules?**
  _Cohesion score 0.05054945054945055 - nodes in this community are weakly interconnected._