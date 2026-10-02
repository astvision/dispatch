# Plugin Picks (PP) Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** A Claude Code plan names which of three curated official plugins (frontend-design, playwright, context7) the task needs; the requester sees them in the plan, and the task's execution, fix and review runs load them from the official marketplace copy on the machine that runs them.

**Architecture:** The pickable list lives in the domain (`CuratedPlugins`), so `Plan.parse` can drop anything else. The picks stay inside the task's stored `planJson`. `Coordinator.executeJob` copies them onto the `Job` (left out of its JSON when empty, for older workers), and `JobRunner` puts them on each `RunRequest`. `ClaudeCodeAgent` hands them to `OwnerPlugins.resolve`, which finds each in `~/.claude/plugins/marketplaces/claude-plugins-official` through `OfficialPlugins` and merges their directories and MCP servers with the owner's, in the one `--mcp-config`.

**Tech Stack:** Java 21, Jackson, JUnit 5, Maven (`./mvnw`); React + TypeScript + Vite + Vitest for the UI (`ui/`).

**Spec:** `docs/superpowers/specs/2026-10-02-plugin-picks-design.md` (approved, ddfcc05).

## Global Constraints

- The curated list is exactly `frontend-design`, `playwright`, `context7`, all from marketplace `claude-plugins-official`.
- Nothing is installed: no `claude plugin` command runs for a pick, and nothing is written under `~/.claude`.
- `--strict-mcp-config` stays on every Claude Code run; a pick's servers go into the same single `--mcp-config` as the owner's, named `plugin_<name>_<server>`.
- A pick this machine cannot find is skipped and logged `agent.plugin_skipped` (WARN); the run goes on. A name outside the list in a plan is dropped and logged `plan.plugin_dropped` (INFO).
- Plan runs never load picks; only EXECUTE (including verify-loop fixes) and REVIEW runs do.
- A job without picks must serialize exactly as before (no `"plugins"` key), so an older worker (`FAIL_ON_UNKNOWN_PROPERTIES`) still reads it.
- Telegram messages are Mongolian only (`messages_mn.properties`); the desk UI has `en.ts` and `mn.ts`.
- Commit subjects in this repo are one plain sentence saying what is now true (see `git log`), and every commit ends with:
  ```
  Co-Authored-By: Claude Opus 5.5 (1M context) <noreply@anthropic.com>
  Claude-Session: https://claude.ai/code/session_01XY6qLAmFtZyEU8pL3Mjn2W
  ```
- The repository is public: test fixtures use fake values only.

## Review Focus

1. **A picked name in another form** (`" Frontend-Design "`, `playwright@claude-plugins-official`): a person expects it to count as the listed plugin, not be dropped. Pinned in Task 1.
2. **A verify-loop fix run** (a resumed EXECUTE after failing tests): it is part of the execution and must load the same picks as the first execution run and the reviewer. Pinned in Task 4.
3. **A re-plan after a correction** of a plan that picked plugins: the planning run must still load none. Pinned in Task 4.
4. **Upstream marketplace drift**, where a curated name's entry gains a non-path `source` (git or URL) or its directory moves: the run must go on without it rather than fail or fetch. Pinned in Task 5.
5. **A machine whose `OwnerPlugins` has no home** (`OwnerPlugins.NONE`, the legacy `Agents.create` path): a job with picks must not crash the run. Pinned in Task 5.

---

### Task 1: The curated list and a plan's `plugins` field

**Files:**
- Create: `src/main/java/dispatch/domain/CuratedPlugins.java`
- Modify: `src/main/java/dispatch/domain/Plan.java`
- Modify: `src/main/resources/plan-schema.json`
- Test: `src/test/java/dispatch/domain/PlanTest.java`

**Interfaces:**
- Produces: `CuratedPlugins.MARKETPLACE` (`"claude-plugins-official"`), `CuratedPlugins.Entry(String name, String when)`, `CuratedPlugins.ALL` (`List<Entry>`), `CuratedPlugins.match(String) -> Optional<String>`; `Plan.plugins()` (`List<String>`) and the 7-argument constructor `Plan(String understanding, List<String> findings, List<String> steps, List<String> risks, List<PlanQuestion> questionItems, List<PlanDecision> decisions, List<String> plugins)`. The 5- and 6-argument constructors stay.

- [ ] **Step 1: Write the failing tests**

Add to `PlanTest` (imports: `com.fasterxml.jackson.databind.JsonNode`, `dispatch.Json`, `dispatch.agent.Schemas`):

```java
    @Test
    void picksKeepListedPluginsInTheirListedFormAndDropTheRest() {
        Plan plan = Plan.parse("""
                {"understanding":"u","findings":[],"steps":["s"],"risks":[],"questions":[],"decisions":[],
                 "plugins":[" Frontend-Design ","playwright@claude-plugins-official","telegram","frontend-design",7]}""");

        assertEquals(List.of("frontend-design", "playwright"), plan.plugins());
    }

    @Test
    void aPlanStoredBeforePicksHasNone() {
        Plan plan = Plan.parse("{\"understanding\":\"u\",\"findings\":[],\"steps\":[\"s\"],\"risks\":[],\"questions\":[]}");

        assertEquals(List.of(), plan.plugins());
    }

    @Test
    void picksThatAreNotAnArrayAreInvalid() {
        assertThrows(InvalidPlanException.class, () -> Plan.parse(
                "{\"understanding\":\"u\",\"findings\":[],\"steps\":[\"s\"],\"risks\":[],\"questions\":[],\"plugins\":\"playwright\"}"));
    }

    @Test
    void picksSurviveTheStoredJson() {
        Plan plan = new Plan("u", List.of(), List.of("s"), List.of(), List.of(), List.of(), List.of("context7"));

        assertEquals(plan, Plan.parse(plan.toJson()));
    }

    @Test
    void theSchemaRequiresPicksSoStrictOutputModesAcceptIt() {
        JsonNode schema = Json.read(Schemas.PLAN);

        assertTrue(schema.path("required").toString().contains("\"plugins\""), schema.toString());
        assertEquals("array", schema.path("properties").path("plugins").path("type").asText());
    }
```

- [ ] **Step 2: Run them to verify they fail**

Run: `./mvnw -q test -Dtest=PlanTest`
Expected: compilation FAILS: `plugins()` and the 7-argument constructor do not exist.

- [ ] **Step 3: Create `CuratedPlugins`**

```java
package dispatch.domain;

import java.util.List;
import java.util.Locale;
import java.util.Optional;

/**
 * The official plugins a plan may pick for its execution (spec: plugin picks, ADR 0037): hosted in Anthropic's
 * claude-plugins-official marketplace, and fit for an unattended, sandboxed run. None has a hook that waits for a person
 * or calls a model of its own, none needs credentials, and none needs a tool Dispatch's runs lack.
 */
public final class CuratedPlugins {

    /** The marketplace every entry comes from, as Claude Code names it. */
    public static final String MARKETPLACE = "claude-plugins-official";

    /** @param when when a plan should pick it, as the plan prompt says it */
    public record Entry(String name, String when) {
    }

    public static final List<Entry> ALL = List.of(
            new Entry("frontend-design", "the task builds or reshapes a user interface"),
            new Entry("playwright", "the change should be checked in a real browser"),
            new Entry("context7", "the task depends on a library's current API or configuration"));

    private CuratedPlugins() {
    }

    /**
     * The listed name {@code given} means, ignoring case, surrounding spaces and an {@code @claude-plugins-official}
     * suffix; empty when it names nothing on the list.
     */
    public static Optional<String> match(String given) {
        String name = given.strip().toLowerCase(Locale.ROOT);
        String suffix = "@" + MARKETPLACE;
        String bare = name.endsWith(suffix) ? name.substring(0, name.length() - suffix.length()) : name;
        return ALL.stream().map(Entry::name).filter(bare::equals).findFirst();
    }
}
```

- [ ] **Step 4: Add `plugins` to `Plan`**

In `Plan.java`:

1. Add the imports `dispatch.Log` and `java.util.Optional`.
2. Extend the class comment's last sentence to: "Decisions came later still, and plugin picks after them, so a stored plan may have neither."
3. Change the record header, `FIELDS`, the compact constructor and the 6-argument constructor:

