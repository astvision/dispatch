# Agent Skills (SK) Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Claude Code's plan, execute, fix and review runs load a bundled `dispatch` plugin of five vetted skills adapted from superpowers 6.4.1, and every plan and execution prompt says that speed matters. On by default; `skills: off` turns the skills off.

**Architecture:** The skills ship as jar resources under `skills/dispatch/` and are written to `<stateDir>/plugins/dispatch/` when a machine that runs task agents starts (a personal instance, a member's worker). The team machine marks a Claude Code job `Job.Project.skills = true`. The `JobRunner` on the machine that runs the agent then passes the plugin dir in `RunRequest.pluginDirs` and appends a note naming the skills for that call. `ClaudeCodeAgent` turns that into `--plugin-dir` plus `Skill` in `--tools`, and the sandbox binds the dir read-only.

**Tech Stack:** Java 25, no framework (ADR 0002), JUnit 5, Jackson 2.22 (`Json.MAPPER`, FAIL_ON_UNKNOWN_PROPERTIES), Maven wrapper `./mvnw`.

**Spec:** `docs/superpowers/specs/2026-10-01-agent-skills-design.md`

## Global Constraints

- Config: instance `skills: on | off` (missing = `on`; anything else refused as `skills: on or off, not <value>`). A project's `skills: on | off` overrides the instance's, and its error path is `projects[<i>].skills`.
- Skills apply only where the project's agent is `claude-code`. Codex and Gemini projects never get the flag, the plugin or a note.
- Plugin: name `dispatch`. Resources go in `src/main/resources/skills/dispatch/`, listed in its `files.txt`. It is written to `<stateDir>/plugins/dispatch/` by `dispatch run` in personal mode (not in team mode, which runs no task agent) and by `dispatch worker run`. The dir is deleted first, then written.
- Skills: `test-driven-development`, `systematic-debugging`, `verification-before-completion`, `receiving-code-review`, `code-reviewer`. Upstream is superpowers 6.4.1 (MIT, Jesse Vincent) at `/home/zb/.claude/plugins/cache/claude-plugins-official/superpowers/6.4.1/skills`.
- Notes, verbatim:
  - PLAN: `If the task reports a bug, use the dispatch:systematic-debugging skill to investigate its root cause before you plan; change nothing. Put the root cause in findings.`
  - EXECUTE: `Use the dispatch:test-driven-development skill while you build, and the dispatch:verification-before-completion skill before your summary.`
  - FIX_TEST: `Use the dispatch:systematic-debugging skill to find the root cause before you change code, then the dispatch:verification-before-completion skill.`
  - FIX_REVIEW: `Use the dispatch:receiving-code-review skill: check each finding against the code before you change anything.`
  - REVIEW: `Use the dispatch:code-reviewer skill, then answer only through the structured output.`
- Speed paragraphs, verbatim, ending `PLAN_FORMAT` and `EXECUTE_RULES`:
  - Plan: `Speed matters: investigate only what the plan needs. Search for the code you need instead of reading whole files or directories, stop once you can name the change, and run a build or test only when it is the quickest way to confirm a bug's cause.`
  - Execution: `Speed matters: read only the code your change touches, search instead of reading whole files, run only the tests that cover your change rather than the whole suite, and stop when the work is done: no refactoring, polish or extras beyond it.`
- Claude flags: `--plugin-dir <dir>` once per plugin dir, and `,Skill` appended to `--tools` for PLAN, EXECUTE and REVIEW when there is a plugin dir. `--setting-sources project,local` and `--strict-mcp-config` stay unchanged.
- Missing plugin dir on a skills job: `FailureReason.SETUP` with the detail `skills plugin missing at <dir>; restart Dispatch`, and no agent is started.
- Logs: `agent.skill skill=<name> run=<logBase>` (INFO) for a top-level `Skill` call, and `agent.plugin_errors run=<logBase> errors=<json>` (WARN).
- JSON compatibility: `Job.Project.skills` is null when off and left out of the JSON.
- Minimum Claude Code: 2.1.76.
- Commits: one sentence describing the behaviour, ending with these two lines; branch `agent-skills`; do not push.
  ```
  Co-Authored-By: Claude Opus 5.5 (1M context) <noreply@anthropic.com>
  Claude-Session: https://claude.ai/code/session_01HaBofgP3uFdxiejGuF4fkq
  ```

## Review Focus

1. **A team worker rebuilding the job.** `WorkerLoop.withLocalClone` copies every field by hand and could drop `skills`, which would silently turn skills off on every member's computer. Pinned in Task 2 (`workerKeepsTheJobsSkills`).
2. **A Codex or Gemini project with skills on**, which is the default. It must never get the flag, so it is never told to use `dispatch:` skills it cannot load. Pinned in Task 2 (`aCodexProjectWithSkillsOnNeverGetsThem`).
3. **A resumed run** (a correction, retry, follow-up, or any fix round) is a new `claude` process and needs `--plugin-dir` again. Pinned in Task 7 (every call of a looped execution carries the plugin dir and its note).
4. **A machine whose plugin dir is gone** must fail loudly as `SETUP` before any agent starts, rather than tell an agent to use skills it cannot find. Pinned in Task 7 (`aSkillsJobOnAMachineWithoutThePluginFailsAsSetupWithoutAnAgent`).
5. **The plugin inside the sandbox** must be bound read-only and never writable, or one task's agent could rewrite the skills every later run loads. Pinned in Task 5 (`aRunsPluginDirIsReadOnly`).

---

### Task 1: The `skills` setting

**Files:**
- Modify: `src/main/java/dispatch/config/Config.java` (record `Project`, lines 191-217)
- Modify: `src/main/java/dispatch/config/ConfigLoader.java` (`load` lines 77-81, `validateProjects` lines 365-427, `ConfigFile` lines 476-491)
- Modify: `src/main/resources/texts_en.properties:276`, `src/main/resources/texts_mn.properties:275`
- Modify: `src/test/java/dispatch/core/CoordinatorTest.java:371-375` (`loopProject`)
- Test: `src/test/java/dispatch/config/ConfigLoaderTest.java`

**Interfaces:**
- Produces: `Config.Project` gains a LAST component `String skills` ("on" or "off" once loaded); the 12-argument convenience constructor passes `null` for `test`, `loop` and `skills`; `boolean Config.Project.skillsOn()` returns `"on".equals(skills)`. Text key `config.loop` is renamed `config.onOff` (`{0}: on or off, not {1}`), used for both settings.

- [ ] **Step 1: Write the failing tests** (in `ConfigLoaderTest`, next to `anUnknownLoopValueIsRefused`; `VALID`, `write` and `ENV` are the class's own fixture)

```java
    @Test
    void skillsAreOnByDefaultAndTheInstanceMayTurnThemOff() throws Exception {
        assertTrue(ConfigLoader.load(write(VALID), ENV).projects().getFirst().skillsOn());

        assertFalse(ConfigLoader.load(write("skills: off\n" + VALID), ENV).projects().getFirst().skillsOn());
    }

    @Test
    void aProjectsSkillsOverrideTheInstance() throws Exception {
        String yaml = "skills: off\n" + VALID.replace("- name: autoland-management\n",
                "- name: autoland-management\n    skills: on\n");

        assertTrue(ConfigLoader.load(write(yaml), ENV).projects().getFirst().skillsOn());
    }

    @Test
    void anUnknownSkillsValueIsRefusedWhereItIsWritten() throws Exception {
        Path instance = write("skills: maybe\n" + VALID);
        ConfigException atInstance = assertThrows(ConfigException.class, () -> ConfigLoader.load(instance, ENV));
        assertTrue(atInstance.getMessage().contains("skills: on or off, not maybe"), atInstance.getMessage());

        Path project = write(VALID.replace("- name: autoland-management\n", "- name: autoland-management\n    skills: always\n"));
        ConfigException atProject = assertThrows(ConfigException.class, () -> ConfigLoader.load(project, ENV));
        assertTrue(atProject.getMessage().contains("projects[0].skills: on or off, not always"), atProject.getMessage());
    }
```

- [ ] **Step 2: Run them to see them fail**

Run: `./mvnw -q test -Dtest=ConfigLoaderTest`
Expected: COMPILATION ERROR (`skillsOn()` does not exist).

- [ ] **Step 3: Add `skills` to `Config.Project`**

In `Config.java`, change the end of the record's components and its convenience constructor:

```java
            /** "on" or "off" once loaded: the project's own setting, else the instance's (spec: verify loop). */
            String loop,
            /** "on" or "off" once loaded: the project's own setting, else the instance's (spec: agent skills). */
            String skills) {

        /** A project as it was before the verify loop: no test command, loop and skills unresolved. */
        public Project(String name, String alias, String repo, String path, String baseBranch, String agent, String model,
                       String effort, List<String> copyFiles, Limits limits, PhaseSettings plan, PhaseSettings execute) {
            this(name, alias, repo, path, baseBranch, agent, model, effort, copyFiles, limits, plan, execute, null, null, null);
        }

        public boolean loopOn() {
            return "on".equals(loop);
        }

        /** Whether this project's Claude Code runs load the dispatch skills plugin; Codex and Gemini ignore it. */
        public boolean skillsOn() {
            return "on".equals(skills);
        }
```

- [ ] **Step 4: Resolve and validate it in `ConfigLoader`**

In `load`, replace the loop block and the `validateProjects` call:

```java
        String loop = raw.loop() == null ? "on" : raw.loop();
        if (!loop.equals("on") && !loop.equals("off")) {
            errors.add(Text.of("config.onOff", "loop", raw.loop()));
        }
        String skills = raw.skills() == null ? "on" : raw.skills();
        if (!skills.equals("on") && !skills.equals("off")) {
            errors.add(Text.of("config.onOff", "skills", raw.skills()));
        }
        List<Config.Project> projects = validateProjects(raw.projects(), agents, loop, skills, errors);
```

Change `validateProjects`' signature to `(List<Config.Project> projects, Map<String, Config.Agent> agents, String instanceLoop, String instanceSkills, List<Text> errors)`. In its loop, replace the `config.loop` error key and add the skills check before `normalized.add`:

```java
            String projectLoop = project.loop() == null ? instanceLoop : project.loop();
            if (!projectLoop.equals("on") && !projectLoop.equals("off")) {
                errors.add(Text.of("config.onOff", at + ".loop", project.loop()));
            }
            String projectSkills = project.skills() == null ? instanceSkills : project.skills();
            if (!projectSkills.equals("on") && !projectSkills.equals("off")) {
                errors.add(Text.of("config.onOff", at + ".skills", project.skills()));
            }
```

and pass it last: `project.execute(), project.test(), projectLoop, projectSkills));`.

In `ConfigFile`, add the last component: `String loop, String skills) {`.

- [ ] **Step 5: Rename the text key in both languages**

`texts_en.properties`: `config.loop={0}: on or off, not {1}` becomes `config.onOff={0}: on or off, not {1}`.
`texts_mn.properties`: `config.loop={0}: on эсвэл off байна, {1} биш` becomes `config.onOff={0}: on эсвэл off байна, {1} биш`.

- [ ] **Step 6: Keep `CoordinatorTest` compiling**

`loopProject` uses the canonical constructor; add the new last argument `null`:

```java
    private static Config.Project loopProject(String loop) {
        return new Config.Project("alm", null, "git@github.com:acme/alm.git", "/home/bold/alm", "main", "claude-code", null,
                "high", List.of(".env"), null, new Config.PhaseSettings("opus", null), new Config.PhaseSettings(null, "low"),
                "./mvnw -q test", loop, null);
    }
```

- [ ] **Step 7: Run the tests**

Run: `./mvnw -q test -Dtest='ConfigLoaderTest,CoordinatorTest'`
Expected: PASS. The loop tests still pass, because their message text is unchanged.

- [ ] **Step 8: Commit**

```bash
git add src/main/java/dispatch/config src/main/resources/texts_en.properties src/main/resources/texts_mn.properties \
        src/test/java/dispatch/config/ConfigLoaderTest.java src/test/java/dispatch/core/CoordinatorTest.java
git commit -m "A skills setting, on unless the instance or the project says off, is read and checked like loop

Co-Authored-By: Claude Opus 5.5 (1M context) <noreply@anthropic.com>
Claude-Session: https://claude.ai/code/session_01HaBofgP3uFdxiejGuF4fkq"
```

---

### Task 2: A Claude Code job carries its skills to whatever runs it

**Files:**
- Modify: `src/main/java/dispatch/core/Job.java` (record `Project`, lines 107-122)
- Modify: `src/main/java/dispatch/core/Coordinator.java` (`newJob`, lines 155-165)
- Modify: `src/main/java/dispatch/worker/WorkerLoop.java:230-232`
- Test: `src/test/java/dispatch/core/CoordinatorTest.java`, `src/test/java/dispatch/core/JobJsonTest.java`, `src/test/java/dispatch/worker/WorkerLoopTest.java`

**Interfaces:**
- Consumes: `Config.Project.skillsOn()` and `Config.Project.agent()` (Task 1).
- Produces: `Job.Project` gains a LAST component `@JsonInclude(NON_NULL) Boolean skills` and an 8-argument constructor `(name, repo, path, baseBranch, agent, copyFiles, test, loop)` that passes `null`. `boolean Job.Project.skillsOn()` returns `Boolean.TRUE.equals(skills)`.

- [ ] **Step 1: Write the failing tests**

In `CoordinatorTest` (reuse `approvedExecutionJob`, `projects`, `queue`, `coordinator`, `remember`, `claim`, `given`, `agentResult` and `PLAN_JSON`, which the class already has):

```java
    @Test
    void aClaudeCodeProjectWithSkillsOnSendsThem() {
        Job on = approvedExecutionJob(skillsProject("on", "claude-code"));

        assertTrue(on.project().skillsOn());
        assertTrue(Json.write(on).contains("\"skills\":true"), Json.write(on));
    }

    @Test
    void skillsOffAddNothingAnOlderWorkerWouldRejectToTheJobsJson() {
        Job off = approvedExecutionJob(skillsProject("off", "claude-code"));

        assertFalse(off.project().skillsOn());
        assertFalse(Json.write(off).contains("\"skills\""), Json.write(off));
    }

    @Test
    void aCodexProjectWithSkillsOnNeverGetsThem() {
        Job codex = approvedExecutionJob(skillsProject("on", "codex"));

        assertFalse(codex.project().skillsOn(), "Codex cannot load the dispatch plugin");
    }

    @Test
    void aPlanJobCarriesTheSkillsToo() {
        queue("Fix the login timeout");
        coordinator(projects(List.of(skillsProject("on", "claude-code"))), remember(JobResult.succeeded(agentResult(PLAN_JSON))))
                .execute(claim());

        assertTrue(given.get().project().skillsOn());
    }

    private static Config.Project skillsProject(String skills, String agent) {
        return new Config.Project("alm", null, "git@github.com:acme/alm.git", "/home/bold/alm", "main", agent, null, null,
                List.of(), null, null, null, null, "off", skills);
    }
```

In `JobJsonTest`:

```java
    @Test
    void aJobsSkillsSurviveJsonAndAJobWithoutThemHasThemOff() throws Exception {
        Job on = new Job(7, 2, RunKind.EXECUTE,
                new Job.Project("alm", "git@github.com:acme/alm.git", null, "main", "claude-code", List.of(), null, null, true),
                "main", "6f3030a", "/w/7", null, UUID.randomUUID(), false, "p", null, null, 1L, null, List.of(), "s", List.of(),
                null, null);
        assertEquals(on, Json.MAPPER.readValue(Json.write(on), Job.class));

        Job older = new Job(7, 2, RunKind.EXECUTE,
                new Job.Project("alm", "git@github.com:acme/alm.git", null, "main", "claude-code", List.of()),
                "main", "6f3030a", "/w/7", null, UUID.randomUUID(), false, "p", null, null, 1L, null, List.of(), "s", List.of(),
                null, null);
        assertFalse(Json.MAPPER.readValue(Json.write(older), Job.class).project().skillsOn());
    }
```

In `WorkerLoopTest` (reuse `idleLoop`, `repos` and `executeJob`):

```java
    @Test
    void workerKeepsTheJobsSkills() throws Exception {
        WorkerLoop loop = idleLoop("ann-laptop", repos.repo("alm"));

        Job ran = loop.withLocalClone(executeJob(new Job.Project("alm", "git@github.com:acme/alm.git", null, "main",
                "claude-code", List.of(), null, null, true), null)).orElseThrow();

        assertTrue(ran.project().skillsOn(), "a member's computer runs the team's job with its skills");
    }
```

- [ ] **Step 2: Run them to see them fail**

Run: `./mvnw -q test -Dtest='CoordinatorTest,JobJsonTest,WorkerLoopTest'`
Expected: COMPILATION ERROR (no 9-argument `Job.Project`, no `skillsOn()`).

- [ ] **Step 3: Add `skills` to `Job.Project`**

```java
    /** @param path the clone the run works from, null when the worker keeps its own under {@code repos/<name>} */
    public record Project(String name, String repo, String path, String baseBranch, String agent, List<String> copyFiles,
                          @JsonInclude(JsonInclude.Include.NON_NULL) String test,
                          @JsonInclude(JsonInclude.Include.NON_NULL) Boolean loop,
                          @JsonInclude(JsonInclude.Include.NON_NULL) Boolean skills) {

        public Project {
            copyFiles = copyFiles == null ? List.of() : List.copyOf(copyFiles);
        }

        /** A project as a team machine from before the verify loop sends it: no test, loop and skills off. */
        public Project(String name, String repo, String path, String baseBranch, String agent, List<String> copyFiles) {
            this(name, repo, path, baseBranch, agent, copyFiles, null, null, null);
        }

        /** A project as a team machine from before the agent skills sends it: skills off. */
        public Project(String name, String repo, String path, String baseBranch, String agent, List<String> copyFiles,
                       String test, Boolean loop) {
            this(name, repo, path, baseBranch, agent, copyFiles, test, loop, null);
        }

        /** Null, a job from an older team machine, keeps today's behaviour. */
        public boolean loopOn() {
            return Boolean.TRUE.equals(loop);
        }

        /** Whether the run loads the dispatch skills plugin; null (skills off, or an older team machine) means no. */
        public boolean skillsOn() {
            return Boolean.TRUE.equals(skills);
        }
    }
```

- [ ] **Step 4: Set it in `Coordinator.newJob`**

```java
        Job.Project on = new Job.Project(project.name(), project.repo(), project.path(), project.baseBranch(), project.agent(),
                project.copyFiles(), project.loopOn() ? project.test() : null, project.loopOn() ? Boolean.TRUE : null,
                skills(project));
```

and add below `newJob`:

```java
    /**
     * True only where the run's agent can load the dispatch plugin (spec: agent skills); null is left out of the job's JSON,
     * so a worker from before the skills still reads it.
     */
    private static Boolean skills(Config.Project project) {
        return project.skillsOn() && "claude-code".equals(project.agent()) ? Boolean.TRUE : null;
    }
```

- [ ] **Step 5: Keep it on a member's computer**

In `WorkerLoop.withLocalClone`:

```java
        Job.Project project = new Job.Project(job.project().name(), job.project().repo(), mine.path(),
                job.project().baseBranch(), job.project().agent(), job.project().copyFiles(), job.project().test(),
                job.project().loop(), job.project().skills());
```

- [ ] **Step 6: Run the tests**

Run: `./mvnw -q test -Dtest='CoordinatorTest,JobJsonTest,WorkerLoopTest,JobRunnerTest,RemoteWorkersTest,WorkerProtocolTest'`
Expected: PASS.

- [ ] **Step 7: Commit**

```bash
git add src/main/java/dispatch/core/Job.java src/main/java/dispatch/core/Coordinator.java \
        src/main/java/dispatch/worker/WorkerLoop.java src/test/java/dispatch/core/CoordinatorTest.java \
        src/test/java/dispatch/core/JobJsonTest.java src/test/java/dispatch/worker/WorkerLoopTest.java
git commit -m "A Claude Code project's job says its skills are on, and a member's worker keeps that, while a job with skills off adds nothing to its JSON

Co-Authored-By: Claude Opus 5.5 (1M context) <noreply@anthropic.com>
Claude-Session: https://claude.ai/code/session_01HaBofgP3uFdxiejGuF4fkq"
```

---

### Task 3: The plugin is written where task agents run

**Files:**
- Create: `src/main/java/dispatch/core/BundledFiles.java`
- Create: `src/main/java/dispatch/core/SkillsPlugin.java`
- Create: `src/main/resources/skills/dispatch/files.txt`, `src/main/resources/skills/dispatch/.claude-plugin/plugin.json`
- Modify: `src/main/java/dispatch/core/AssistantHome.java` (`install`, `resource`, `FILE_LIST`)
- Modify: `src/main/java/dispatch/workspace/Workspaces.java` (after `runLogBase`, line 108)
- Modify: `src/main/java/dispatch/App.java:120-121`
- Modify: `src/main/java/dispatch/worker/WorkerCommand.java` (after the state dir lock, line ~145)
- Test: `src/test/java/dispatch/core/SkillsPluginTest.java` (new), `src/test/java/dispatch/AppTest.java`, `src/test/java/dispatch/worker/WorkerCommandTest.java`

**Interfaces:**
- Produces:
  - `BundledFiles.list(String root): List<String>`, `BundledFiles.copy(String root, Path dir): void` and `BundledFiles.read(String name): String`. Here `root` is an absolute resource root such as `/skills/dispatch`, and `name` is an absolute resource name.
  - `SkillsPlugin.RESOURCES = "/skills/dispatch"` (package-private), `SkillsPlugin.install(Path dir): void` and `SkillsPlugin.files(): List<String>` (package-private).
  - `Workspaces.skillsPluginDir(): Path` returns `<stateDir>/plugins/dispatch`.

- [ ] **Step 1: Write the failing tests**

`src/test/java/dispatch/core/SkillsPluginTest.java`:

```java
package dispatch.core;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class SkillsPluginTest {

    @TempDir
    Path dir;

    @Test
    void installReplacesAnOlderCopyWholly() throws IOException {
        Path plugin = dir.resolve("plugins/dispatch");
        Files.createDirectories(plugin.resolve("skills/dropped-skill"));
        Files.writeString(plugin.resolve("skills/dropped-skill/SKILL.md"), "an older Dispatch's skill");
        Files.createDirectories(plugin.resolve(".claude-plugin"));
        Files.writeString(plugin.resolve(".claude-plugin/plugin.json"), "{}");

        SkillsPlugin.install(plugin);

        for (String file : SkillsPlugin.files()) {
            assertTrue(Files.isRegularFile(plugin.resolve(file)), file);
        }
        assertFalse(Files.exists(plugin.resolve("skills/dropped-skill")), "a skill no longer shipped is gone");
        assertTrue(Files.readString(plugin.resolve(".claude-plugin/plugin.json")).contains("\"name\": \"dispatch\""));
    }
}
```

In `AppTest`:

```java
    @Test
    void aPersonalBotWritesTheSkillsPluginItsRunsLoad() {
        app = start();

        assertTrue(Files.isRegularFile(config.stateDir().resolve("plugins/dispatch/.claude-plugin/plugin.json")));
    }
```

In `WorkerCommandTest`:

```java
    @Test
    void aWorkerWritesTheSkillsPluginBeforeItContactsTheTeam() throws Exception {
        Path workerFile = dir.resolve("worker.yaml");
        Path stateDir = dir.resolve("worker-state");
        Files.writeString(workerFile, """
                team: https://127.0.0.1:9
                name: ann-laptop
                stateDir: %s
                """.formatted(stateDir));
        SecretsFile.write(SecretsFile.beside(workerFile), Map.of(WorkerCommand.KEY_VARIABLE, "some-key"));
        ByteArrayOutputStream out = new ByteArrayOutputStream();

        int status = new WorkerCommand(new PrintStream(out, true, StandardCharsets.UTF_8))
                .run(new Cli.WorkerRun(workerFile, null), Map.of());

        assertEquals(1, status, out.toString(StandardCharsets.UTF_8));
        assertTrue(out.toString(StandardCharsets.UTF_8).contains("cannot reach"), out.toString(StandardCharsets.UTF_8));
        assertTrue(Files.isRegularFile(stateDir.resolve("plugins/dispatch/.claude-plugin/plugin.json")));
    }
```

- [ ] **Step 2: Run them to see them fail**

Run: `./mvnw -q test -Dtest='SkillsPluginTest,AppTest,WorkerCommandTest'`
Expected: COMPILATION ERROR (`SkillsPlugin` does not exist).

- [ ] **Step 3: Write `BundledFiles`**

```java
package dispatch.core;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/**
 * Text files shipped in the jar under one resource root and listed in its {@code files.txt}, written out at startup: the
 * assistant's home (A-1) and the skills plugin (spec: agent skills).
 */
public final class BundledFiles {

    private static final String FILE_LIST = "files.txt";

    private BundledFiles() {
    }

    /** The files under {@code root}, relative to it, as its {@code files.txt} lists them. */
    public static List<String> list(String root) {
        return read(root + "/" + FILE_LIST).lines().map(String::strip).filter(line -> !line.isEmpty()).toList();
    }

    /** Writes every listed file under {@code dir}, over whatever is there. */
    public static void copy(String root, Path dir) {
        for (String file : list(root)) {
            write(dir.resolve(file), read(root + "/" + file));
        }
    }

    /** @param name an absolute resource name, e.g. {@code /assistant/CLAUDE.md} */
    public static String read(String name) {
        try (InputStream in = BundledFiles.class.getResourceAsStream(name)) {
            if (in == null) {
                throw new IllegalStateException("resource missing: " + name);
            }
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static void write(Path file, String content) {
        try {
            Files.createDirectories(file.getParent());
            Files.writeString(file, content, StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException("cannot write " + file + ": " + e.getMessage(), e);
        }
    }
}
```

- [ ] **Step 4: Move `AssistantHome.install` onto it**

Replace `install()`'s body with `BundledFiles.copy("/assistant", dir);`. Delete the `FILE_LIST` constant and the private `resource(String)` method, which nothing else uses. Keep the private `write(Path, String)`, because `environmentFor` writes the member's command with it. Remove the `java.io.InputStream` import if it is now unused.

- [ ] **Step 5: Write `SkillsPlugin` and its first resources**

```java
package dispatch.core;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Stream;

/**
 * The dispatch plugin of vetted skills that Claude Code's runs load (spec: agent skills): shipped in the jar, written to
 * {@code <stateDir>/plugins/dispatch} when a machine that runs task agents starts, and bound read-only into each sandbox.
 */
public final class SkillsPlugin {

    static final String RESOURCES = "/skills/dispatch";

    private SkillsPlugin() {
    }

    /** Replaces whatever an older Dispatch left in {@code dir}, so a skill it no longer ships does not linger. */
    public static void install(Path dir) {
        deleteTree(dir);
        BundledFiles.copy(RESOURCES, dir);
    }

    /** The plugin's files, relative to its dir. */
    static List<String> files() {
        return BundledFiles.list(RESOURCES);
    }

    /** Agents never see the state dir, so nothing in it is a link they planted; Files.walk does not follow links anyway. */
    private static void deleteTree(Path dir) {
        if (!Files.exists(dir, LinkOption.NOFOLLOW_LINKS)) {
            return;
        }
        try (Stream<Path> paths = Files.walk(dir)) {
            for (Path path : paths.sorted(Comparator.reverseOrder()).toList()) {
                Files.delete(path);
            }
        } catch (IOException e) {
            throw new UncheckedIOException("cannot replace " + dir + ": " + e.getMessage(), e);
        }
    }
}
```

`src/main/resources/skills/dispatch/files.txt`:

```
.claude-plugin/plugin.json
```

`src/main/resources/skills/dispatch/.claude-plugin/plugin.json`:

```json
{
  "name": "dispatch",
  "version": "1.0.0",
  "description": "Vetted engineering skills for Dispatch's unattended Claude Code runs, adapted from superpowers 6.4.1",
  "license": "MIT"
}
```

- [ ] **Step 6: Name its place in `Workspaces`**

After `runLogBase`:

```java
    /**
     * The dispatch skills plugin Claude Code's runs load (spec: agent skills). Hidden from agents with the rest of the
     * state dir; each run's sandbox binds it back read-only.
     */
    public Path skillsPluginDir() {
        return stateDir.resolve("plugins").resolve("dispatch");
    }
```

- [ ] **Step 7: Write it at startup**

In `App.start`, right after the `createDirectories()`/`createTeamDirectories()` statement:

```java
        if (config.workers() == null) {
            // This machine runs the tasks' agents, which load the vetted skills from here (spec: agent skills).
            SkillsPlugin.install(workspaces.skillsPluginDir());
        }
```

In `WorkerCommand.run`, inside the `try` that holds the state dir lock and before `new WorkerClient(...)`:

```java
            try {
                // This computer runs its member's agents, which load the vetted skills from here (spec: agent skills).
                SkillsPlugin.install(workspaces.skillsPluginDir());
            } catch (java.io.UncheckedIOException e) {
                out.println(e.getMessage());
                return 1;
            }
```

Add `import dispatch.core.SkillsPlugin;` to both files.

- [ ] **Step 8: Run the tests**

Run: `./mvnw -q test -Dtest='SkillsPluginTest,AppTest,WorkerCommandTest,AssistantTest'`
Expected: PASS. `AssistantTest` proves the assistant's home is still written.

- [ ] **Step 9: Commit**

```bash
git add src/main/java/dispatch/core/BundledFiles.java src/main/java/dispatch/core/SkillsPlugin.java \
        src/main/java/dispatch/core/AssistantHome.java src/main/java/dispatch/workspace/Workspaces.java \
        src/main/java/dispatch/App.java src/main/java/dispatch/worker/WorkerCommand.java \
        src/main/resources/skills src/test/java/dispatch/core/SkillsPluginTest.java src/test/java/dispatch/AppTest.java \
        src/test/java/dispatch/worker/WorkerCommandTest.java
git commit -m "A personal instance and a member's worker write the dispatch skills plugin to their state dir at startup, replacing any older copy

Co-Authored-By: Claude Opus 5.5 (1M context) <noreply@anthropic.com>
Claude-Session: https://claude.ai/code/session_01HaBofgP3uFdxiejGuF4fkq"
```

---

### Task 4: Five skills adapted for runs nobody can answer

**Files:**
- Create: under `src/main/resources/skills/dispatch/`: `NOTICE.md`, `LICENSE-superpowers`, `skills/test-driven-development/{SKILL.md,writing-good-tests.md}`, `skills/systematic-debugging/{SKILL.md,root-cause-tracing.md,defense-in-depth.md,condition-based-waiting.md}`, `skills/verification-before-completion/SKILL.md`, `skills/receiving-code-review/SKILL.md`, `skills/code-reviewer/SKILL.md`
- Modify: `src/main/resources/skills/dispatch/files.txt`
- Test: `src/test/java/dispatch/core/SkillsPluginTest.java`

**Interfaces:**
- Consumes: `SkillsPlugin.files()`, `SkillsPlugin.RESOURCES`, `BundledFiles.read` (Task 3).
- Produces: the skill directories that the notes in Task 6 name: `test-driven-development`, `systematic-debugging`, `verification-before-completion`, `receiving-code-review`, `code-reviewer`.

- [ ] **Step 1: Write the failing tests** (in `SkillsPluginTest`; add imports `java.util.List`, `java.util.Locale`)

```java
    /** Every skill an agent reads is adapted: none sends it to a person, nor names the upstream plugin's skills. */
    @Test
    void noBundledSkillAsksForAPersonOrNamesTheUpstreamPlugin() {
        List<String> skills = SkillsPlugin.files().stream().filter(file -> file.startsWith("skills/")).toList();
        assertTrue(skills.size() >= 5, skills.toString());
        for (String file : skills) {
            String text = BundledFiles.read(SkillsPlugin.RESOURCES + "/" + file).toLowerCase(Locale.ROOT);
            for (String phrase : List.of("human partner", "your partner", "ask the user", "superpowers:")) {
                assertFalse(text.contains(phrase), file + " still says '" + phrase + "'");
            }
        }
    }

    @Test
    void eachSkillIsNamedAfterItsDirectory() {
        List<String> skills = SkillsPlugin.files().stream().filter(file -> file.endsWith("/SKILL.md")).toList();
        assertEquals(List.of("skills/test-driven-development/SKILL.md", "skills/systematic-debugging/SKILL.md",
                "skills/verification-before-completion/SKILL.md", "skills/receiving-code-review/SKILL.md",
                "skills/code-reviewer/SKILL.md"), skills);
        for (String file : skills) {
            String name = file.substring("skills/".length(), file.length() - "/SKILL.md".length());
            String text = BundledFiles.read(SkillsPlugin.RESOURCES + "/" + file);
            assertTrue(text.startsWith("---\nname: " + name + "\ndescription: "), file);
        }
    }
```

(Add `import static org.junit.jupiter.api.Assertions.assertEquals;`.)

- [ ] **Step 2: Run them to see them fail**

Run: `./mvnw -q test -Dtest=SkillsPluginTest`
Expected: FAIL. `files.txt` lists no skills yet, so the `>= 5` assertion and the list assertion both fail.

- [ ] **Step 3: Copy and adapt the upstream files**

Run this once from the worktree root. It copies each file and applies every replacement, failing loudly if any replacement does not match exactly once. Do not commit the script.

~~~python
import pathlib, shutil
UP = pathlib.Path('/home/zb/.claude/plugins/cache/claude-plugins-official/superpowers/6.4.1')
OUT = pathlib.Path('src/main/resources/skills/dispatch')

def adapt(rel_from, rel_to, *pairs):
    text = (UP / 'skills' / rel_from).read_text()
    for old, new in pairs:
        assert text.count(old) == 1, (rel_from, old)
        text = text.replace(old, new)
    target = OUT / 'skills' / rel_to
    target.parent.mkdir(parents=True, exist_ok=True)
    target.write_text(text)

adapt('test-driven-development/SKILL.md', 'test-driven-development/SKILL.md',
      ('**Exceptions (ask your human partner):**',
       '**Exceptions (only where the approved plan allows them; name each in your summary):**'),
      ("| Don't know how to test | Write wished-for API. Write assertion first. Ask your human partner. |",
       "| Don't know how to test | Write wished-for API. Write assertion first. Still stuck: say so in your summary. |"),
      ("No exceptions without your human partner's permission.",
       'No exceptions beyond what the approved plan allows; name every exception in your summary.'))
adapt('test-driven-development/writing-good-tests.md', 'test-driven-development/writing-good-tests.md',
      ("agent's behavior (superpowers:writing-skills); prose for humans earns no",
       "agent's behavior; prose for humans earns no"),
      ("**your human partner's correction:** \"Are we testing the behavior of a",
       "**A reviewer's question:** \"Are we testing the behavior of a"),
      ("components. **your human partner's question:** \"Do we need to be using a",
       "components. **A reviewer's question:** \"Do we need to be using a"))
adapt('systematic-debugging/SKILL.md', 'systematic-debugging/SKILL.md',
      ('`superpowers:test-driven-development`', '`dispatch:test-driven-development`'),
      ('`superpowers:verification-before-completion`', '`dispatch:verification-before-completion`'),
      ("   - DON'T attempt Fix #4 without architectural discussion",
       "   - DON'T attempt Fix #4: stop as step 5 says"),
      ('   **Discuss with your human partner before attempting more fixes**',
       '   **Stop fixing: keep the change as it is, and say in your summary which root cause is unresolved and why the architecture is in doubt**'),
      ("## your human partner's Signals You're Doing It Wrong\n\n**Watch for these redirections:**",
       "## Signals You're Doing It Wrong\n\n**Watch for these in a correction, a review finding or your own reasoning:**"))
adapt('systematic-debugging/root-cause-tracing.md', 'systematic-debugging/root-cause-tracing.md',
      ("Use the bisection script `find-polluter.sh` in this directory:\n\n```bash\n"
       "bash ./find-polluter.sh '.git' 'src/**/*.test.ts'\n```\n\n"
       "Runs tests one-by-one, stops at first polluter. See script for usage.",
       "Run the test files one at a time, or in halves to bisect, and check after each run whether the pollution "
       "appeared: the first file after which it appears is the polluter."))
adapt('systematic-debugging/defense-in-depth.md', 'systematic-debugging/defense-in-depth.md')
adapt('systematic-debugging/condition-based-waiting.md', 'systematic-debugging/condition-based-waiting.md',
      ("See `condition-based-waiting-example.ts` in this directory for complete implementation with domain-specific "
       "helpers (`waitForEvent`, `waitForEventCount`, `waitForEventMatch`) from actual debugging session.\n\n", ''))
adapt('verification-before-completion/SKILL.md', 'verification-before-completion/SKILL.md')
shutil.copyfile(UP / 'LICENSE', OUT / 'LICENSE-superpowers')
print('ok')
~~~

Expected output: `ok`.

- [ ] **Step 4: Write `skills/receiving-code-review/SKILL.md`** (a rewrite; the upstream skill is a conversation with a person)

~~~markdown
---
name: receiving-code-review
description: Use when a reviewer's findings come back to you to fix, before changing any code for them - check each finding against the code, fix the real ones one at a time, and leave the code alone for a finding you have shown is wrong
---

# Receiving Code Review

## Overview

A review finding is a claim about the code, not an order. Check it before you act on it.

**Core principle:** Verify before implementing. Technical correctness over agreement.

## The Response Pattern

```
FOR the list of findings:

1. READ: The whole list first; findings may be related
2. UNDERSTAND: Restate each one in your own words
3. VERIFY: Check it against the code and the approved plan
4. EVALUATE: Is it right for THIS codebase?
5. ACT: Fix it, or leave the code and record why the finding is wrong
6. TEST: One finding at a time, test each fix
```

## Checking a Finding

Before changing code for a finding, check:
1. Is it technically correct for this codebase?
2. Would the change break existing behaviour?
3. Is there a reason for the current implementation (a test, a comment, a caller)?
4. Does it work on every platform and version the project supports?
5. Did the reviewer have the full context (the plan, the callers, the tests)?

**A finding you cannot verify:** take the most reasonable reading, act on it, and say in your summary what you could not verify.

**A finding that conflicts with the approved plan:** follow the plan, and say in your summary which finding you left and why.

## YAGNI Check

```
IF a finding asks to "implement it properly" or to add a feature:
  grep the codebase for actual usage
  IF unused: do not build it; say so in your summary
  IF used: implement it properly
```

## Implementation Order

```
1. Read and check every finding first
2. Then fix, in this order:
   - Breakage and security
   - Simple fixes (typos, imports)
   - Complex fixes (logic, structure)
3. Test each fix on its own
4. Check for regressions
```

## When a Finding Is Wrong

Leave the code as it is when the finding:
- would break existing behaviour
- misses context the reviewer did not have
- asks for something nothing uses (YAGNI)
- is technically incorrect for this stack
- conflicts with the approved plan

Never change code to satisfy a finding you have shown is wrong. In your summary, name the finding and give the technical reason in one line, pointing at the code or test that shows it.

## Reporting

Your summary says what you fixed and what you left:
```
✅ "Fixed: null check in OrderService.cancel (finding 2)."
✅ "Left finding 3: the legacy path is still called by ImportJob (ImportJob.java:88)."

❌ "You're absolutely right!"
❌ "Great point!"
❌ Thanks, apologies or praise
```

Actions speak: the diff shows what you took from the review.

## Common Mistakes

| Mistake | Fix |
|---------|-----|
| Blind implementation | Verify against the code first |
| Assuming the reviewer is right | Check whether the change breaks things |
| Batch without testing | One at a time, test each |
| Silently skipping a finding | Name it and the reason in your summary |
| Unverifiable finding, no word about it | Act on the most reasonable reading and say what you could not verify |
~~~

- [ ] **Step 5: Write `skills/code-reviewer/SKILL.md`** (rewritten from `requesting-code-review/code-reviewer.md`, which is a template for starting a subagent)

~~~markdown
---
name: code-reviewer
description: Use when you review another agent's change against its task and approved plan in a read-only review run, before you answer with the review verdict and findings
---

# Code Reviewer

You review a change another agent made, against the task and the plan the team approved. Find what is wrong before it reaches the team.

## Read-Only Review

Change nothing: no edits, no commits, and no git command that changes the working tree, the index, HEAD or a branch. Read with `git show`, `git diff`, `git log` and plain file reads.

Do the whole review yourself, in passes if the diff is large. Never start a subagent or ask for a second opinion.

## What to Check

**Plan alignment:**
- Does the change do all of what the task and the approved plan ask?
- Is each deviation from the plan a justified improvement rather than a departure?

**Correctness:**
- Bugs, edge cases, null and empty inputs, invalid states
- Errors handled and never swallowed, with useful context where they are logged
- Input validated where it enters

**Security:**
- Injection, secrets in code or logs, unsafe defaults, missing authorisation

**Performance:**
- Query count and N+1, index use, pagination, projection, memory, network round trips

**Tests:**
- Do the tests check real behaviour, not mocks?
- Are the edge cases covered, and do the tests pass?

**Production readiness:**
- A migration when a schema changed; backward compatibility; the documentation the change needs

## The Plan Is a Vision Document

The plan says what the software must do; it does not list every input or condition the software will meet. For behaviour the plan is silent on, judge by what a reasonable person using the software would expect: that expectation is a requirement, and the plan's silence is not permission. Grade such a finding by its effect on that person.

## Calibration

Grade by actual severity:
- **blocking**: the change is wrong, unsafe, breaks something, or misses part of the plan.
- **minor**: everything else: style, naming, small improvements, polish.

Not everything is blocking. A finding about the plan itself rather than the change is minor, and says so.

## Each Finding

- The file and line
- What is wrong, and why it matters
- How to fix it, when that is not obvious

At most 20 findings, the most severe first. Be specific: never "improve error handling" without the place and the case.

## Rules

**Do:** read the code before you judge it; be specific; give a clear verdict.

**Don't:** say "looks good" without checking; mark a nitpick blocking; judge code you did not read.

## Your Answer

Answer only through the structured output: the verdict `ok` when nothing is blocking, otherwise `changes`, and the findings.
~~~

- [ ] **Step 6: Write `NOTICE.md`**

~~~markdown
# The dispatch plugin

The skills in this plugin are adapted from superpowers 6.4.1 (https://github.com/obra/superpowers), copyright (c) 2025
Jesse Vincent, under the MIT licence in `LICENSE-superpowers`. Dispatch's runs have nobody to answer a question, so each
skill was changed where it sent the agent to a person; everything else is the upstream text.

| File | Upstream (superpowers 6.4.1) | Changed |
|---|---|---|
| `skills/test-driven-development/SKILL.md` | same path | Exceptions only where the approved plan allows them, named in the summary; stuck means saying so in the summary |
| `skills/test-driven-development/writing-good-tests.md` | same path | The anecdotes are a reviewer's questions; no reference to the skill-writing skill |
| `skills/systematic-debugging/SKILL.md` | same path | Skill names are `dispatch:`; after three failed fixes the agent stops and reports the unresolved root cause in its summary; the warning signals come from a correction, a review finding or its own reasoning |
| `skills/systematic-debugging/root-cause-tracing.md` | same path | Bisecting by hand instead of `find-polluter.sh`, which is not shipped |
| `skills/systematic-debugging/defense-in-depth.md` | same path | None |
| `skills/systematic-debugging/condition-based-waiting.md` | same path | No pointer to `condition-based-waiting-example.ts`, which is not shipped |
| `skills/verification-before-completion/SKILL.md` | same path | None |
| `skills/receiving-code-review/SKILL.md` | same path | Rewritten for findings that come back in a run: verify each, fix the real ones, leave the wrong ones and say why in the summary |
| `skills/code-reviewer/SKILL.md` | `skills/requesting-code-review/code-reviewer.md` | Rewritten as a skill for a read-only reviewer that answers with Dispatch's review schema (`blocking` or `minor`) |

To update: copy the newer upstream files, re-apply these changes, update the version here and in `plugin.json`'s
description, and review the diff.
~~~

- [ ] **Step 7: List the files**

`src/main/resources/skills/dispatch/files.txt` becomes:

```
.claude-plugin/plugin.json
NOTICE.md
LICENSE-superpowers
skills/test-driven-development/SKILL.md
skills/test-driven-development/writing-good-tests.md
skills/systematic-debugging/SKILL.md
skills/systematic-debugging/root-cause-tracing.md
skills/systematic-debugging/defense-in-depth.md
skills/systematic-debugging/condition-based-waiting.md
skills/verification-before-completion/SKILL.md
skills/receiving-code-review/SKILL.md
skills/code-reviewer/SKILL.md
```

- [ ] **Step 8: Run the tests**

Run: `./mvnw -q test -Dtest=SkillsPluginTest`
Expected: PASS (3 tests).

- [ ] **Step 9: Commit**

```bash
git add src/main/resources/skills src/test/java/dispatch/core/SkillsPluginTest.java
git commit -m "The dispatch plugin ships five superpowers skills adapted for runs nobody can answer: TDD, systematic debugging, verification, receiving a review and a code reviewer

Co-Authored-By: Claude Opus 5.5 (1M context) <noreply@anthropic.com>
Claude-Session: https://claude.ai/code/session_01HaBofgP3uFdxiejGuF4fkq"
```

---

### Task 5: A run with a plugin loads it, may invoke its skills and logs which it used

**Files:**
- Modify: `src/main/java/dispatch/agent/RunRequest.java`
- Modify: `src/main/java/dispatch/agent/claude/ClaudeCodeAgent.java` (`start` line 57, `commandLine` lines 94-111)
- Modify: `src/main/java/dispatch/agent/claude/StreamParser.java` (constructor, `accept`)
- Modify: `src/main/java/dispatch/agent/sandbox/SandboxPolicies.java:69`
- Test: `src/test/java/dispatch/agent/claude/ClaudeCodeAgentTest.java`, `src/test/java/dispatch/agent/claude/StreamParserTest.java`, `src/test/java/dispatch/agent/sandbox/SandboxPoliciesTest.java`

**Interfaces:**
- Produces:
  - `RunRequest` gains a LAST component `List<Path> pluginDirs`. It is never null, because the compact constructor maps null to `List.of()`. The 10-argument and 11-argument constructors keep working and pass `List.of()`.
  - `StreamParser(String expectedPermissionMode, String requestedModel, Path workdir, Path logBase)`.

- [ ] **Step 1: Write the failing tests**

In `ClaudeCodeAgentTest` (add `import java.util.Map;` if missing):

```java
    @Test
    void aRunWithAPluginLoadsItAndMayInvokeItsSkills() throws Exception {
        Path plugin = Files.createDirectories(dir.resolve("state/plugins/dispatch"));
        for (RunKind kind : List.of(RunKind.PLAN, RunKind.EXECUTE, RunKind.REVIEW)) {
            agent.start(new RunRequest(kind, workdir, "Do it", UUID.randomUUID(), false, List.of(), new BigDecimal("1"),
                    null, null, dir.resolve("runs/1/" + kind), Map.of(), List.of(plugin))).await();

            List<String> args = Files.readAllLines(workdir.resolve("fake-claude.args"));
            assertEquals(plugin.toString(), valueAfter(args, "--plugin-dir"), kind.name());
            assertTrue(valueAfter(args, "--tools").endsWith(",Skill"), kind + ": " + args);
            assertEquals("project,local", valueAfter(args, "--setting-sources"), "the owner's own plugins still stay out");
        }
    }

    @Test
    void aRunWithoutAPluginGetsNeitherThePluginNorTheSkillTool() throws Exception {
        for (RunKind kind : List.of(RunKind.PLAN, RunKind.EXECUTE, RunKind.REVIEW)) {
            agent.start(new RunRequest(kind, workdir, "Do it", UUID.randomUUID(), false, List.of(), new BigDecimal("1"),
                    null, null, dir.resolve("runs/1/" + kind))).await();

            List<String> args = Files.readAllLines(workdir.resolve("fake-claude.args"));
            assertFalse(args.contains("--plugin-dir"), kind + ": " + args);
            assertFalse(valueAfter(args, "--tools").contains("Skill"), kind + ": " + args);
        }
    }
```

In `StreamParserTest`, add a log-capturing helper and two tests (add `import java.nio.file.Path;` if missing):

```java
    @Test
    void aSkillTheRunInvokesIsLoggedAndASubagentsIsNot() {
        Path logBase = Path.of("/s/runs/7/2");
        StreamParser parser = new StreamParser("auto", null, WORKTREE, logBase);

        String logged = capturingLog(() -> {
            parser.accept("""
                    {"type":"assistant","message":{"model":"claude-opus-5-5","content":[{"type":"tool_use","id":"t1","name":"Skill","input":{"skill":"dispatch:test-driven-development"}}]}}""");
            parser.accept("""
                    {"type":"assistant","parent_tool_use_id":"t9","message":{"model":"claude-opus-5-5","content":[{"type":"tool_use","id":"t2","name":"Skill","input":{"skill":"dispatch:code-reviewer"}}]}}""");
        });

        assertTrue(logged.contains("event=agent.skill skill=dispatch:test-driven-development run=" + logBase), logged);
        assertFalse(logged.contains("dispatch:code-reviewer"), logged);
    }

    @Test
    void aPluginThatDidNotLoadIsLogged() {
        StreamParser parser = new StreamParser("auto", null, WORKTREE, Path.of("/s/runs/7/2"));

        String logged = capturingLog(() -> parser.accept("""
                {"type":"system","subtype":"init","session_id":"s1","permissionMode":"auto","plugin_errors":[{"path":"/s/plugins/dispatch","error":"invalid manifest"}]}"""));

        assertTrue(logged.contains("level=WARN event=agent.plugin_errors"), logged);
        assertTrue(logged.contains("invalid manifest"), logged);
    }

    private static String capturingLog(Runnable action) {
        java.io.PrintStream original = System.out;
        java.io.ByteArrayOutputStream logged = new java.io.ByteArrayOutputStream();
        System.setOut(new java.io.PrintStream(logged, true, java.nio.charset.StandardCharsets.UTF_8));
        try {
            action.run();
        } finally {
            System.setOut(original);
        }
        return logged.toString(java.nio.charset.StandardCharsets.UTF_8);
    }
```

Also change the two existing constructions, at lines 67 and 201, to pass a fourth argument `null`.

In `SandboxPoliciesTest` (add `import java.util.UUID;` and `import java.util.Map;` if missing):

```java
    @Test
    void aRunsPluginDirIsReadOnly() throws IOException {
        Path workdir = Files.createDirectories(root.resolve("work/alm"));
        Path plugin = Files.createDirectories(stateDir.resolve("plugins/dispatch"));
        SandboxPolicies policies = new SandboxPolicies(home, stateDir, List.of(configDir, stateDir));
        RunRequest request = new RunRequest(RunKind.REVIEW, workdir, "prompt", UUID.randomUUID(), false, List.of(), null, null,
                null, workdir.resolve("run"), Map.of(), List.of(plugin));

        SandboxPolicy policy = policies.forRun(request, List.of(".claude"));

        assertTrue(policy.readOnly().contains(plugin), policy.readOnly().toString());
        assertFalse(policy.writable().contains(plugin), policy.writable().toString());
        assertTrue(policy.hidden().contains(stateDir), "the rest of the state dir stays hidden");
    }
```

- [ ] **Step 2: Run them to see them fail**

Run: `./mvnw -q test -Dtest='ClaudeCodeAgentTest,StreamParserTest,SandboxPoliciesTest'`
Expected: COMPILATION ERROR (no 12-argument `RunRequest`, no 4-argument `StreamParser`).

- [ ] **Step 3: Add `pluginDirs` to `RunRequest`**

Add `@param pluginDirs Claude Code plugins this run loads (spec: agent skills); empty for none, ignored by Codex and Gemini CLI` to the Javadoc, then:

```java
public record RunRequest(
        RunKind kind,
        Path workdir,
        String prompt,
        UUID sessionId,
        boolean resume,
        List<Path> readOnlyDirs,
        BigDecimal budgetUsd,
        String model,
        String effort,
        Path logBase,
        Map<String, String> environment,
        List<Path> pluginDirs) {

    public RunRequest {
        pluginDirs = pluginDirs == null ? List.of() : List.copyOf(pluginDirs);
    }

    public RunRequest(RunKind kind, Path workdir, String prompt, UUID sessionId, boolean resume, List<Path> readOnlyDirs,
                      BigDecimal budgetUsd, String model, String effort, Path logBase, Map<String, String> environment) {
        this(kind, workdir, prompt, sessionId, resume, readOnlyDirs, budgetUsd, model, effort, logBase, environment, List.of());
    }

    public RunRequest(RunKind kind, Path workdir, String prompt, UUID sessionId, boolean resume, List<Path> readOnlyDirs,
                      BigDecimal budgetUsd, String model, String effort, Path logBase) {
        this(kind, workdir, prompt, sessionId, resume, readOnlyDirs, budgetUsd, model, effort, logBase, Map.of(), List.of());
    }
}
```

- [ ] **Step 4: Pass the plugin to Claude Code**

In `ClaudeCodeAgent.start`: `new StreamParser(permissionMode, request.model(), request.workdir(), request.logBase())`.

In `commandLine`, after the `--add-dir` loop, and in the switch:

```java
        // The dispatch plugin's vetted skills (spec: agent skills); the owner's own plugins stay out (--setting-sources).
        for (Path dir : request.pluginDirs()) {
            args.addAll(List.of("--plugin-dir", dir.toString()));
        }
        // A listed skill cannot be invoked without the Skill tool (probed on Claude Code 2.1.286).
        String skill = request.pluginDirs().isEmpty() ? "" : ",Skill";
        switch (request.kind()) {
            // Read-only investigation: no subagents or schedulers, just reading files and read-only shell commands.
            case PLAN -> args.addAll(List.of("--tools", "Read,Bash" + skill, "--json-schema", PLAN_SCHEMA));
            // Delivery is Dispatch's job (ADR 0007); the deny rules are a guardrail, not a boundary (ADR 0009).
            // --disallowedTools takes every following argument that is not a flag, so it stays last.
            case EXECUTE -> args.addAll(List.of("--tools", "Read,Edit,Write,Bash" + skill,
                    "--disallowedTools", "Bash(git commit *)", "Bash(git push *)", "Bash(gh *)"));
            // The verify loop's reviewer: read-only like a plan, its own schema (spec: verify loop).
            case REVIEW -> args.addAll(List.of("--tools", "Read,Bash" + skill, "--json-schema", REVIEW_SCHEMA));
```

Leave the SPLIT, ASSISTANT and DELIVER cases as they are.

- [ ] **Step 5: Log the skills a run uses, and a plugin that did not load**

In `StreamParser`: add the field `private final Path logBase;`. Make the constructor `StreamParser(String expectedPermissionMode, String requestedModel, Path workdir, Path logBase)`, with `@param logBase the run's log base, which names its task, run and step in the log`, and assign it. Add `import dispatch.Log;`. In `accept`, extend the init branch and the tool-use loop:

```java
        if (type.equals("system") && event.path("subtype").asText().equals("init")) {
            initSeen = true;
            sessionId = event.path("session_id").asText(null);
            permissionMode = event.path("permissionMode").asText(null);
            JsonNode pluginErrors = event.path("plugin_errors");
            if (pluginErrors.isArray() && !pluginErrors.isEmpty()) {
                // The run goes on without the skills its prompt names (spec: agent skills).
                Log.warn("agent.plugin_errors", "run", logBase, "errors", pluginErrors.toString());
            }
        } else if (type.equals("assistant")) {
            // A subagent's messages name the tool call that started it; only the run's own model is reported.
            boolean topLevel = !event.hasNonNull("parent_tool_use_id");
            if (topLevel && event.path("message").hasNonNull("model")) {
                models.add(event.path("message").get("model").asText());
            }
            for (JsonNode block : event.path("message").path("content")) {
                if (block.path("type").asText().equals("tool_use")) {
                    activity = new AgentActivity(activity.steps() + 1, describe(block));
                    if (topLevel && block.path("name").asText().equals("Skill")) {
                        Log.info("agent.skill", "skill", block.path("input").path("skill").asText(), "run", logBase);
                    }
                }
            }
        }
```

- [ ] **Step 6: Bind the plugin read-only in the sandbox**

In `SandboxPolicies.forRun`:

```java
        List<Path> readOnly = new ArrayList<>(request.readOnlyDirs());
        // The skills plugin lives in the hidden state dir: readable, never writable, or one agent could rewrite every later run's skills.
        readOnly.addAll(request.pluginDirs());
```

- [ ] **Step 7: Run the tests**

Run: `./mvnw -q test -Dtest='ClaudeCodeAgentTest,StreamParserTest,SandboxPoliciesTest,BubblewrapTest'`
Expected: PASS. (`BubblewrapTest` may be skipped where bubblewrap is missing.)

- [ ] **Step 8: Commit**

```bash
git add src/main/java/dispatch/agent src/test/java/dispatch/agent
git commit -m "A Claude Code run given a plugin loads it with the Skill tool, read-only in its sandbox, and the log says which skills it used and whether the plugin failed to load

Co-Authored-By: Claude Opus 5.5 (1M context) <noreply@anthropic.com>
Claude-Session: https://claude.ai/code/session_01HaBofgP3uFdxiejGuF4fkq"
```

---

### Task 6: The notes that name the skills, and "Speed matters"

**Files:**
- Modify: `src/main/java/dispatch/core/Prompts.java` (`PLAN_FORMAT` lines 13-34, `EXECUTE_RULES` lines 37-49, new nested enum)
- Test: `src/test/java/dispatch/core/PromptsTest.java` (new)

**Interfaces:**
- Consumes: `SkillsPlugin.files()` (Task 3), the skill directories (Task 4).
- Produces: the package-private `enum Prompts.SkillNote { PLAN, EXECUTE, FIX_TEST, FIX_REVIEW, REVIEW }` with `String text()`, `String after()` (returns `"\n" + text + "\n"`) and `String before()` (returns `text + "\n\n"`).

- [ ] **Step 1: Write the failing tests**

`src/test/java/dispatch/core/PromptsTest.java`:

```java
package dispatch.core;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dispatch.domain.Phase;
import dispatch.domain.Priority;
import dispatch.domain.Requester;
import dispatch.domain.Run;
import dispatch.domain.RunCause;
import dispatch.domain.RunKind;
import dispatch.domain.RunStatus;
import dispatch.domain.Task;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;

class PromptsTest {

    private static final String PLAN_SPEED = "Speed matters: investigate only what the plan needs.";
    private static final String EXECUTE_SPEED = "Speed matters: read only the code your change touches";

    @Test
    void plansAndCorrectionsSayThatSpeedMatters() {
        assertTrue(Prompts.plan(task()).contains(PLAN_SPEED));
        assertTrue(Prompts.correction(task(), reply()).contains(PLAN_SPEED));
    }

    @Test
    void executionsRetriesAndFollowUpsSayThatSpeedMatters() {
        assertTrue(Prompts.execute(task(), "{\"steps\":[\"Fix add()\"]}").contains(EXECUTE_SPEED));
        assertTrue(Prompts.retry(task(), "Fix add()").contains(EXECUTE_SPEED));
        assertTrue(Prompts.followUp(task(), reply()).contains(EXECUTE_SPEED));
    }

    @Test
    void reviewsFixesSplitsAndTheAssistantAreNotToldToHurry() {
        List<String> others = List.of(Prompts.review(task(), "{}", null),
                Prompts.testFailure("./mvnw -q test", "FooTest failed"),
                Prompts.reviewFindings(List.of(new Review.Finding("blocking", "Calc.java", 3, "wrong sign"))),
                Prompts.split("fix X, add Y"), Prompts.assistant("<dispatch-now/>", "what is running?"));
        for (String prompt : others) {
            assertFalse(prompt.contains("Speed matters"), prompt);
        }
    }

    @Test
    void everySkillANoteNamesShipsInThePlugin() {
        Pattern named = Pattern.compile("dispatch:([a-z-]+)");
        List<String> files = SkillsPlugin.files();
        for (Prompts.SkillNote note : Prompts.SkillNote.values()) {
            Matcher skill = named.matcher(note.text());
            assertTrue(skill.find(), note + " names no skill");
            do {
                assertTrue(files.contains("skills/" + skill.group(1) + "/SKILL.md"), note + " names " + skill.group(1));
            } while (skill.find());
        }
    }

    private static Task task() {
        Instant now = Instant.parse("2026-10-01T10:00:00Z");
        return new Task(1, "calc", "Fix add()", "calc.py: add(2, 3) returns -1. Fix add().", Phase.PLANNING, Priority.NORMAL,
                new Requester("telegram:100", "Bold"), "telegram:100/1", "telegram:100", UUID.randomUUID(), null, "main",
                null, null, null, null, null, null, null, now, null, null, now, null);
    }

    private static Run reply() {
        return new Run(1, 2, RunKind.PLAN, RunCause.CORRECTION, RunStatus.RUNNING, "Also add a test for add(2, 3).",
                "telegram:100", "Bold", null, null, null, null, null, null, null, null);
    }
}
```

- [ ] **Step 2: Run them to see them fail**

Run: `./mvnw -q test -Dtest=PromptsTest`
Expected: COMPILATION ERROR (`Prompts.SkillNote` does not exist).

- [ ] **Step 3: End `PLAN_FORMAT` and `EXECUTE_RULES` with "Speed matters"**

`PLAN_FORMAT`: after the `Language: ... Keep code identifiers, file paths and commands unchanged.` paragraph, before the closing `"""`, add:

```
            Speed matters: investigate only what the plan needs. Search for the code you need instead of reading whole \
            files or directories, stop once you can name the change, and run a build or test only when it is the \
            quickest way to confirm a bug's cause.
```

`EXECUTE_RULES`: after its `Language: ...` paragraph, before the closing `"""`, add:

```
            Speed matters: read only the code your change touches, search instead of reading whole files, run only the \
            tests that cover your change rather than the whole suite, and stop when the work is done: no refactoring, \
            polish or extras beyond it.
```

Put a blank line before each new paragraph, as between the existing ones. Each line ending in `\` must end with a space before the backslash.

- [ ] **Step 4: Add the notes**

Inside `Prompts`, after `EXECUTE_RULES`:

```java
    /**
     * What each agent call is told to use from the dispatch plugin (spec: agent skills). The machine that runs the agent
     * adds it, never the team machine, so a machine without the plugin is never told to use one.
     */
    enum SkillNote {
        PLAN("If the task reports a bug, use the dispatch:systematic-debugging skill to investigate its root cause before "
                + "you plan; change nothing. Put the root cause in findings."),
        EXECUTE("Use the dispatch:test-driven-development skill while you build, and the "
                + "dispatch:verification-before-completion skill before your summary."),
        FIX_TEST("Use the dispatch:systematic-debugging skill to find the root cause before you change code, then the "
                + "dispatch:verification-before-completion skill."),
        FIX_REVIEW("Use the dispatch:receiving-code-review skill: check each finding against the code before you change "
                + "anything."),
        REVIEW("Use the dispatch:code-reviewer skill, then answer only through the structured output.");

        private final String text;

        SkillNote(String text) {
            this.text = text;
        }

        String text() {
            return text;
        }

        /** The note after a prompt, on its own line. */
        String after() {
            return "\n" + text + "\n";
        }

        /** The note before a prompt, followed by a blank line. */
        String before() {
            return text + "\n\n";
        }
    }
```

- [ ] **Step 5: Run the tests**

Run: `./mvnw -q test -Dtest='PromptsTest,LiveAgentsTest'`
Expected: PASS. (`LiveAgentsTest` is skipped without its environment variables, but it compiles against `Prompts`.)

- [ ] **Step 6: Commit**

```bash
git add src/main/java/dispatch/core/Prompts.java src/test/java/dispatch/core/PromptsTest.java
git commit -m "Every plan and execution prompt says that speed matters, and each kind of agent call has a note naming the dispatch skills it should use

Co-Authored-By: Claude Opus 5.5 (1M context) <noreply@anthropic.com>
Claude-Session: https://claude.ai/code/session_01HaBofgP3uFdxiejGuF4fkq"
```

---

### Task 7: The runner hands every call its plugin and its note

**Files:**
- Modify: `src/main/java/dispatch/core/JobRunner.java` (`plan` lines 130-147, `implement` lines 149-200, `loopAgents` lines 210-230, `request` lines 441-444, new `plugins` and `skillNote`)
- Modify: `src/main/java/dispatch/core/VerifyLoop.java` (`Setup` line 67, test fix line 181, review line 192, review fix line 228)
- Test: `src/test/java/dispatch/core/VerifyLoopTest.java`, `src/test/java/dispatch/core/JobRunnerTest.java`

**Interfaces:**
- Consumes: `Job.Project.skillsOn()` (Task 2), `Workspaces.skillsPluginDir()` and `SkillsPlugin.install(Path)` (Task 3), the 12-argument `RunRequest` (Task 5), and `Prompts.SkillNote` (Task 6).
- Produces: `VerifyLoop.Setup` gains a LAST component `boolean skills`.

- [ ] **Step 1: Write the failing `VerifyLoopTest` tests**

Add the fields `private boolean skills;` and `private final List<String> prompts = new ArrayList<>();`. Add `prompts.add(prompt);` as the first line of both the `fix` and `review` fakes. In the `run(String, Duration, BigDecimal, BigDecimal)` helper, pass `skills` as the new last `Setup` argument. Then add:

```java
    @Test
    void withSkillsEachFixAndTheReviewAreToldWhichSkillToUse() {
        skills = true;
        testResults.add(false);
        reviewAnswer = BLOCKING_AND_MINOR;

        run();

        assertEquals(List.of("test", "fix", "test", "review", "fix", "test"), calls);
        assertTrue(prompts.get(0).endsWith(Prompts.SkillNote.FIX_TEST.after()), prompts.get(0));
        assertTrue(prompts.get(1).startsWith(Prompts.SkillNote.REVIEW.before()), prompts.get(1));
        assertTrue(prompts.get(2).endsWith(Prompts.SkillNote.FIX_REVIEW.after()), prompts.get(2));
    }

    @Test
    void withoutSkillsNoLoopPromptNamesASkill() {
        testResults.add(false);
        reviewAnswer = BLOCKING_AND_MINOR;

        run();

        assertEquals(3, prompts.size());
        prompts.forEach(prompt -> assertFalse(prompt.contains("dispatch:"), prompt));
    }
```

- [ ] **Step 2: Write the failing `JobRunnerTest` tests**

Add the fields `private boolean skills;` and `private final List<RunRequest> loopRequests = new CopyOnWriteArrayList<>();`. Make `loopRequests.add(request);` the first line of `ScriptedAgent.start`. In `executeWithLoop`, build the project as `new Job.Project("alm", repos.origin.toString(), null, "main", "claude-code", List.of(), "./mvnw -q test", loop, skills ? Boolean.TRUE : null)`. Then add:

```java
    @Test
    void withSkillsEveryCallOfAnExecutionGetsThePluginAndItsNote() throws Exception {
        skills = true;
        SkillsPlugin.install(workspaces.skillsPluginDir());
        answers.put(RunKind.EXECUTE, answer(null, "1.00"));
        answers.put(RunKind.REVIEW, answer("{\"verdict\":\"ok\",\"findings\":[]}", "0.20"));
        Deque<Integer> exitCodes = new ArrayDeque<>(List.of(1, 0));

        JobResult result = executeWithLoop(true, (command, workdir, log, timeout, stop, started) ->
                new TestRunner.TestRun(exitCodes.pop(), false, false, "FooTest failed"));

        assertEquals(JobResult.Outcome.SUCCEEDED, result.outcome(), result.failureDetail());
        assertEquals(List.of(RunKind.EXECUTE, RunKind.EXECUTE, RunKind.REVIEW), agentKindsStarted);
        for (RunRequest request : loopRequests) {
            assertEquals(List.of(workspaces.skillsPluginDir()), request.pluginDirs(), request.kind() + " resume=" + request.resume());
        }
        assertTrue(loopRequests.get(0).prompt().endsWith(Prompts.SkillNote.EXECUTE.after()), loopRequests.get(0).prompt());
        assertTrue(loopRequests.get(1).prompt().endsWith(Prompts.SkillNote.FIX_TEST.after()), "the fix is resumed with its own note");
        assertTrue(loopRequests.get(2).prompt().startsWith(Prompts.SkillNote.REVIEW.before()), loopRequests.get(2).prompt());
    }

    @Test
    void withoutSkillsNoCallGetsThePluginOrANote() throws Exception {
        answers.put(RunKind.EXECUTE, answer(null, "1.00"));
        answers.put(RunKind.REVIEW, answer("{\"verdict\":\"ok\",\"findings\":[]}", "0.20"));

        executeWithLoop(true, (command, workdir, log, timeout, stop, started) -> new TestRunner.TestRun(0, false, false, "ok"));

        assertEquals(2, loopRequests.size());
        for (RunRequest request : loopRequests) {
            assertEquals(List.of(), request.pluginDirs());
            assertFalse(request.prompt().contains("dispatch:"), request.prompt());
        }
    }

    @Test
    void aSkillsJobOnAMachineWithoutThePluginFailsAsSetupWithoutAnAgent() {
        skills = true;

        JobResult result = executeWithLoop(true, (command, workdir, log, timeout, stop, started) -> {
            throw new AssertionError("no test runs without the plugin");
        });

        assertEquals(FailureReason.SETUP, result.failureReason());
        assertTrue(result.failureDetail().contains("skills plugin missing at " + workspaces.skillsPluginDir()),
                result.failureDetail());
        assertEquals(List.of(), agentKindsStarted);
    }

    @Test
    void aPlanWithSkillsStartsClaudeWithThePluginAndThePlanNote() throws Exception {
        SkillsPlugin.install(workspaces.skillsPluginDir());

        JobResult result = runner.run(withSkills(job(RunKind.PLAN, 1, "Plan this: fix the login timeout", null, null, null)),
                events, control);

        assertEquals(JobResult.Outcome.SUCCEEDED, result.outcome(), result.failureDetail());
        Path worktree = Path.of(events.worktree);
        List<String> args = Files.readAllLines(worktree.resolve("fake-claude.args"));
        assertEquals(workspaces.skillsPluginDir().toString(), args.get(args.indexOf("--plugin-dir") + 1));
        assertEquals("Read,Bash,Skill", args.get(args.indexOf("--tools") + 1));
        assertTrue(Files.readString(worktree.resolve("fake-claude.prompt")).endsWith(Prompts.SkillNote.PLAN.after()));
    }

    /** A copy of {@code job} whose project has skills on, as the team machine sends a Claude Code project's job. */
    private static Job withSkills(Job job) {
        Job.Project p = job.project();
        return new Job(job.taskId(), job.seq(), job.kind(), new Job.Project(p.name(), p.repo(), p.path(), p.baseBranch(),
                p.agent(), p.copyFiles(), p.test(), p.loop(), true), job.baseBranch(), job.baseSha(), job.worktree(),
                job.prUrl(), job.sessionId(), job.resume(), job.prompt(), job.model(), job.effort(), job.timeoutMillis(),
                job.budgetUsd(), job.attachments(), job.commitSubject(), job.commitTrailers(), job.deliverySummary(),
                job.branch(), job.reviewPrompt(), job.expectedHead());
    }
```

(Add imports as needed: `dispatch.agent.RunRequest`, `dispatch.domain.FailureReason`, `java.util.ArrayDeque`, `java.util.Deque`. The class already imports most of them.)

- [ ] **Step 3: Run them to see them fail**

Run: `./mvnw -q test -Dtest='VerifyLoopTest,JobRunnerTest'`
Expected: COMPILATION ERROR (`VerifyLoop.Setup` has no `skills`).

- [ ] **Step 4: Teach `VerifyLoop` the notes**

`Setup` gains its last component, with a Javadoc line: `@param skills whether the job's skills are on: each fix and the review are told which dispatch skill to use (spec: agent skills)`.

```java
    public record Setup(String testCommand, Path worktree, Path logBase, String reviewPrompt, Instant deadline,
                        BigDecimal budgetUsd, BigDecimal spentUsd, boolean skills) {
    }
```

In the inner `Pass`:

```java
                if (!fix(Prompts.testFailure(setup.testCommand(), tail) + note(Prompts.SkillNote.FIX_TEST))) {
```

```java
            String skill = setup.skills() ? Prompts.SkillNote.REVIEW.before() : "";
            AgentResult result = agents.review(skill + setup.reviewPrompt() + "\n" + capped(agents.diff()), budgetLeft(), timeLeft());
```

```java
            if (fixesLeft == 0 || !fix(Prompts.reviewFindings(parsed.blocking()) + note(Prompts.SkillNote.FIX_REVIEW))) {
```

and add to `Pass`:

```java
        /** After a fix prompt: the skill note for it, when the job's skills are on. */
        private String note(Prompts.SkillNote note) {
            return setup.skills() ? note.after() : "";
        }
```

- [ ] **Step 5: Teach `JobRunner` the plugin and the notes**

Add, near `request`:

```java
    /**
     * The dispatch plugin for a job whose skills are on (spec: agent skills), else none. A missing one means this
     * machine's startup never wrote it: the run fails rather than tell the agent to use skills it cannot load.
     */
    private List<Path> plugins(Job job) {
        if (!job.project().skillsOn()) {
            return List.of();
        }
        Path dir = workspaces.skillsPluginDir();
        if (!Files.isDirectory(dir)) {
            throw new WorkspaceException("skills plugin missing at " + dir + "; restart Dispatch");
        }
        return List.of(dir);
    }

    /** The note naming the plugin's skills for a plan or an execution; added here, beside the plugin, never by the team machine. */
    private static String skillNote(RunKind kind, List<Path> plugins) {
        if (plugins.isEmpty()) {
            return "";
        }
        return (kind == RunKind.PLAN ? Prompts.SkillNote.PLAN : Prompts.SkillNote.EXECUTE).after();
    }

    private RunRequest request(Job job, Path worktree, TaskFiles files, List<Path> plugins) {
        return new RunRequest(job.kind(), worktree, job.prompt() + files.note() + skillNote(job.kind(), plugins),
                job.sessionId(), job.resume(), files.dirs(), job.budgetUsd(), job.model(), job.effort(),
                workspaces.runLogBase(job.taskId(), job.seq()), Map.of(), plugins);
    }
```

(This replaces the old 3-argument `request`.)

In `plan`: declare `List<Path> plugins;` beside `worktree` and `files`, and make `plugins = plugins(job);` the first statement of the `try`, so a missing plugin fails before a worktree is made. Then call `request(job, worktree, files, plugins)`.

In `implement`: likewise declare `List<Path> plugins;` and make `plugins = plugins(job);` the first statement of the first `try`. Call `request(job, worktree, files, plugins)`. Pass `!plugins.isEmpty()` as the new last argument of `new VerifyLoop.Setup(...)`, and `plugins` as a new last argument of `loopAgents(...)`.

In `loopAgents`: add the parameter `List<Path> plugins`, and build both calls with the 12-argument `RunRequest`:

```java
                return call(job, events, control, new RunRequest(RunKind.EXECUTE, worktree, prompt, job.sessionId(), true,
                        files.dirs(), budgetUsd, job.model(), job.effort(), Path.of(logBase + ".fix-" + fixes), Map.of(),
                        plugins), timeout);
```

```java
                return call(job, events, control, new RunRequest(RunKind.REVIEW, worktree, prompt, UUID.randomUUID(), false,
                        files.dirs(), budgetUsd, job.model(), job.effort(), Path.of(logBase + ".review"), Map.of(), plugins),
                        timeout);
```

- [ ] **Step 6: Run the tests**

Run: `./mvnw -q test -Dtest='VerifyLoopTest,JobRunnerTest,TeamWorkersTest,WorkerLoopTest'`
Expected: PASS.

- [ ] **Step 7: Commit**

```bash
git add src/main/java/dispatch/core/JobRunner.java src/main/java/dispatch/core/VerifyLoop.java \
        src/test/java/dispatch/core/VerifyLoopTest.java src/test/java/dispatch/core/JobRunnerTest.java
git commit -m "Every plan, execution, fix and review of a skills job gets the dispatch plugin and the note naming its skills, and a machine without the plugin fails the run as setup

Co-Authored-By: Claude Opus 5.5 (1M context) <noreply@anthropic.com>
Claude-Session: https://claude.ai/code/session_01HaBofgP3uFdxiejGuF4fkq"
```

---

### Task 8: A live execution uses the skills

**Files:**
- Create: `src/test/java/dispatch/core/LiveSkillsTest.java`

**Interfaces:**
- Consumes: `SkillsPlugin.install`, `Prompts.execute`, `Prompts.SkillNote.EXECUTE.after()`, the 12-argument `RunRequest`, and `ClaudeCodeAgent(String, Map, Duration)`.

- [ ] **Step 1: Write the test**

```java
package dispatch.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dispatch.agent.Agent;
import dispatch.agent.AgentOutcome;
import dispatch.agent.AgentResult;
import dispatch.agent.RunRequest;
import dispatch.agent.claude.ClaudeCodeAgent;
import dispatch.domain.Phase;
import dispatch.domain.Priority;
import dispatch.domain.Requester;
import dispatch.domain.RunKind;
import dispatch.domain.Task;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.junit.jupiter.api.io.TempDir;

/**
 * Runs real Claude Code with the dispatch plugin, Dispatch's execution prompt and its skill note, in a scratch repository
 * with a bug to fix (spec: agent skills). Off unless DISPATCH_LIVE_CLAUDE=1, since it spends the account's quota;
 * DISPATCH_LIVE_DIR keeps the run log there.
 */
class LiveSkillsTest {

    @TempDir
    Path dir;

    @Test
    @EnabledIfEnvironmentVariable(named = "DISPATCH_LIVE_CLAUDE", matches = "1")
    @Timeout(value = 15, unit = TimeUnit.MINUTES)
    void anExecutionWithSkillsInvokesTestDrivenDevelopmentAndFixesTheBug() throws Exception {
        Path plugin = dir.resolve("state/plugins/dispatch");
        SkillsPlugin.install(plugin);
        Path repo = scratchRepository();
        Path logs = System.getenv("DISPATCH_LIVE_DIR") != null ? Path.of(System.getenv("DISPATCH_LIVE_DIR"), "skills")
                : dir.resolve("runs");
        Task task = task("calc.py: add(2, 3) returns -1 instead of 5. Fix add().");
        String plan = "{\"understanding\":\"add() subtracts\",\"findings\":[\"calc.py: add returns a - b\"],"
                + "\"steps\":[\"Write a failing unittest for add(2, 3) in test_calc.py\",\"Make add return a + b\"],"
                + "\"risks\":[],\"questions\":[],\"decisions\":[]}";
        Agent claude = new ClaudeCodeAgent("claude", System.getenv(), Duration.ofSeconds(10));

        AgentResult done = claude.start(new RunRequest(RunKind.EXECUTE, repo,
                Prompts.execute(task, plan) + Prompts.SkillNote.EXECUTE.after(), UUID.randomUUID(), false, List.of(), null,
                "sonnet", null, logs.resolve("1"), Map.of(), List.of(plugin))).await();

        System.out.println("LIVE skills: outcome=" + done.outcome() + " model=" + done.model() + " cost=" + done.costUsd()
                + " error=" + done.error() + "\n  summary: " + done.summary());
        assertEquals(AgentOutcome.SUCCEEDED, done.outcome(), done.error());
        String stream = Files.readString(Path.of(logs.resolve("1") + ".jsonl"));
        assertTrue(stream.contains("\"skill\":\"dispatch:test-driven-development\""), "the execution invoked the TDD skill");
        assertEquals("5", python(repo, "from calc import add; print(add(2, 3))").strip());
    }

    private Path scratchRepository() throws Exception {
        Path repo = Files.createDirectories(dir.resolve("worktree"));
        Files.writeString(repo.resolve("calc.py"), "def add(a, b):\n    return a - b\n");
        Files.writeString(repo.resolve("README.md"), "# Calc\n\nA tiny calculator module. Tests: python3 -m unittest\n");
        git(repo, "init", "-q");
        git(repo, "add", "-A");
        // An isolated scratch repository: a throwaway identity for this one commit, not the user's own.
        git(repo, "-c", "user.name=Dispatch test", "-c", "user.email=test@example.com", "commit", "-q", "-m", "init");
        return repo;
    }

    private static Task task(String description) {
        Instant now = Instant.now();
        return new Task(1, "calc", "Fix add()", description, Phase.EXECUTING, Priority.NORMAL,
                new Requester("telegram:100", "Bold"), "telegram:100/1", "telegram:100", UUID.randomUUID(), null, "main",
                null, null, null, null, null, null, null, now, null, null, now, null);
    }

    private static String git(Path repo, String... args) throws Exception {
        List<String> command = new ArrayList<>(List.of("git", "-C", repo.toString()));
        command.addAll(List.of(args));
        return run(command);
    }

    private static String python(Path repo, String code) throws Exception {
        return run(List.of("python3", "-c", "import sys; sys.path.insert(0, '" + repo + "'); " + code));
    }

    private static String run(List<String> command) throws IOException, InterruptedException {
        Process process = new ProcessBuilder(command).redirectErrorStream(true).start();
        String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        if (process.waitFor() != 0) {
            throw new IllegalStateException(String.join(" ", command) + " failed: " + output);
        }
        return output;
    }
}
```

- [ ] **Step 2: It is skipped by default**

Run: `./mvnw -q test -Dtest=LiveSkillsTest`
Expected: PASS with 1 test skipped.

- [ ] **Step 3: Run it for real once**

Run: `DISPATCH_LIVE_CLAUDE=1 ./mvnw -q test -Dtest=LiveSkillsTest`
Expected: PASS, and the printed `LIVE skills:` line shows `outcome=SUCCEEDED`. It costs about $0.30–1.00 on Sonnet. If the skill is not invoked, read `runs/1.jsonl` (or `$DISPATCH_LIVE_DIR/skills/1.jsonl`) for the init event's `skills` and `plugin_errors` before changing anything.

- [ ] **Step 4: Commit**

```bash
git add src/test/java/dispatch/core/LiveSkillsTest.java
git commit -m "An opt-in live test shows a real Claude Code execution with the dispatch plugin invoking test-driven-development and fixing the bug

Co-Authored-By: Claude Opus 5.5 (1M context) <noreply@anthropic.com>
Claude-Session: https://claude.ai/code/session_01HaBofgP3uFdxiejGuF4fkq"
```

---

### Task 9: ADR 0034 and the documents

**Files:**
- Create: `docs/adr/0034-claude-runs-load-a-vetted-skills-plugin.md`
- Modify: `README.md`, `README.en.md`, `SECURITY.md`, `docs/ARCHITECTURE.md`, `docs/superpowers/specs/2026-10-01-agent-skills-design.md` (status line)

- [ ] **Step 1: Write ADR 0034**

~~~markdown
# Claude's runs load a vetted skills plugin

Builds on ADR 0032 (the sandbox) and ADR 0033 (the verify loop).

Claude Code's plan, execute, fix and review runs load `dispatch`, a plugin of five skills that ship with Dispatch:
test-driven-development, systematic-debugging, verification-before-completion, receiving-code-review and code-reviewer.
They are adapted from superpowers 6.4.1 (MIT): wherever a skill sent the agent to a person, it now decides, follows the
approved plan, and says what it decided in its summary, because nobody can answer during a run. `NOTICE.md` in the
plugin lists every change.

The jar carries the plugin. A personal instance and a member's worker write it to `<stateDir>/plugins/dispatch` at
startup, replacing any older copy. The team machine marks a Claude Code project's job with `skills`. The machine that
runs the agent passes `--plugin-dir` and adds `Skill` to `--tools`, and appends a note naming the skills for that call:
root-cause investigation for a plan, TDD and verification for an execution, systematic debugging for a fix after red
tests, receiving-code-review for a fix after blocking findings, and the reviewer checklist for the review. The state dir
is hidden from agents, and the sandbox binds the plugin back read-only. `--setting-sources project,local` stays, so the
owner's own plugins never load into a run. `skills: off`, on the instance or a project, gives the runs without skills.

Every plan and execution prompt also says that speed matters: investigate or read only what the work needs, search
instead of reading whole files, run only the tests that cover the change, and stop when it is done.

We chose this over:
- **Loading whole marketplace plugins.** Their skills assume a person in the loop (a brainstorming hard gate, "ask your
  human partner"), and their hooks would run in unattended runs.
- **Forcing one skill with a slash command** (`/dispatch:<skill>` at the start of the prompt). It is deterministic, but it
  allows only one skill per run and turns Dispatch's whole prompt into that skill's arguments.
- **Pasting the skill text into Dispatch's prompts.** It would work for every agent, but every run would pay for the full
  text, and the skills' supporting files would be lost.
- **Skills an admin picks from a marketplace.** Each admin would vet what they add, and Dispatch would need download,
  pinning and caching. The set changes only by a commit to Dispatch.

Consequences: a skill is listed in every run and costs its full text only where it is invoked. The log says which skills
a run used (`agent.skill`) and when the plugin did not load (`agent.plugin_errors`). With `Skill` on, Claude Code's
built-in skills are listed too and act only through the run's own tools, and a repository's `.claude/skills/` are
trusted like its `CLAUDE.md`. Codex and Gemini CLI runs are unchanged. Claude Code 2.1.76 or later is needed. A job with
skills on needs the team machine and its workers upgraded together.
~~~

- [ ] **Step 2: README (Mongolian)**

In `README.md`'s features table, after the `🔒 **Sandbox**` row:

```
| 🧩 **Skills** | Claude-ийн ажилд шалгагдсан skill-үүд ачаалагдана: эхлээд унах тест (TDD), алдааны үндсэн шалтгааныг олох, дуусгахаасаа өмнө шалгах, review-ийн жагсаалт. Төлөвлөгөө, гүйцэтгэл бүрт «Speed matters» — хурдан ажилла. `skills: off` (instance эсвэл төсөл) skill-үүдийг унтраана |
```

In "⚡ 3 алхамаар эхлэх", at the end of the `**Хэрэгтэй:**` line, add: ` Claude Code 2.1.76+.`

- [ ] **Step 3: README (English)**

In `README.en.md`, after the "### Test and review before the pull request" section, add:

~~~markdown
### Skills in Claude's runs

Claude Code's plans, executions, fixes and reviews load `dispatch`, a plugin of five skills that ships with Dispatch
(ADR 0034): test-driven-development, systematic-debugging, verification-before-completion, receiving-code-review and a
code-reviewer checklist, adapted from superpowers 6.4.1 for runs nobody can answer. Each prompt names the skills for its
step, for example TDD and verification for an execution, and systematic debugging for a fix after red tests. The log
shows which a run used (`grep event=agent.skill`). Every plan and execution prompt also says that speed matters. Skills
are on by default for Claude Code projects; `skills: off` on the instance or a project turns them off. They need Claude
Code 2.1.76 or later. Codex and Gemini CLI projects are unchanged.
~~~

- [ ] **Step 4: SECURITY.md**

In "## Sandbox", after the "**Unsandboxed runs:**" bullet, add:

```
- **Skills:** the dispatch plugin under the state directory is bound read-only into every Claude Code run, so an agent can read the skills and never change them (ADR 0034). With the `Skill` tool on, a repository's own `.claude/skills/` run in its tasks, trusted like its `CLAUDE.md`.
```

- [ ] **Step 5: ARCHITECTURE**

In "## Agent boundary", change the record line to:

```java
record RunRequest(RunKind kind, Path workdir, String prompt, UUID sessionId /* null for SPLIT */, boolean resume,
                  List<Path> readOnlyDirs, BigDecimal budgetUsd, String model, String effort, Path logBase,
                  Map<String, String> environment, List<Path> pluginDirs) {}
```

and add this paragraph below the `ClaudeCodeAgent` table:

```
When the job's skills are on (ADR 0034), PLAN, EXECUTE and REVIEW also get `--plugin-dir <stateDir>/plugins/dispatch` and `Skill` in `--tools`, and the runner appends the note naming the skills for that call.
```

- [ ] **Step 6: Mark the spec built**

Change the spec's status line to: `Status: approved design, 2026-10-01; built on branch agent-skills (see [ADR 0034](../../adr/0034-claude-runs-load-a-vetted-skills-plugin.md)).`

- [ ] **Step 7: The whole build**

Run: `./mvnw -q verify`
Expected: BUILD SUCCESS, every test green.

- [ ] **Step 8: Commit**

```bash
git add docs README.md README.en.md SECURITY.md
git commit -m "ADR 0034, the READMEs, SECURITY.md and the architecture say Claude's runs load a vetted skills plugin and that speed matters

Co-Authored-By: Claude Opus 5.5 (1M context) <noreply@anthropic.com>
Claude-Session: https://claude.ai/code/session_01HaBofgP3uFdxiejGuF4fkq"
```