```java
public record Plan(String understanding, List<String> findings, List<String> steps, List<String> risks,
                   List<PlanQuestion> questionItems, List<PlanDecision> decisions, List<String> plugins) {

    public static final int MAX_OPTIONS = 4;
    public static final int MAX_OPTION_LENGTH = 40;
    public static final int MAX_ALTERNATIVES = 3;
    private static final Set<String> FIELDS = Set.of("understanding", "findings", "steps", "risks", "questions", "decisions",
            "plugins");

    public Plan {
        findings = List.copyOf(findings);
        steps = List.copyOf(steps);
        risks = List.copyOf(risks);
        questionItems = List.copyOf(questionItems);
        decisions = List.copyOf(decisions);
        plugins = List.copyOf(plugins);
    }

    /** A plan without plugin picks. */
    public Plan(String understanding, List<String> findings, List<String> steps, List<String> risks,
                List<PlanQuestion> questionItems, List<PlanDecision> decisions) {
        this(understanding, findings, steps, risks, questionItems, decisions, List.of());
    }
```

The existing 5-argument constructor stays as it is: it calls the 6-argument one.

4. In `parse`, change the `return` to:

```java
        return new Plan(understandingNode.asText(), texts(node, "findings"), steps, texts(node, "risks"), questions,
                decisions(node), plugins(node));
```

5. In `toJson`, before `return json.toString();`:

```java
        plugins.forEach(json.putArray("plugins")::add);
```

6. Add after `decisions(JsonNode)`:

```java
    /**
     * Absent in a plan stored before plugin picks. A name outside {@link CuratedPlugins} is dropped rather than the plan
     * rejected: the agent only suggested it, and the plan works without it.
     */
    private static List<String> plugins(JsonNode plan) {
        JsonNode array = plan.get("plugins");
        if (array == null) {
            return List.of();
        }
        if (!array.isArray()) {
            throw new InvalidPlanException("plan field 'plugins' must be an array");
        }
        List<String> plugins = new ArrayList<>();
        for (JsonNode item : array) {
            Optional<String> name = CuratedPlugins.match(item.asText(""));
            if (name.isEmpty()) {
                Log.info("plan.plugin_dropped", "plugin", item.toString());
            } else if (!plugins.contains(name.get())) {
                plugins.add(name.get());
            }
        }
        return plugins;
    }
```

- [ ] **Step 5: Add `plugins` to the schema**

In `src/main/resources/plan-schema.json`, change `required` to:

```json
  "required": ["understanding", "findings", "steps", "risks", "questions", "decisions", "plugins"],
```

and add after the `decisions` property (mind the comma after its closing brace):

```json
    "plugins": {
      "type": "array",
      "items": {"type": "string"},
      "description": "Plugins from the list in the prompt that the execution needs; empty if none"
    }
```

- [ ] **Step 6: Run the tests to verify they pass**

Run: `./mvnw -q test -Dtest='PlanTest,CodexAgentTest,GeminiAgentTest'`
Expected: PASS. The Codex and Gemini agents pass `plan-schema.json` too.

- [ ] **Step 7: Commit**

```bash
git add src/main/java/dispatch/domain/CuratedPlugins.java src/main/java/dispatch/domain/Plan.java \
        src/main/resources/plan-schema.json src/test/java/dispatch/domain/PlanTest.java
git commit -F - <<'EOF'
A plan names the official plugins its execution needs from a short list Dispatch ships, and any other name is dropped

Co-Authored-By: Claude Opus 5.5 (1M context) <noreply@anthropic.com>
Claude-Session: https://claude.ai/code/session_01XY6qLAmFtZyEU8pL3Mjn2W
EOF
```

---

### Task 2: A Claude Code plan run is offered the list

**Files:**
- Modify: `src/main/java/dispatch/core/Prompts.java`
- Modify: `src/main/java/dispatch/core/JobRunner.java` (`request`, near line 472)
- Test: `src/test/java/dispatch/core/PromptsTest.java`, `src/test/java/dispatch/core/JobRunnerTest.java`

**Interfaces:**
- Consumes: `CuratedPlugins.ALL` (Task 1).
- Produces: `Prompts.PLUGIN_NOTE` (package-private `static final String`); `JobRunner.pluginNote(Job)` (package-private static, returns `""` or `Prompts.PLUGIN_NOTE`).

- [ ] **Step 1: Write the failing tests**

In `PromptsTest` (import `dispatch.domain.CuratedPlugins`):

```java
    @Test
    void thePluginNoteNamesEveryCuratedPluginAndWhenToPickIt() {
        for (CuratedPlugins.Entry entry : CuratedPlugins.ALL) {
            assertTrue(Prompts.PLUGIN_NOTE.contains("- " + entry.name() + ": when " + entry.when()), Prompts.PLUGIN_NOTE);
        }
        assertTrue(Prompts.PLUGIN_NOTE.contains("plugins field"), Prompts.PLUGIN_NOTE);
    }
```

In `JobRunnerTest`:

```java
    @Test
    void aClaudeCodePlanIsOfferedTheCuratedPluginsBeforeItsSkillNote() throws Exception {
        SkillsPlugin.install(workspaces.skillsPluginDir());

        runner.run(withSkills(job(RunKind.PLAN, 1, "Plan this: fix the login timeout", null, null, null)), events, control);

        String prompt = Files.readString(Path.of(events.worktree).resolve("fake-claude.prompt"));
        assertTrue(prompt.contains("- frontend-design: when the task builds or reshapes a user interface"), prompt);
        assertTrue(prompt.endsWith(Prompts.SkillNote.PLAN.text()), "the skill note stays last: " + prompt);
    }

    @Test
    void onlyAClaudeCodePlanIsOfferedPlugins() {
        Job plan = job(RunKind.PLAN, 1, "Plan this", null, null, null);
        Job.Project p = plan.project();
        Job codexPlan = new Job(plan.taskId(), plan.seq(), plan.kind(), new Job.Project(p.name(), p.repo(), p.path(),
                p.baseBranch(), "codex", p.copyFiles()), plan.baseBranch(), plan.baseSha(), plan.worktree(), plan.prUrl(),
                plan.sessionId(), plan.resume(), plan.prompt(), plan.model(), plan.effort(), plan.timeoutMillis(),
                plan.budgetUsd(), plan.attachments(), plan.commitSubject(), plan.commitTrailers(), plan.deliverySummary());

        assertEquals(Prompts.PLUGIN_NOTE, JobRunner.pluginNote(plan));
        assertEquals("", JobRunner.pluginNote(codexPlan), "Codex loads no plugins, so it is offered none");
        assertEquals("", JobRunner.pluginNote(job(RunKind.EXECUTE, 2, "Implement", null, null, null)), "only a plan picks");
    }
```

- [ ] **Step 2: Run them to verify they fail**

Run: `./mvnw -q test -Dtest='PromptsTest,JobRunnerTest'`
Expected: compilation FAILS: `Prompts.PLUGIN_NOTE` and `JobRunner.pluginNote` do not exist.

- [ ] **Step 3: Add the note to `Prompts`**

In `Prompts.java`, next to `enum SkillNote` (import `dispatch.domain.CuratedPlugins`):

```java
    /** After a Claude Code plan prompt: the official plugins its plan may pick for the execution (spec: plugin picks). */
    static final String PLUGIN_NOTE = pluginNote();

    private static String pluginNote() {
        StringBuilder note = new StringBuilder("\nPlugins you may pick for the execution, in the plan's plugins field. Pick "
                + "only what this task needs, and none when nothing fits:\n");
        for (CuratedPlugins.Entry entry : CuratedPlugins.ALL) {
            note.append("- ").append(entry.name()).append(": when ").append(entry.when()).append('\n');
        }
        return note.toString();
    }
```

- [ ] **Step 4: Append it in `JobRunner.request`**

Replace `request` and add `pluginNote` beside `skillNote`:

```java
    private RunRequest request(Job job, Path worktree, TaskFiles files, List<Path> plugins) {
        return new RunRequest(job.kind(), worktree,
                job.prompt() + files.note() + pluginNote(job) + skillNote(job.kind(), plugins),
                job.sessionId(), job.resume(), files.dirs(), job.budgetUsd(), job.model(), job.effort(),
                workspaces.runLogBase(job.taskId(), job.seq()), Map.of(), plugins);
    }

    /**
     * The plugins a Claude Code plan may pick (spec: plugin picks), before the skill note; the other agents load no
     * plugins, so they are offered none. Package-private: JobRunnerTest reaches it directly.
     */
    static String pluginNote(Job job) {
        return job.kind() == RunKind.PLAN && job.project().agent().equals("claude-code") ? Prompts.PLUGIN_NOTE : "";
    }
```

- [ ] **Step 5: Run the tests to verify they pass**

Run: `./mvnw -q test -Dtest='PromptsTest,JobRunnerTest'`
Expected: PASS, including the existing `aPlanWithSkillsStartsClaudeWithThePluginAndThePlanNote`.

- [ ] **Step 6: Commit**

```bash
git add src/main/java/dispatch/core/Prompts.java src/main/java/dispatch/core/JobRunner.java \
        src/test/java/dispatch/core/PromptsTest.java src/test/java/dispatch/core/JobRunnerTest.java
git commit -F - <<'EOF'
A Claude Code plan run is told which official plugins it may pick and when each fits

Co-Authored-By: Claude Opus 5.5 (1M context) <noreply@anthropic.com>
Claude-Session: https://claude.ai/code/session_01XY6qLAmFtZyEU8pL3Mjn2W
EOF
```

---

### Task 3: The requester sees the picks

**Files:**
- Modify: `src/main/java/dispatch/telegram/Renderer.java` (`planHtml`, near line 653)
- Modify: `src/main/resources/messages_mn.properties`
- Modify: `src/main/java/dispatch/core/TaskService.java` (`currentPlan`, near line 870)
- Modify: `ui/src/api.ts` (`PlanView`), `ui/src/mini/TicketSheet.tsx`, `ui/src/desktop/tasks/TaskView.tsx`, `ui/src/i18n/en.ts`, `ui/src/i18n/mn.ts`, `ui/src/mini/fixtures.ts`, `ui/src/desktop/tasks/TaskView.test.tsx`
- Test: `src/test/java/dispatch/telegram/RendererTest.java`, `src/test/java/dispatch/ui/TasksApiTest.java`, `ui/src/desktop/tasks/TaskView.test.tsx`

**Interfaces:**
- Consumes: `Plan.plugins()` and the `"plugins"` key in `Plan.toJson()` (Task 1).
- Produces: message key `plan.plugins`; the Mini App plan payload field `plugins` (array of strings, always present); the TS field `PlanView.plugins: string[]`.

- [ ] **Step 1: Write the failing Java tests**

In `RendererTest`:

```java
    @Test
    void thePlansPicksShowAsOneLineInItsSummaryAndItsDetails() {
        ObjectNode payload = planPayload(List.of("Do it"), List.of());
        ((ObjectNode) payload.get("plan")).putArray("plugins").add("frontend-design").add("playwright");

        String summary = renderer.render(OutboxKind.PLAN_READY, payload).html();
        String details = renderer.render(OutboxKind.PLAN_READY, payload.deepCopy().put("view", "details")).html();

        assertTrue(summary.contains("🧩 Плагин: frontend-design, playwright"), summary);
        assertTrue(details.contains("🧩 Плагин: frontend-design, playwright"), details);
    }

    @Test
    void aPlanWithoutPicksHasNoPluginLine() {
        String html = renderer.render(OutboxKind.PLAN_READY, planPayload(List.of("Do it"), List.of())).html();

        assertFalse(html.contains("Плагин"), html);
    }
```

In `TasksApiTest` (`Plan` is already imported there):

```java
    @Test
    void theRequestersPlanShowsItsPicks() {
        long taskId = planned(ALI, new Plan("Make the timeout configurable", List.of(), List.of("Read auth.timeout"),
                List.of(), List.of(), List.of(), List.of("playwright")));

        JsonNode plan = api.detail(ALI_CALLER, Json.object().put("taskId", taskId)).path("plan");

        assertEquals("[\"playwright\"]", plan.path("plugins").toString());
    }
```

- [ ] **Step 2: Run them to verify they fail**

Run: `./mvnw -q test -Dtest='RendererTest,TasksApiTest'`
Expected: FAIL: no `🧩 Плагин` line, and `plugins` missing from the payload.

- [ ] **Step 3: Render the line**

In `messages_mn.properties`, after `plan.decisionLine=…`:

```properties
plan.plugins=🧩 Плагин: {0}
```

In `Renderer.planHtml`, change the `details` case and the `default` case:

```java
            case "details" -> {
                foldedSection(html, "plan.stepsSection", plan.path("steps"), true);
                foldedSection(html, "plan.findingsSection", plan.path("findings"), false);
                htmlSection(html, "plan.risks", plan.path("risks"), false);
                decisionLines(html, plan.path("decisions"));
                pluginLine(html, plan.path("plugins"));
            }
```

```java
            default -> {
                decisionLines(html, plan.path("decisions"));
                pluginLine(html, plan.path("plugins"));
            }
```

and add after `decisionLines`:

```java
    /** The official plugins the execution will load (spec: plugin picks), on one line; nothing when the plan picked none. */
    private void pluginLine(StringBuilder html, JsonNode plugins) {
        if (plugins.isEmpty()) {
            return;
        }
        List<String> names = new ArrayList<>();
        plugins.forEach(name -> names.add(escape(name.asText())));
        html.append('\n').append(format("plan.plugins", String.join(", ", names))).append('\n');
    }
```

- [ ] **Step 4: Put the picks in the Mini App payload**

In `TaskService.currentPlan`, after the `findings` line:

```java
        plan.plugins().forEach(payload.putArray("plugins")::add);
```

- [ ] **Step 5: Run the Java tests to verify they pass**

Run: `./mvnw -q test -Dtest='RendererTest,TasksApiTest'`
Expected: PASS.

- [ ] **Step 6: Write the failing UI test**

In `ui/src/desktop/tasks/TaskView.test.tsx`, the `waiting` fixture's plan (line 25) becomes
`risks: [], findings: [], questions: [], plugins: [] },`. Then add after the first test:

```tsx
test("a plan's picked plugins are listed under it", async () => {
  vi.mocked(api.getTaskDetail).mockResolvedValue({ ...waiting, plan: { ...waiting.plan!, plugins: ["playwright"] } });

  render(<TaskView taskId={14} />);

  expect(await screen.findByText("playwright")).toBeInTheDocument();
  expect(screen.getByText("Plugins")).toBeInTheDocument();
});
```

- [ ] **Step 7: Run it to verify it fails**

Run: `cd ui && npx vitest run src/desktop/tasks/TaskView.test.tsx`
Expected: FAIL: the type has no `plugins`, or the text is not found.

- [ ] **Step 8: Show the picks in both UI screens**

`ui/src/api.ts`, in `PlanView` after `findings`:

```ts
  /** The official plugins the execution loads (spec: plugin picks); empty when the plan picked none. */
  plugins: string[];
```

`ui/src/mini/TicketSheet.tsx`, after `<PlanList title="Эрсдэл" items={plan.risks} />`:

```tsx
            <PlanList title="Плагин" items={plan.plugins} />
```

`ui/src/desktop/tasks/TaskView.tsx`, after the risks block (the `{plan.risks.length > 0 && ( … )}` that ends before `</section>`):

```tsx
          {plan.plugins.length > 0 && (
            <>
              <Typography.Text type="secondary" className="task-label">{t("tasks.plugins")}</Typography.Text>
              <ul className="task-steps">{plan.plugins.map((plugin) => <li key={plugin}>{plugin}</li>)}</ul>
            </>
          )}
```

`ui/src/i18n/en.ts`, after `"tasks.risks": "Risks",`: `"tasks.plugins": "Plugins",`
`ui/src/i18n/mn.ts`, after `"tasks.risks": "Эрсдэл",`: `"tasks.plugins": "Плагин",`
`ui/src/mini/fixtures.ts`, in `planWithQuestions` after `findings: [],`: `plugins: [],`

- [ ] **Step 9: Run the UI checks to verify they pass**

Run: `cd ui && npm run typecheck && npm test`
Expected: PASS. `tsc` names any other `PlanView` literal that still lacks `plugins`: add `plugins: []` there.

- [ ] **Step 10: Commit**

```bash
git add src/main/java/dispatch/telegram/Renderer.java src/main/resources/messages_mn.properties \
        src/main/java/dispatch/core/TaskService.java src/test/java/dispatch/telegram/RendererTest.java \
        src/test/java/dispatch/ui/TasksApiTest.java ui/src
git commit -F - <<'EOF'
The plan message and the Mini App show the plugins a plan picked, on one line

Co-Authored-By: Claude Opus 5.5 (1M context) <noreply@anthropic.com>
Claude-Session: https://claude.ai/code/session_01XY6qLAmFtZyEU8pL3Mjn2W
EOF
```

---

### Task 4: The picks travel from the plan to every execution and review run

**Files:**
- Modify: `src/main/java/dispatch/core/Job.java`
- Modify: `src/main/java/dispatch/agent/RunRequest.java`
- Modify: `src/main/java/dispatch/core/Coordinator.java` (`planJob`, `executeJob`, `deliverJob`, `newJob`)
- Modify: `src/main/java/dispatch/worker/WorkerLoop.java` (`withLocalClone`)
- Modify: `src/main/java/dispatch/core/JobRunner.java` (`request`, `loopAgents`)
- Test: `src/test/java/dispatch/core/JobJsonTest.java`, `src/test/java/dispatch/core/CoordinatorTest.java`, `src/test/java/dispatch/worker/WorkerLoopTest.java`, `src/test/java/dispatch/core/JobRunnerTest.java`

**Interfaces:**
- Consumes: `Plan.plugins()` (Task 1).
- Produces: `Job.plugins()` (record component, `List<String>`, null when none) and `Job.picks()` (`@JsonIgnore`, never null); the 23-argument `Job` constructor ending `…, String expectedHead, List<String> plugins`, with the 22-argument one kept; `RunRequest.picks()` (`List<String>`, never null) and the 13-argument `RunRequest` constructor ending `…, List<Path> pluginDirs, List<String> picks`, with the 12-argument one kept.

- [ ] **Step 1: Write the failing tests**

`JobJsonTest` (add the static import `assertFalse` and the imports `java.util.UUID` and `java.math.BigDecimal` where missing):

```java
    @Test
    void aJobsPicksSurviveJsonAndAJobWithoutPicksLeavesTheFieldOutForOlderWorkers() throws Exception {
        Job.Project project = new Job.Project("alm", "git@github.com:acme/alm.git", null, "main", "claude-code", List.of());
        UUID session = UUID.fromString("11111111-2222-3333-4444-555555555555");
        Job picked = new Job(7, 2, RunKind.EXECUTE, project, "main", "6f3030a", "/w/7", null, session, false, "Implement",
                null, null, 1000L, new BigDecimal("2"), List.of(), "dispatch #7: x", List.of(), null, null, null, null,
                List.of("playwright"));
        Job none = new Job(7, 2, RunKind.EXECUTE, project, "main", "6f3030a", "/w/7", null, session, false, "Implement",
                null, null, 1000L, new BigDecimal("2"), List.of(), "dispatch #7: x", List.of(), null, null, null, null,
                List.of());

        assertEquals(picked, Json.MAPPER.readValue(Json.write(picked), Job.class));
        assertEquals(List.of("playwright"), picked.picks());
        assertFalse(Json.write(none).contains("\"plugins\""), Json.write(none));
        assertEquals(List.of(), none.picks());
    }
```

`CoordinatorTest`: add the constant and the helper overload, then the tests (`BOLD` is the requester in this class's tasks; import `java.util.OptionalInt` if missing):

```java
    private static final String PICKING_PLAN = new Plan("The page needs a layout", List.of(), List.of("Build the page"),
            List.of(), List.of(), List.of(), List.of("frontend-design")).toJson();
```

```java
    private Job approvedExecutionJob(Config.Project project) {
        return approvedExecutionJob(project, PLAN_JSON);
    }

    private Job approvedExecutionJob(Config.Project project, String planJson) {
        long id = queue("Fix the login timeout");
        Projects configured = projects(List.of(project));
        coordinator(configured, remember(JobResult.succeeded(agentResult(planJson)))).execute(claim());
        db.transaction(tx -> tasks.commands().run(tx, BOLD, new TaskCommand.Approve(id, 1)));
        coordinator(configured, remember(JobResult.succeeded(agentResult(null)))).execute(claim());
        return given.get();
    }
```

(This replaces the existing one-argument `approvedExecutionJob`.)

```java
    @Test
    void anExecutionJobCarriesThePlansPicks() {
        Job job = approvedExecutionJob(ALM, PICKING_PLAN);

        assertEquals(List.of("frontend-design"), job.picks());
        assertTrue(Json.write(job).contains("\"plugins\":[\"frontend-design\"]"), Json.write(job));
    }

    @Test
    void anExecutionWithoutPicksSendsNoPluginsFieldAnOlderWorkerWouldReject() {
        Job job = approvedExecutionJob(ALM);

        assertEquals(List.of(), job.picks());
        assertFalse(Json.write(job).contains("\"plugins\""), Json.write(job));
    }

    @Test
    void aCorrectionOfAPickingPlanPlansWithoutLoadingThePicks() {
        long id = queue("Add a settings page");
        Projects configured = projects(List.of(ALM));
        coordinator(configured, remember(JobResult.succeeded(agentResult(PICKING_PLAN)))).execute(claim());
        db.transaction(tx -> tasks.commands().run(tx, BOLD, new TaskCommand.Correct(id, OptionalInt.of(1), "Reuse the form")));

        coordinator(configured, remember(JobResult.succeeded(agentResult(PICKING_PLAN)))).execute(claim());

        assertEquals(RunKind.PLAN, given.get().kind());
        assertEquals(List.of(), given.get().picks(), "a plan run chooses picks; it never loads them");
    }
```

`WorkerLoopTest`:

```java
    @Test
    void workerKeepsTheJobsPicks() throws Exception {
        WorkerLoop loop = idleLoop("ann-laptop", repos.repo("alm"));
        Job.Project project = new Job.Project("alm", "git@github.com:acme/alm.git", null, "main", "claude-code", List.of());
        Job sent = executeJob(project, null);
        Job picked = new Job(sent.taskId(), sent.seq(), sent.kind(), project, sent.baseBranch(), sent.baseSha(), sent.worktree(),
                sent.prUrl(), sent.sessionId(), sent.resume(), sent.prompt(), sent.model(), sent.effort(), sent.timeoutMillis(),
                sent.budgetUsd(), sent.attachments(), sent.commitSubject(), sent.commitTrailers(), sent.deliverySummary(),
                sent.branch(), sent.reviewPrompt(), sent.expectedHead(), List.of("playwright"));

        assertEquals(List.of("playwright"), loop.withLocalClone(picked).orElseThrow().picks());
    }
```

`JobRunnerTest`: add the field beside `guarded`:

```java
    /** The plugin picks {@link #executeWithLoop} sends with its job, as a team machine does for a plan that picked some. */
    private List<String> picks = List.of();
```

In `executeWithLoop`, the `new Job(...)` call gains `picks` as a last argument after `guarded ? events.baseSha : null`. Then:

```java
    @Test
    void theExecutionItsFixAndItsReviewerAllCarryThePicks() {
        picks = List.of("playwright");
        answers.put(RunKind.EXECUTE, answer(null, "1.00"));
        answers.put(RunKind.REVIEW, answer("{\"verdict\":\"ok\",\"findings\":[]}", "0.20"));
        java.util.concurrent.atomic.AtomicInteger testRuns = new java.util.concurrent.atomic.AtomicInteger();

        JobResult result = executeWithLoop(true, (command, workdir, log, timeout, stop, started) ->
                new TestRunner.TestRun(testRuns.getAndIncrement() == 0 ? 1 : 0, false, false, "FooTest failed"));

        assertEquals(JobResult.Outcome.SUCCEEDED, result.outcome(), result.failureDetail());
        assertEquals(List.of(RunKind.EXECUTE, RunKind.EXECUTE, RunKind.REVIEW), agentKindsStarted);
        for (RunRequest request : loopRequests) {
            assertEquals(List.of("playwright"), request.picks(), request.kind() + " resume=" + request.resume());
        }
    }
```

- [ ] **Step 2: Run them to verify they fail**

Run: `./mvnw -q test -Dtest='JobJsonTest,CoordinatorTest,WorkerLoopTest,JobRunnerTest'`
Expected: compilation FAILS: the 23-argument `Job` constructor, `Job.picks()` and `RunRequest.picks()` do not exist.

- [ ] **Step 3: Add `plugins` to `Job`**

In the class comment, after the `expectedHead` line:

```java
 * @param plugins         EXECUTE only: the official plugins the plan picked (spec: plugin picks); null when there are none,
 *                        and then left out of the JSON, so an older worker still reads every job without picks
```

The record's last components become:

```java
        @JsonInclude(JsonInclude.Include.NON_NULL) String expectedHead,
        @JsonInclude(JsonInclude.Include.NON_NULL) List<String> plugins) {

    public Job {
        attachments = attachments == null ? List.of() : List.copyOf(attachments);
        commitTrailers = commitTrailers == null ? List.of() : List.copyOf(commitTrailers);
        plugins = plugins == null || plugins.isEmpty() ? null : List.copyOf(plugins);
    }

    /** Before plugin picks: none. */
    public Job(long taskId, int seq, RunKind kind, Project project, String baseBranch, String baseSha, String worktree,
            String prUrl, UUID sessionId, boolean resume, String prompt, String model, String effort, long timeoutMillis,
            BigDecimal budgetUsd, List<Attachment> attachments, String commitSubject, List<String> commitTrailers,
            String deliverySummary, String branch, String reviewPrompt, String expectedHead) {
        this(taskId, seq, kind, project, baseBranch, baseSha, worktree, prUrl, sessionId, resume, prompt, model, effort,
                timeoutMillis, budgetUsd, attachments, commitSubject, commitTrailers, deliverySummary, branch, reviewPrompt,
                expectedHead, null);
    }
```

The three older constructors stay as they are: each calls a longer one. Beside `branchName()`, add:

```java
    /** The plugins this run loads (spec: plugin picks); empty for a job without picks or from an older team machine. */
    @JsonIgnore
    public List<String> picks() {
        return plugins == null ? List.of() : plugins;
    }
```

- [ ] **Step 4: Add `picks` to `RunRequest`**

Add to the class comment:

```java
 * @param picks        the official plugins the task's plan picked (spec: plugin picks); empty for none, ignored by Codex and
 *                     Gemini CLI
```

Append the component `List<String> picks` after `List<Path> pluginDirs`. Then the compact constructor, `withPluginDirs` and the new 12-argument constructor:

```java
    public RunRequest {
        pluginDirs = pluginDirs == null ? List.of() : List.copyOf(pluginDirs);
        picks = picks == null ? List.of() : List.copyOf(picks);
    }

    /** This request with {@code more} plugin directories after its own; the sandbox binds them all read-only. */
    public RunRequest withPluginDirs(List<Path> more) {
        List<Path> dirs = new java.util.ArrayList<>(pluginDirs);
        dirs.addAll(more);
        return new RunRequest(kind, workdir, prompt, sessionId, resume, readOnlyDirs, budgetUsd, model, effort, logBase,
                environment, dirs, picks);
    }

    /** Without picks. */
    public RunRequest(RunKind kind, Path workdir, String prompt, UUID sessionId, boolean resume, List<Path> readOnlyDirs,
                      BigDecimal budgetUsd, String model, String effort, Path logBase, Map<String, String> environment,
                      List<Path> pluginDirs) {
        this(kind, workdir, prompt, sessionId, resume, readOnlyDirs, budgetUsd, model, effort, logBase, environment, pluginDirs,
                List.of());
    }
```

In the two existing shorter constructors, change their `this(…, List.of())` call to `this(…, List.of(), List.of())`.

- [ ] **Step 5: Copy the picks in `Coordinator`**

`newJob` gains a last parameter `List<String> plugins`, passed as the last argument of its `new Job(…)` (after `expectedHead(task.id(), run.kind())`). `planJob` and `deliverJob` pass `null` for it. `executeJob` passes `picks(task)`. Add:

```java
    /** The plan's plugin picks (spec: plugin picks); a task without a plan has none. A plan run never loads them. */
    private static List<String> picks(Task task) {
        return task.planJson() == null ? null : Plan.parse(task.planJson()).plugins();
    }
```

- [ ] **Step 6: Pass them through on a worker**

In `WorkerLoop.withLocalClone`, the returned `new Job(…)` ends `job.deliverySummary(), job.branch(), job.reviewPrompt(), job.expectedHead(), job.plugins()));`.

- [ ] **Step 7: Put them on every request in `JobRunner`**

In `request`, the `new RunRequest(…)` ends `workspaces.runLogBase(job.taskId(), job.seq()), Map.of(), plugins, job.picks());`. In `loopAgents`, the `fix` request ends `Path.of(logBase + ".fix-" + fixes), Map.of(), plugins, job.picks()), timeout);` and the `review` request ends `Path.of(logBase + ".review"), Map.of(), plugins, job.picks()), timeout);`. A PLAN job always has no picks (Step 5), so its request carries none.

- [ ] **Step 8: Run the tests to verify they pass**

Run: `./mvnw -q test -Dtest='JobJsonTest,CoordinatorTest,WorkerLoopTest,JobRunnerTest,ClaudeCodeAgentTest'`
Expected: PASS.

- [ ] **Step 9: Commit**

```bash
git add src/main/java/dispatch/core/Job.java src/main/java/dispatch/agent/RunRequest.java \
        src/main/java/dispatch/core/Coordinator.java src/main/java/dispatch/worker/WorkerLoop.java \
        src/main/java/dispatch/core/JobRunner.java src/test/java/dispatch/core/JobJsonTest.java \
        src/test/java/dispatch/core/CoordinatorTest.java src/test/java/dispatch/worker/WorkerLoopTest.java \
        src/test/java/dispatch/core/JobRunnerTest.java
git commit -F - <<'EOF'
A plan's plugin picks reach its execution, fix and review runs on any machine, and a job without picks reads as before

Co-Authored-By: Claude Opus 5.5 (1M context) <noreply@anthropic.com>
Claude-Session: https://claude.ai/code/session_01XY6qLAmFtZyEU8pL3Mjn2W
EOF
```

---

### Task 5: A run loads its picks from the official marketplace copy

**Files:**
- Create: `src/main/java/dispatch/agent/claude/OfficialPlugins.java`
- Modify: `src/main/java/dispatch/agent/claude/OwnerPlugins.java` (`resolve`)
- Modify: `src/main/java/dispatch/agent/claude/ClaudeCodeAgent.java` (`start`)
- Modify: `src/test/java/dispatch/testing/OwnerPluginsFixture.java`
- Test: `src/test/java/dispatch/agent/claude/OfficialPluginsTest.java` (create), `src/test/java/dispatch/agent/claude/OwnerPluginsTest.java`, `src/test/java/dispatch/agent/claude/ClaudeCodeAgentTest.java`

**Interfaces:**
- Consumes: `CuratedPlugins.MARKETPLACE` (Task 1); `RunRequest.picks()` and the 13-argument `RunRequest` constructor (Task 4).
- Produces: `OfficialPlugins.marketplace(Path home) -> Path` and `OfficialPlugins.find(Path home, List<String> picks) -> Map<String, Path>` (package-private); `OwnerPlugins.resolve(String claudeCommand, Map<String, String> environment, List<String> picks) -> Resolved`, with the 2-argument `resolve` kept for `dispatch check`; `OwnerPluginsFixture.marketplace(Path home, Map<String, String> plugins) -> Path`.

- [ ] **Step 1: Add the marketplace fixture**

In `OwnerPluginsFixture` (import `java.util.StringJoiner`):

```java
    /**
     * An official marketplace copy under {@code home}, as Claude Code keeps it, hosting each plugin at ./plugins/<name>
     * with its {@code .mcp.json} holding the given servers, or none when they are "".
     */
    public static Path marketplace(Path home, Map<String, String> plugins) throws IOException {
        Path marketplace = home.resolve(".claude/plugins/marketplaces/claude-plugins-official");
        Files.createDirectories(marketplace.resolve(".claude-plugin"));
        StringJoiner entries = new StringJoiner(",");
        for (Map.Entry<String, String> plugin : plugins.entrySet()) {
            plugin(marketplace.resolve("plugins").resolve(plugin.getKey()), plugin.getValue().isEmpty() ? null : plugin.getValue());
            entries.add("{\"name\": \"" + plugin.getKey() + "\", \"source\": \"./plugins/" + plugin.getKey() + "\"}");
        }
        Files.writeString(marketplace.resolve(".claude-plugin/marketplace.json"), "{\"plugins\": [" + entries + "]}");
        return marketplace;
    }
```

- [ ] **Step 2: Write the failing tests**

Create `OfficialPluginsTest`:

```java
package dispatch.agent.claude;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dispatch.testing.OwnerPluginsFixture;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class OfficialPluginsTest {

    @TempDir
    Path home;

    private Path marketplace;

    @BeforeEach
    void catalog() throws IOException {
        marketplace = Files.createDirectories(OfficialPlugins.marketplace(home));
        OwnerPluginsFixture.plugin(marketplace.resolve("plugins/frontend-design"), null);
        OwnerPluginsFixture.plugin(marketplace.resolve("external_plugins/playwright"), "{\"playwright\": {\"command\": \"npx\"}}");
        Files.createDirectories(marketplace.resolve(".claude-plugin"));
        Files.writeString(marketplace.resolve(".claude-plugin/marketplace.json"), """
                {"plugins": [
                  {"name": "frontend-design", "source": "./plugins/frontend-design"},
                  {"name": "playwright", "source": "./external_plugins/playwright"},
                  {"name": "context7", "source": {"source": "url", "url": "https://example.com/context7.git"}},
                  {"name": "escape", "source": "../../outside"},
                  {"name": "absolute", "source": "/etc"},
                  {"name": "gone", "source": "./plugins/gone"}
                ]}""");
    }

    @Test
    void picksHostedInTheMarketplaceResolveToTheirDirectoriesInOrder() {
        Map<String, Path> found = OfficialPlugins.find(home, List.of("playwright", "frontend-design"));

        assertEquals(List.of("playwright", "frontend-design"), List.copyOf(found.keySet()));
        assertEquals(marketplace.resolve("external_plugins/playwright").toAbsolutePath().normalize(), found.get("playwright"));
    }

    @Test
    void aPickThisMachineCannotLoadAsADirectoryInTheMarketplaceIsSkipped() {
        Map<String, Path> found = OfficialPlugins.find(home,
                List.of("context7", "escape", "absolute", "gone", "unknown", "frontend-design"));

        assertEquals(List.of("frontend-design"), List.copyOf(found.keySet()), "a fetched source, an escape, a missing dir");
    }

    @Test
    void noMarketplaceCopyOrNoHomeSkipsEveryPick() throws IOException {
        Files.delete(marketplace.resolve(".claude-plugin/marketplace.json"));

        assertTrue(OfficialPlugins.find(home, List.of("frontend-design")).isEmpty());
        assertTrue(OfficialPlugins.find(null, List.of("frontend-design")).isEmpty());
    }
}
```

In `OwnerPluginsTest` (add `assertNull` to the static imports):

```java
    @Test
    @DisabledOnOs(OS.WINDOWS)
    void picksLoadWithTheirServersAndAPickTheOwnerAlsoListsLoadsOnceAsListed() throws IOException {
        Path claude = FakeClaude.install(Files.createDirectories(dir.resolve("bin")));
        Path home = Files.createDirectories(dir.resolve("home"));
        Path marketplace = OwnerPluginsFixture.marketplace(home, Map.of("frontend-design", "",
                "playwright", "{\"playwright\": {\"command\": \"npx\"}}"));
        Path installed = OwnerPluginsFixture.plugin(dir.resolve("cache/frontend-design"), null);
        OwnerPluginsFixture.installed(claude, Map.of("frontend-design@claude-plugins-official", installed));
        Path config = Files.writeString(dir.resolve("dispatch.yaml"),
                "agents:\n  claude-code:\n    plugins: [frontend-design@claude-plugins-official]\n");

        OwnerPlugins.Resolved resolved = OwnerPlugins.instance(config, home)
                .resolve(claude.toString(), FakeClaude.environment(), List.of("frontend-design", "playwright"));

        assertEquals(List.of(installed, marketplace.resolve("plugins/playwright")), resolved.pluginDirs());
        JsonNode servers = Json.MAPPER.readTree(resolved.mcpConfig()).get("mcpServers");
        assertEquals("npx", servers.path("plugin_playwright_playwright").path("command").asText());
    }

    @Test
    void picksAloneResolveWithoutAskingClaudeCodeForItsPlugins() throws IOException {
        Path home = Files.createDirectories(dir.resolve("home"));
        Path marketplace = OwnerPluginsFixture.marketplace(home, Map.of("frontend-design", ""));
        Path config = Files.writeString(dir.resolve("dispatch.yaml"), "agents:\n  claude-code:\n    command: claude\n");

        OwnerPlugins.Resolved resolved = OwnerPlugins.instance(config, home)
                .resolve("/nonexistent/claude", Map.of(), List.of("frontend-design"));

        assertEquals(List.of(marketplace.resolve("plugins/frontend-design")), resolved.pluginDirs());
        assertNull(resolved.mcpConfig(), "no server, no --mcp-config");
    }

    @Test
    void noHomeSkipsEveryPickInsteadOfFailingTheRun() {
        OwnerPlugins.Resolved resolved = OwnerPlugins.NONE.resolve("claude", Map.of(), List.of("frontend-design"));

        assertEquals(List.of(), resolved.pluginDirs());
    }
```

In `ClaudeCodeAgentTest`:

```java
    @Test
    void anExecutionLoadsItsPicksAndAPickThisMachineLacksIsSkippedAndLogged() throws Exception {
        Path home = Files.createDirectories(dir.resolve("home"));
        Path marketplace = OwnerPluginsFixture.marketplace(home, Map.of("playwright", "{\"playwright\": {\"command\": \"npx\"}}"));
        Path config = Files.writeString(dir.resolve("dispatch.yaml"), "agents:\n  claude-code:\n    command: claude\n");
        ClaudeCodeAgent owned = owning(config, home, Map.of());
        RunRequest execute = new RunRequest(RunKind.EXECUTE, workdir, "Build it", SESSION, false, List.of(),
                new BigDecimal("2"), null, null, dir.resolve("runs/1/2"), Map.of(), List.of(), List.of("playwright", "context7"));

        String logged = capturingLog(() -> awaitQuietly(owned.start(execute)));

        List<String> args = Files.readAllLines(workdir.resolve("fake-claude.args"));
        assertEquals(marketplace.resolve("plugins/playwright").toString(), valueAfter(args, "--plugin-dir"));
        assertTrue(valueAfter(args, "--mcp-config").contains("\"plugin_playwright_playwright\""), args.toString());
        assertTrue(args.contains("--strict-mcp-config"));
        assertTrue(valueAfter(args, "--tools").endsWith(",Skill"), args.toString());
        assertTrue(logged.contains("event=agent.plugin_skipped") && logged.contains("context7"), logged);
    }

    @Test
    void anExecutionWithoutPicksKeepsTodaysCommandLine() throws Exception {
        Path config = Files.writeString(dir.resolve("dispatch.yaml"), "agents:\n  claude-code:\n    command: claude\n");
        ClaudeCodeAgent owned = owning(config, dir, Map.of());

        awaitQuietly(owned.start(new RunRequest(RunKind.EXECUTE, workdir, "Build it", SESSION, false, List.of(),
                new BigDecimal("2"), null, null, dir.resolve("runs/1/2"))));

        List<String> args = Files.readAllLines(workdir.resolve("fake-claude.args"));
        assertFalse(args.contains("--plugin-dir") || args.contains("--mcp-config"), args.toString());
    }
```

- [ ] **Step 3: Run them to verify they fail**

Run: `./mvnw -q test -Dtest='OfficialPluginsTest,OwnerPluginsTest,ClaudeCodeAgentTest'`
Expected: compilation FAILS: `OfficialPlugins` and the 3-argument `resolve` do not exist.

- [ ] **Step 4: Create `OfficialPlugins`**

```java
package dispatch.agent.claude;

import com.fasterxml.jackson.databind.JsonNode;
import dispatch.Json;
import dispatch.Log;
import dispatch.domain.CuratedPlugins;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Where this machine's copy of the official marketplace keeps a plan's picks (spec: plugin picks). Claude Code keeps the
 * marketplace cloned under ~/.claude, and a plugin hosted in it is a directory there, so nothing is installed or fetched.
 * A pick this machine cannot find is skipped, not fatal: the agent chose it, the owner did not.
 */
final class OfficialPlugins {

    private OfficialPlugins() {
    }

    /** The marketplace's directory under {@code home}. */
    static Path marketplace(Path home) {
        return home.resolve(".claude/plugins/marketplaces").resolve(CuratedPlugins.MARKETPLACE);
    }

    /** Each pick found in {@code home}'s marketplace copy, by name, in the order given; the rest are skipped and logged. */
    static Map<String, Path> find(Path home, List<String> picks) {
        Map<String, Path> found = new LinkedHashMap<>();
        if (picks.isEmpty()) {
            return found;
        }
        if (home == null) {
            picks.forEach(pick -> skipped(pick, "this machine has no home directory to look in"));
            return found;
        }
        Path marketplace = marketplace(home).toAbsolutePath().normalize();
        Path catalog = marketplace.resolve(".claude-plugin/marketplace.json");
        JsonNode entries;
        try {
            entries = Json.MAPPER.readTree(catalog.toFile()).path("plugins");
        } catch (IOException e) {
            picks.forEach(pick -> skipped(pick, "cannot read " + catalog + ": " + e.getMessage()));
            return found;
        }
        for (String pick : picks) {
            Path dir = directory(marketplace, entries, pick);
            if (dir != null) {
                found.put(pick, dir);
            }
        }
        return found;
    }

    private static Path directory(Path marketplace, JsonNode entries, String pick) {
        for (JsonNode entry : entries) {
            if (!entry.path("name").asText().equals(pick)) {
                continue;
            }
            JsonNode source = entry.get("source");
            // A third-party plugin's source is an object (git or URL) that only an install fetches.
            if (source == null || !source.isTextual()) {
                skipped(pick, "its marketplace source is not a directory in the marketplace");
                return null;
            }
            Path dir = marketplace.resolve(source.asText()).normalize();
            if (!dir.startsWith(marketplace)) {
                skipped(pick, "its marketplace source " + source.asText() + " is outside the marketplace");
                return null;
            }
            if (!Files.isRegularFile(dir.resolve(".claude-plugin/plugin.json"))) {
                skipped(pick, "no plugin at " + dir);
                return null;
            }
            return dir;
        }
        skipped(pick, "the marketplace at " + marketplace + " has no such plugin");
        return null;
    }

    private static void skipped(String pick, String why) {
        Log.warn("agent.plugin_skipped", "plugin", pick, "reason", why);
    }
}
```

- [ ] **Step 5: Merge the picks in `OwnerPlugins.resolve`**

Add the import `dispatch.domain.CuratedPlugins`. Replace the head of `resolve` and its owner-plugin loop, keeping everything from `if (!lists.mcpServers().isEmpty())` on unchanged:

```java
    /**
     * What a run loads, resolved on this machine now: each listed plugin's directory, and one MCP configuration holding
     * the listed servers and the listed plugins' own. Fails the run before its agent starts when something listed is
     * missing.
     */
    public Resolved resolve(String claudeCommand, Map<String, String> environment) {
        return resolve(claudeCommand, environment, List.of());
    }

    /**
     * As {@link #resolve(String, Map)}, plus the official plugins the task's plan picked (spec: plugin picks), found in
     * this machine's marketplace copy with their servers in the same configuration. A pick the owner also lists loads
     * once, as listed. A pick this machine lacks is skipped.
     */
    public Resolved resolve(String claudeCommand, Map<String, String> environment, List<String> picks) {
        Lists lists = lists();
        if (lists.isEmpty() && picks.isEmpty()) {
            return Resolved.NONE;
        }
        ObjectNode servers = Json.MAPPER.createObjectNode();
        List<Path> dirs = new ArrayList<>();
        Map<String, Path> installed = lists.plugins().isEmpty() ? Map.of() : installed(claudeCommand, environment);
        for (String id : lists.plugins()) {
            Path dir = installed.get(id);
            if (dir == null) {
                throw new AgentStartException("claude-code plugin " + id + " is not installed on this computer: install it "
                        + "(claude plugin install " + id + ") or remove it from " + pluginsKey() + " in " + file, null);
            }
            dirs.add(dir);
            // Named as Claude Code names a plugin's server in the owner's own sessions, so its tools are called the same.
            addServers(servers, id.contains("@") ? id.substring(0, id.indexOf('@')) : id, dir);
        }
        List<String> unlisted = picks.stream()
                .filter(pick -> !lists.plugins().contains(pick + "@" + CuratedPlugins.MARKETPLACE)).toList();
        for (Map.Entry<String, Path> pick : OfficialPlugins.find(home, unlisted).entrySet()) {
            dirs.add(pick.getValue());
            addServers(servers, pick.getKey(), pick.getValue());
        }
```

Add beside `add`:

```java
    /** A plugin's own servers, named {@code plugin_<plugin>_<server>}, with its directory as ${CLAUDE_PLUGIN_ROOT}. */
    private static void addServers(ObjectNode servers, String plugin, Path dir) {
        for (Iterator<Map.Entry<String, JsonNode>> it = pluginServers(dir); it.hasNext(); ) {
            Map.Entry<String, JsonNode> server = it.next();
            add(servers, "plugin_" + plugin + "_" + server.getKey(), withRoot(server.getValue(), dir));
        }
    }
```

- [ ] **Step 6: Hand the picks over in `ClaudeCodeAgent.start`**

```java
        OwnerPlugins.Resolved owner = OWNER_KINDS.contains(request.kind())
                ? ownerPlugins.resolve(command, ProcessRun.agentEnvironment(environment), request.picks())
                : OwnerPlugins.Resolved.NONE;
```

Change the comment above it to: "Read and resolved now, on the machine that runs the agent: an edit, an install or the plan's picks apply to this run."

- [ ] **Step 7: Run the tests to verify they pass**

Run: `./mvnw -q test -Dtest='OfficialPluginsTest,OwnerPluginsTest,ClaudeCodeAgentTest,ChecksTest'`
Expected: PASS.

- [ ] **Step 8: Commit**

```bash
git add src/main/java/dispatch/agent/claude/OfficialPlugins.java src/main/java/dispatch/agent/claude/OwnerPlugins.java \
        src/main/java/dispatch/agent/claude/ClaudeCodeAgent.java src/test/java/dispatch/testing/OwnerPluginsFixture.java \
        src/test/java/dispatch/agent/claude/OfficialPluginsTest.java src/test/java/dispatch/agent/claude/OwnerPluginsTest.java \
        src/test/java/dispatch/agent/claude/ClaudeCodeAgentTest.java
git commit -F - <<'EOF'
An execution or review run loads its plan's picks from the official marketplace copy, with their servers in its one MCP configuration, and skips a pick the machine lacks

Co-Authored-By: Claude Opus 5.5 (1M context) <noreply@anthropic.com>
Claude-Session: https://claude.ai/code/session_01XY6qLAmFtZyEU8pL3Mjn2W
EOF
```

---

### Task 6: Live check and documentation

**Files:**
- Modify: `src/test/java/dispatch/core/LiveOwnerPluginsTest.java`
- Create: `docs/adr/0037-a-plan-picks-official-plugins-from-a-list-dispatch-ships.md`
- Modify: `docs/adr/0036-claude-runs-load-the-plugins-and-mcp-servers-the-owner-lists.md`, `SECURITY.md`, `README.en.md`, `README.md`, `docs/ARCHITECTURE.md`, `docs/superpowers/specs/2026-10-02-plugin-picks-design.md`

**Interfaces:**
- Consumes: `Prompts.PLUGIN_NOTE` (Task 2), `Plan.plugins()` (Task 1), the 13-argument `RunRequest` (Task 4), the picks loading (Task 5).

- [ ] **Step 1: Write the opt-in live test**

In `LiveOwnerPluginsTest`, after the skills test (add imports `dispatch.domain.Plan` and `java.util.Map` if missing):

```java
    /** A plan picks frontend-design for a page, and an execution with that pick invokes its skill (spec: plugin picks). */
    @Test
    @EnabledIfEnvironmentVariable(named = "DISPATCH_LIVE_CLAUDE", matches = "1")
    void aPlanPicksFrontendDesignForAPageAndAnExecutionLoadsIt() throws Exception {
        Sandbox sandbox = Sandboxes.detect(SandboxSetting.AUTO, Probe.system(System.getenv()));
        assumeTrue(sandbox instanceof Bubblewrap, "needs bwrap");
        Path home = Path.of(System.getProperty("user.home"));
        assumeTrue(Files.isDirectory(home.resolve(".claude/plugins/marketplaces/claude-plugins-official/plugins/frontend-design")),
                "needs the official marketplace copy");
        Path root = Files.createTempDirectory(Path.of("target").toAbsolutePath(), "live-picks");
        Path stateDir = Files.createDirectories(root.resolve("state"));
        Path workdir = Files.createDirectories(root.resolve("work"));
        Files.writeString(workdir.resolve("index.html"), "<!doctype html><title>Settings</title><body></body>\n");
        Path config = Files.writeString(root.resolve("dispatch.yaml"), "agents:\n  claude-code:\n    command: claude\n");
        ClaudeCodeAgent claude = new ClaudeCodeAgent("claude", System.getenv(), Duration.ofSeconds(10),
                new Confinement(sandbox, new SandboxPolicies(home, stateDir, List.of(stateDir))), OwnerPlugins.instance(config, home));

        AgentResult plan = claude.start(new RunRequest(RunKind.PLAN, workdir,
                "Add a settings page to index.html with a form for the user's name and email." + Prompts.PLUGIN_NOTE,
                UUID.randomUUID(), false, List.of(), null, "sonnet", null, stateDir.resolve("runs/1/1"))).await();
        System.out.println("LIVE plugin picks, plan: " + plan.outcome() + " " + plan.structuredOutput());
        assertEquals(AgentOutcome.SUCCEEDED, plan.outcome(), plan.error());
        List<String> picks = Plan.parse(plan.structuredOutput()).plugins();
        assertTrue(picks.contains("frontend-design"), "picked: " + picks);

        Path logBase = stateDir.resolve("runs/1/2");
        AgentResult execute = claude.start(new RunRequest(RunKind.EXECUTE, workdir,
                "Load the frontend-design:frontend-design skill with the Skill tool, then reply done. Change no file.",
                UUID.randomUUID(), false, List.of(), null, "sonnet", null, logBase, Map.of(), List.of(), picks)).await();
        List<JsonNode> events = Files.readAllLines(Path.of(logBase + ".jsonl")).stream().map(Json::read).toList();
        System.out.println("LIVE plugin picks, execute: " + execute.outcome());
        assertEquals(AgentOutcome.SUCCEEDED, execute.outcome(), execute.error());
        assertTrue(succeeded(events, "Skill", "frontend-design"), "the picked skill loaded");
    }
```

- [ ] **Step 2: Run it live (two short Sonnet runs)**

Run: `DISPATCH_LIVE_CLAUDE=1 ./mvnw -q test -Dtest='LiveOwnerPluginsTest#aPlanPicksFrontendDesignForAPageAndAnExecutionLoadsIt'`
Expected: PASS, printing `LIVE plugin picks, plan: SUCCEEDED {… "plugins":["frontend-design"…]}`. If the plan does not pick it, read the printed plan before changing anything: the prompt's wording may need to say more plainly what the list is for.

- [ ] **Step 3: Write ADR 0037**

`docs/adr/0037-a-plan-picks-official-plugins-from-a-list-dispatch-ships.md`:

```markdown
# A plan picks official plugins from a list Dispatch ships

Amends ADR 0036 (listing plugins is the machine owner's decision): a task's plan may also pick plugins, from a short
list in Dispatch.

A Claude Code plan run is shown `CuratedPlugins` (frontend-design, playwright, context7) and names the ones the task
needs in the plan's `plugins` field. The requester sees them in the plan message and the Mini App before approving.
The task's execution, its verify-loop fixes and its reviewer load them; the plan run never does. Each is found in the
copy of Anthropic's `claude-plugins-official` marketplace that Claude Code keeps under `~/.claude`, so nothing is
installed or fetched. Its servers join the run's one inline `--mcp-config`, and `--strict-mcp-config` stays. A name
outside the list is dropped from the plan, and a pick a machine cannot find is skipped with `agent.plugin_skipped`.

A plugin joins the list only if it works in an unattended, sandboxed run: no hook that waits for a person or calls a
model of its own, no credentials, and no tool Dispatch's runs lack.

We chose this over:
- **Every Anthropic-hosted plugin.** That includes messaging bridges (telegram, discord, imessage), github and gitlab,
  which would push around Dispatch's `git push` and `gh` ban, servers that need credentials, and hooks written for a
  person at the keyboard.
- **The whole official marketplace.** 262 of its 315 plugins are third-party repositories fetched at install time, and
  `claude plugin install` also enables a plugin in the owner's own Claude Code.
- **An owner allowlist the agent picks from.** More configuration for each owner to keep, for a list that is the same
  everywhere.
- **Loading the whole list in every run.** Each run would start Playwright's and Context7's servers whether the task
  needs them or not, and the LSP plugins (next) are too heavy for that.

Consequences: a picked plugin's code runs in the sandbox with the agent's access, and `playwright` runs whatever npm
serves as `@playwright/mcp@latest`. The agent cannot add to the list; changing it is a Dispatch commit. A job with picks
carries a field an older worker cannot read, so members upgrade their workers before the team machine's plans pick
anything.
```

- [ ] **Step 4: Note the amendment and update the docs**

`docs/adr/0036-…md`: insert after its first paragraph (the "Amends ADR 0034 …" lines) a new paragraph:

```markdown
Amended by ADR 0037: a task's plan may also pick plugins from a short list Dispatch ships, which its execution and review
runs load from the official marketplace copy.
```

`SECURITY.md`: after the bullet that begins `- **Skills:** the dispatch plugin under the state directory`, add:

```markdown
- **Plugin picks:** a task's plan may pick plugins from a list Dispatch ships (ADR 0037), and their code then runs in the sandbox with the agent's access. The list holds only Anthropic-hosted plugins without hooks or credentials, and the agent cannot add to it. `playwright` runs whatever npm serves as `@playwright/mcp@latest`.
```

`README.en.md`: after the paragraph that begins `A machine can add its own Claude Code plugins and MCP servers` and ends `screenshots.`, add:

```markdown
A plan can also pick official plugins for its task from a short list Dispatch ships (ADR 0037): `frontend-design` for
interface work, `playwright` to check a change in a browser, `context7` for a library's current documentation. The plan
message shows them (🧩), and the execution and review load them from the official marketplace copy Claude Code already
keeps, without installing anything. A machine that lacks one skips it.
```

`README.md`: after the `| 🧩 **Skills** | … |` row, add:

```markdown
| 🔌 **Plugin** | Төлөвлөгөө ажилд хэрэгтэй албан ёсны plugin-ийг жагсаалтаас сонгоно (`frontend-design`, `playwright`, `context7`); хэрэгжүүлэлт ба review тэдгээрийг юу ч суулгалгүй ачаална |
```

`docs/ARCHITECTURE.md`, line 286 (the `| Always |` row): before its closing `(ADR 0036) |`, the sentence ends `…read again before each run (ADR 0036)`. Change that end to:

```markdown
read again before each run (ADR 0036), and for execute and review runs the plan's picks from the official marketplace copy (`--plugin-dir`, their servers in the same `--mcp-config`; ADR 0037) |
```

`docs/superpowers/specs/2026-10-02-plugin-picks-design.md`: in "The plan", replace `` `plan.plugins` in both languages `` with `` `plan.plugins` (Telegram messages are Mongolian only) ``. In "Testing", replace `the plan message line in both languages` with `the plan message line`.

- [ ] **Step 5: Run the full verify**

Run: `./mvnw -q verify` (in `ui/`: `npm run typecheck && npm test` were run in Task 3)
Expected: BUILD SUCCESS, 0 failures; the live tests are skipped without `DISPATCH_LIVE_CLAUDE=1`.

- [ ] **Step 6: Commit**

```bash
git add src/test/java/dispatch/core/LiveOwnerPluginsTest.java docs/adr/0037-a-plan-picks-official-plugins-from-a-list-dispatch-ships.md \
        docs/adr/0036-claude-runs-load-the-plugins-and-mcp-servers-the-owner-lists.md SECURITY.md README.en.md README.md \
        docs/ARCHITECTURE.md docs/superpowers/specs/2026-10-02-plugin-picks-design.md
git commit -F - <<'EOF'
ADR 0037, the READMEs and the security notes describe plugin picks, and an opt-in live test picks and loads frontend-design

Co-Authored-By: Claude Opus 5.5 (1M context) <noreply@anthropic.com>
Claude-Session: https://claude.ai/code/session_01XY6qLAmFtZyEU8pL3Mjn2W
EOF
```
