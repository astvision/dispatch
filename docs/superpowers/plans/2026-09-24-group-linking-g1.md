# Group Linking (G-1) Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** The owner adds the bot to a Telegram group (or sends it a command in a group it is already in), taps a project, and the group gets that project's announcements — no config editing, no restart, and no worker for a personal Dispatch.

**Architecture:** "Personal" becomes an explicit rule on the config (no admins, one member), so group chats no longer imply a team. A new `GroupWriter` edits `telegram.groups` in the config file under `ConfigFile.edit`'s lock, as `MemberWriter` does; a new `GroupLinks` in core decides who may link and applies the result to the running `Groups`, as `Membership` does for joins. `UpdateHandler` sends the link prompt and routes its buttons; the Mini App lists linked groups and unlinks them.

**Tech Stack:** Java 25 (plain, Maven wrapper), SQLite via `dispatch.store`, SnakeYAML node marks for in-place YAML edits, React 19 + antd 6 + Vitest for the Mini App.

**Spec:** `docs/superpowers/specs/2026-09-24-group-linking-design.md`

## Global Constraints

- Personal ⇔ `telegram.admins` is empty **and** the groups hold exactly one distinct member. Everything else is a team.
- A team with a group chat still requires `workers` (ADR 0021); a personal config never requires it.
- Who may link/unlink: an admin in a team; the one member in a personal Dispatch (`Groups.mayManage`).
- Every group keeps ≥ 1 project; a project belongs to exactly one group (existing `ConfigLoader` rules).
- Config edits go through `ConfigFile.edit` (lock, validate, atomic replace) and keep comments and layout.
- Linking applies to the running bot with `Groups.replace` — no restart. Unlinking from the Mini App is a config save like the others (restart notice).
- Bot texts are Mongolian, in `messages_mn.properties`; privacy mode stays on.
- Telegram `callback_data` is at most 64 bytes.

## Spec addition (agreed while planning)

The bot is often already a member of the group (the user's "note" is), so Telegram sends no "added" event. Therefore: a **message addressed to the bot** (`/anything@<bot>`) in an unknown group, from someone who may manage Dispatch, also sends the link prompt instead of leaving. From anyone else it leaves, as today. A pending prompt for a chat is not sent twice.

## Review Focus

1. A project name too long for `callback_data` — buttons carry the project's index into the list stored with the prompt, never the name (Task 3 tests a 60-character name).
2. A button pressed after the config changed (project removed, chat already linked) — answers "stale" and writes nothing (Task 3).
3. A basic group becoming a supergroup after linking — the chat id is rewritten, announcements keep arriving (Task 3).
4. The owner never pressed Start, so the private prompt is refused — the bot leaves the group instead of sitting there silently (Task 3).
5. Unlinking the only chat of a personal Dispatch — the config still loads (group without chat is valid) and no `workers` is demanded (Task 2 and Task 4 tests).

---

### Task 1: Personal is an explicit rule

**Files:**
- Modify: `src/main/java/dispatch/config/Config.java` (`isTeam`)
- Modify: `src/main/java/dispatch/core/Groups.java` (add `mayManage`, `isPersonal`)
- Modify: `src/main/java/dispatch/config/ConfigLoader.java` (`validateWorkers` message only)
- Test: `src/test/java/dispatch/config/ConfigLoaderTest.java`, `src/test/java/dispatch/core/GroupsTest.java`

**Interfaces:**
- Produces: `Config.isTeam(Config.Telegram)` (new meaning), `Config.isPersonal()`, `Groups.mayManage(String requesterRef)`, `Groups.isPersonal()`.

- [ ] **Step 1: Write the failing tests**

Add to `ConfigLoaderTest` (follow its existing `load(String yaml)`-style helper; if it writes YAML to a temp file, do the same):

```java
    @Test
    void aPersonalBotMayHaveGroupChatsWithoutWorkers() {
        Config config = load(PERSONAL_WITH_CHAT);   // one member, no admins, a group with chatId, no workers block
        assertFalse(config.isTeam());
        assertEquals(-4883391545L, config.telegram().groups().getFirst().chatId());
        assertNull(config.workers());
    }

    @Test
    void aTeamWithAGroupChatStillNeedsWorkers() {
        ConfigException e = assertThrows(ConfigException.class, () -> load(TEAM_WITH_CHAT_NO_WORKERS)); // admins: [100]
        assertTrue(e.getMessage().contains("workers: required"), e.getMessage());
    }
```

`PERSONAL_WITH_CHAT` is `src/test/resources/personal.yaml` with `      chatId: -4883391545` added under `- name: bold`. `TEAM_WITH_CHAT_NO_WORKERS` is the same plus `  admins: [123456789]` under `telegram:`.

Add to `GroupsTest`:

```java
    @Test
    void theOneMemberOfAPersonalBotMayManageItAndAnAdminMayManageATeam() {
        Groups personal = new Groups(new Config.Telegram(List.of(), List.of(
                new Config.Group("bold", -1L, List.of(new Config.Member(100, "Bold")), List.of("alm")))));
        Groups team = new Groups(new Config.Telegram(List.of(100L), List.of(
                new Config.Group("backend", -1L, List.of(new Config.Member(100, "Bold"), new Config.Member(200, "Ali")),
                        List.of("alm")))));

        assertTrue(personal.isPersonal());
        assertTrue(personal.mayManage("telegram:100"));
        assertFalse(personal.mayManage("telegram:999"));
        assertFalse(team.isPersonal());
        assertTrue(team.mayManage("telegram:100"));
        assertFalse(team.mayManage("telegram:200"), "a member who is not an admin");
    }
```

- [ ] **Step 2: Run them and watch them fail**

Run: `./mvnw -q test -Dtest='ConfigLoaderTest,GroupsTest'`
Expected: FAIL — `workers: required` for the personal config; `mayManage` does not compile.

- [ ] **Step 3: Implement**

`Config.java`:

```java
    /**
     * A team: admins decide who joins, or several people share it; its tasks run on their own computers (ADR 0021).
     * A personal bot has one member and no admins (ADR 0014), and its runs happen in this process even when it links
     * group chats for announcements.
     */
    public static boolean isTeam(Telegram telegram) {
        long members = telegram.groups().stream().flatMap(group -> group.members().stream()).mapToLong(Member::id)
                .distinct().count();
        return !telegram.admins().isEmpty() || members > 1;
    }

    public boolean isPersonal() {
        return !isTeam();
    }
```

`ConfigLoader.validateWorkers`: require `workers` when `Config.isTeam(telegram) && telegram.groups().stream().anyMatch(g -> g.chatId() != null)`; message: `"workers: required once a team's group has a chat; each member's tasks then run on their own computer (publicUrl and port, see deploy/example.yaml)"`.

`Groups.java` (keep `admins` as it is stored today; `Groups(List<Group>)` has none):

```java
    /** One member and no admins: a personal bot (ADR 0014). */
    public boolean isPersonal() {
        return admins().isEmpty() && all().stream().flatMap(group -> group.members().stream())
                .mapToLong(Config.Member::id).distinct().count() == 1;
    }

    /** Who may change this Dispatch's setup from Telegram: an admin, or a personal bot's one member. */
    public boolean mayManage(String requesterRef) {
        return isAdmin(requesterRef) || (isPersonal() && isMember(requesterRef));
    }
```

- [ ] **Step 4: Run the config, core and UI suites; fix only tests that assumed "a chat means a team"**

Run: `./mvnw -q test -Dtest='ConfigLoaderTest,GroupsTest,MiniAppServerTest,TelegramAuthTest,ChecksTest,AppTest'`
Expected: PASS. A pre-existing test that builds a *one-member, no-admin* config with a chat and expects "workers: required" is now wrong by design: give it `admins` or a second member so it still tests a team. Do not change what any other test asserts.

- [ ] **Step 5: Commit**

```bash
git add src/main/java/dispatch/config src/main/java/dispatch/core/Groups.java src/test/java/dispatch
git commit -m "Say what a personal bot is instead of inferring team from a group chat"
```

---

### Task 2: GroupWriter — link, unlink and migrate in the config file

**Files:**
- Create: `src/main/java/dispatch/config/GroupWriter.java`
- Modify: `src/main/java/dispatch/config/ConfigText.java` (add `addGroup`)
- Test: `src/test/java/dispatch/config/GroupWriterTest.java`

**Interfaces:**
- Consumes: `ConfigFile.edit(Path, Map<String,String>, UnaryOperator<String>)`, `ConfigFile.parse`, `ConfigEdit.set/remove/append`, `ConfigEdit.At`, `Setup.teamName(String)` (from `dispatch.cli`; if importing `cli` from `config` is refused by the package layout, copy its slug rule into a private `slug` here).
- Produces:

```java
public interface GroupWriter {
    /** @return telegram as the file holds it afterwards */
    Config.Telegram link(long chatId, String title, String project);
    Config.Telegram unlink(String groupName);
    Config.Telegram migrate(long oldChatId, long newChatId);
    static GroupWriter file(Path configFile, Map<String, String> environment);
}
```

- [ ] **Step 1: Write the failing tests** (fixture: `/personal.yaml` exactly as `MemberWriterTest` loads it, token split the same way)

```java
class GroupWriterTest {
    private static final Map<String, String> ENV = Map.of("TELEGRAM_BOT_TOKEN", "123456789" + ":AAH-fake-token-for-tests-only-0123456789");
    @TempDir Path dir;
    private Path file;

    @BeforeEach
    void setUp() throws IOException {
        file = dir.resolve("dispatch.yaml");
        Files.writeString(file, new String(GroupWriterTest.class.getResourceAsStream("/personal.yaml").readAllBytes())
                .replace("STATE_DIR", dir.resolve("state").toString().replace("'", "''"))
                .replace("CLONE", dir.resolve("work/alm").toString().replace("'", "''")));
    }

    @Test
    void theOnlyProjectOfAChatlessGroupLinksInPlace() throws IOException {
        Config.Telegram telegram = GroupWriter.file(file, ENV).link(-4883391545L, "note", "alm");

        assertEquals(1, telegram.groups().size());
        assertEquals(-4883391545L, telegram.groups().getFirst().chatId());
        assertTrue(Files.readString(file).contains("# A personal instance's config"), "comments stay");
        assertNull(ConfigLoader.load(file, ENV).workers(), "still personal: no workers demanded");
    }

    @Test
    void aProjectSharingItsGroupMovesIntoANewGroupForTheChat() throws IOException {
        addSecondProject("crm");   // appends a project "crm" (path CLONE2) to projects and to group bold, by text edit

        Config.Telegram telegram = GroupWriter.file(file, ENV).link(-4883391545L, "Life notes", "alm");

        Config.Group moved = telegram.groups().stream().filter(g -> Long.valueOf(-4883391545L).equals(g.chatId())).findFirst().orElseThrow();
        assertEquals(List.of("alm"), moved.projects());
        assertEquals("life-notes", moved.name());
        assertEquals(List.of(new Config.Member(123456789, "Bold")), moved.members());
        assertEquals(List.of("crm"), telegram.groups().stream().filter(g -> g.name().equals("bold")).findFirst().orElseThrow().projects());
    }

    @Test
    void aSecondProjectForTheSameChatJoinsItsGroup() throws IOException {
        addSecondProject("crm");
        GroupWriter writer = GroupWriter.file(file, ENV);
        writer.link(-4883391545L, "note", "alm");

        Config.Telegram telegram = writer.link(-4883391545L, "note", "crm");

        assertEquals(List.of("alm", "crm"), telegram.groups().stream()
                .filter(g -> Long.valueOf(-4883391545L).equals(g.chatId())).findFirst().orElseThrow().projects());
    }

    @Test
    void unlinkRemovesOnlyTheChatId() {
        GroupWriter writer = GroupWriter.file(file, ENV);
        writer.link(-4883391545L, "note", "alm");

        Config.Telegram telegram = writer.unlink("bold");

        assertNull(telegram.groups().getFirst().chatId());
        assertEquals(List.of("alm"), telegram.groups().getFirst().projects());
    }

    @Test
    void migrateFollowsTheNewChatId() {
        GroupWriter writer = GroupWriter.file(file, ENV);
        writer.link(-4883391545L, "note", "alm");

        assertEquals(-1004883391545L, writer.migrate(-4883391545L, -1004883391545L).groups().getFirst().chatId());
    }

    @Test
    void anUnknownProjectLeavesTheFileAsItWas() throws IOException {
        String before = Files.readString(file);
        assertThrows(ConfigException.class, () -> GroupWriter.file(file, ENV).link(-1L, "x", "nope"));
        assertEquals(before, Files.readString(file));
    }

    /** A second project in group bold, so the group holds two. */
    private void addSecondProject(String name) throws IOException {
        String text = ConfigText.addProject(Files.readString(file), "bold", name, List.of(
                "name: " + name, "path: '" + dir.resolve("work/" + name).toString().replace("'", "''") + "'",
                "baseBranch: main", "agent: claude-code"));
        Files.writeString(file, text);
    }
}
```

- [ ] **Step 2: Run and watch it fail**

Run: `./mvnw -q test -Dtest=GroupWriterTest`
Expected: FAIL — `GroupWriter` does not exist.

- [ ] **Step 3: Implement**

`ConfigText.addGroup(String text, String name, long chatId, List<Config.Member> members, String project)` appends one block-style group to `telegram.groups`, indented like the existing first group item (use `groupList`-style lookup of `telegram.groups`, `lines.prefix(firstItem.getStartMark())` for the `- ` column and `lastLine(lastItem)` for the insertion point; names are written with `quoted(...)`):

```
    - name: 'life-notes'
      chatId: -4883391545
      members:
        - id: 123456789
          name: 'Bold'
      projects:
        - alm
```

`GroupWriter.file(configFile, environment)`: every method is one `ConfigFile.edit(configFile, environment, current -> ...)` returning `.telegram()`:

- `link(chatId, title, project)`: parse `current`; find the group holding `project` (none → `throw new ConfigException("no project " + project + " in any group")`). If some group already has `chatId` → `ConfigEdit.remove` the project from its current group's `projects` (`At.of("telegram","groups").item("name", from).key("projects").value(project)`) and `ConfigEdit.append` it to that chat's group; if the current group is that same group, return `current`. Else if the current group has no chat and `projects().size() == 1` → `ConfigEdit.set(current, At.of("telegram","groups").item("name", from).key("chatId"), Long.toString(chatId))`. Else → remove it from its group and `ConfigText.addGroup(text, uniqueName(slug(title), config), chatId, from.members(), project)`, where `uniqueName` appends `-2`, `-3`… until no group has that name. `ConfigFile.edit` validates the result, so a group left with no project is refused rather than written.
- `unlink(groupName)`: `ConfigEdit.remove(current, At.of("telegram","groups").item("name", groupName).key("chatId"))`; unknown group → `ConfigException`.
- `migrate(oldChatId, newChatId)`: find the group whose `chatId == oldChatId` (none → return `current` unchanged) and `ConfigEdit.set` its `chatId` to `newChatId`.

- [ ] **Step 4: Run it and watch it pass, plus the neighbours**

Run: `./mvnw -q test -Dtest='GroupWriterTest,MemberWriterTest,ConfigTextTest,ConfigEditTest,ConfigFileTest'`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add src/main/java/dispatch/config src/test/java/dispatch/config/GroupWriterTest.java
git commit -m "Write a group's chat into the config: link, unlink and follow a migration"
```

---

### Task 3: The link prompt and its buttons in Telegram

**Files:**
- Create: `src/main/java/dispatch/core/GroupLinks.java`
- Modify: `src/main/java/dispatch/telegram/UpdateHandler.java` (`onMembershipChange`, `ignoreForeignChat`, `onCallback`, the `migrate_to_chat_id` branch, constructor)
- Modify: `src/main/java/dispatch/telegram/Renderer.java`, `src/main/java/dispatch/domain/OutboxKind.java`, `src/main/resources/messages_mn.properties`
- Modify: `src/main/java/dispatch/App.java` (build `GroupLinks` with `GroupWriter.file(configFile, environment)` and pass it to `UpdateHandler`)
- Test: `src/test/java/dispatch/telegram/UpdateHandlerTest.java`, `src/test/java/dispatch/telegram/RendererTest.java`

**Interfaces:**
- Consumes: `GroupWriter` (Task 2), `Groups.mayManage` (Task 1), `Kv.get/put` (`dispatch.store.Kv`), `BotApi.sendMessage(long, Long, String, Long, List<List<Renderer.Button>>)`, `BotApi.leaveChat(long)`.
- Produces:

```java
public final class GroupLinks {
    public enum Result { LINKED, DECLINED, NOT_ALLOWED, STALE, CONFIG_FAILED }
    public GroupLinks(Groups groups, GroupWriter writer, Clock clock, Runnable wakeOutbox);
    /** Remembers an open prompt; false when one is already open for this chat (so it is not sent twice). */
    public boolean open(Tx tx, long chatId, String title, List<String> projects);
    public Optional<Prompt> prompt(Tx tx, long chatId);      // record Prompt(String title, List<String> projects)
    public Result link(Tx tx, Requester presser, long chatId, int projectIndex);
    public Result decline(Tx tx, Requester presser, long chatId);
    public void migrated(Tx tx, long oldChatId, long newChatId);
}
```

`OutboxKind.GROUP_LINK` (the prompt, rendered but sent directly so a refusal can be seen) and `OutboxKind.GROUP_LINKED` (greeting to the group). Kv key: `"group.link." + chatId` → JSON `{"title": ..., "projects": [...]}`; removed by `link`/`decline`.

- [ ] **Step 1: Write the failing tests** in `UpdateHandlerTest`. Add a `personalHandler()` helper building `Groups` with one group `("bold", null, [Bold 100], ["life"])`, `new GroupLinks(groups, fakeWriter, clock, () -> {})` where `fakeWriter` is an in-memory `GroupWriter` that applies the same rules to a `Config.Telegram` field (for these tests: link in place — set the chat on the one group), and an `UpdateHandler` that takes it. Add a `myChatMember(updateId, fromId, chatId, title, status)` JSON helper next to `message(...)`.

```java
    @Test
    void theOwnerAddingTheBotIsAskedWhichProjectAndTheBotStays() throws Exception {
        UpdateHandler handler = personalHandler();

        handler.handle(myChatMember(700, 100, -4883391545L, "note", "member"));

        JsonNode prompt = telegram.awaitRequest("sendMessage", Duration.ofSeconds(2)).json();
        assertEquals(100, prompt.get("chat_id").asLong(), "privately, to whoever added it");
        assertTrue(prompt.get("text").asText().contains("note"));
        assertEquals("link:-4883391545:0", prompt.at("/reply_markup/inline_keyboard/0/0/callback_data").asText());
        Thread.sleep(100);
        assertTrue(telegram.drain("leaveChat").isEmpty());
    }

    @Test
    void someoneElseAddingTheBotMakesItLeave() throws Exception {
        personalHandler().handle(myChatMember(701, 999, -4883391545L, "note", "member"));
        assertEquals(-4883391545L, telegram.awaitRequest("leaveChat", Duration.ofSeconds(2)).json().get("chat_id").asLong());
    }

    @Test
    void theOwnersCommandInAGroupTheBotIsAlreadyInAlsoAsks() throws Exception {
        personalHandler().handle(message(702, 30, 100, "Bold", -4883391545L, "group", "/status@" + BOT, null));
        assertEquals(100, telegram.awaitRequest("sendMessage", Duration.ofSeconds(2)).json().get("chat_id").asLong());
    }

    @Test
    void tappingTheProjectLinksItAndTheNextTaskIsAnnouncedThereWithoutARestart() throws Exception {
        UpdateHandler handler = personalHandler();
        handler.handle(myChatMember(703, 100, -4883391545L, "note", "member"));
        long promptId = telegram.awaitRequest("sendMessage", Duration.ofSeconds(2)).json().path("message_id").asLong(1);

        handler.handle(callback(704, 100, "Bold", 100L, promptId, "link:-4883391545:0"));

        assertTrue(personalGroups.isGroupChat("telegram:-4883391545"), "the running groups, not only the file");
        assertEquals("GROUP_LINKED", row("SELECT kind FROM outbox WHERE chat_ref = 'telegram:-4883391545'").get("kind"));
        db.transaction(tx -> personalTasks.create(tx, BOLD, "life", "Fix it", Priority.NORMAL, "telegram:100/77"));
        assertEquals("1", row("SELECT count(*) AS n FROM outbox WHERE kind = 'TASK_QUEUED' AND chat_ref = 'telegram:-4883391545'").get("n"));
    }

    @Test
    void aStaleOrForeignButtonWritesNothing() {
        UpdateHandler handler = personalHandler();
        handler.handle(callback(705, 100, "Bold", 100L, 1, "link:-4883391545:0"));   // no prompt open
        handler.handle(myChatMember(706, 100, -4883391545L, "note", "member"));
        handler.handle(callback(707, 999, "Eve", 999L, 1, "link:-4883391545:0"));    // not the owner

        assertFalse(personalGroups.isGroupChat("telegram:-4883391545"));
    }

    @Test
    void aLongProjectNameStillFitsInTheButton() throws Exception {
        UpdateHandler handler = personalHandlerWithProject("x".repeat(60));
        handler.handle(myChatMember(708, 100, -4883391545L, "note", "member"));
        String data = telegram.awaitRequest("sendMessage", Duration.ofSeconds(2)).json()
                .at("/reply_markup/inline_keyboard/0/0/callback_data").asText();
        assertTrue(data.getBytes(StandardCharsets.UTF_8).length <= 64, data);
    }

    @Test
    void aRefusedPromptMakesTheBotLeave() throws Exception {
        telegram.failNext("sendMessage", 403, "Forbidden: bot can't initiate conversation with a user");
        personalHandler().handle(myChatMember(709, 100, -4883391545L, "note", "member"));
        assertEquals(-4883391545L, telegram.awaitRequest("leaveChat", Duration.ofSeconds(2)).json().get("chat_id").asLong());
    }

    @Test
    void aLinkedGroupThatBecomesASupergroupKeepsItsLink() {
        UpdateHandler handler = personalHandler();
        handler.handle(myChatMember(710, 100, -4883391545L, "note", "member"));
        handler.handle(callback(711, 100, "Bold", 100L, 1, "link:-4883391545:0"));

        handler.handle(migration(712, -4883391545L, -1004883391545L));

        assertTrue(personalGroups.isGroupChat("telegram:-1004883391545"));
    }
```

If `FakeTelegram` has no `failNext`, add it in `src/test/java/dispatch/testing/FakeTelegram.java`: the next call of that method answers `{"ok":false,"error_code":code,"description":...}` with that HTTP status. `migration(...)` is a `message` update in the old chat carrying `"migrate_to_chat_id"`.

`RendererTest.samplePayload` gains `case GROUP_LINK -> Json.object().put("chatId", -1L).put("title", "note").put("status", "OPEN").set("projects", Json.array("life"))` (use the file's existing array helper) and `case GROUP_LINKED -> Json.object().put("projects", "life")`.

- [ ] **Step 2: Run and watch them fail**

Run: `./mvnw -q test -Dtest='UpdateHandlerTest,RendererTest'`
Expected: FAIL (compile: `GroupLinks`, `GROUP_LINK`).

- [ ] **Step 3: Implement**

Messages (`messages_mn.properties`):

```properties
group.linkAsk=🔗 <b>{0}</b> группт нэмэгдлээ. Аль төсөлтэй холбох вэ?\nЭнэ группт тухайн төслийн даалгавар, үр дүн бүрийн нэг мөр мэдэгдэл гарна.
group.linkedTo=✅ <b>{0}</b> → {1}. Мэдэгдэл энэ группт гарна.
group.declined=<b>{0}</b> группт холбоогүй тул бот гарлаа.
group.greeting=✅ Энэ группт <b>{0}</b> төслийн даалгавар, үр дүнгийн мэдэгдэл гарна.
button.groupNoLink=Холбохгүй
callback.groupLinked=Холбогдлоо
callback.groupDeclined=Холбосонгүй
callback.groupStale=Энэ асуулт хүчингүй болсон байна
callback.groupLinkFailed=Тохиргоог бичиж чадсангүй
```

`Renderer`: `case GROUP_LINK -> groupLink(payload)`: status OPEN → text `group.linkAsk` with the escaped title and a keyboard of one button per project (`Button(project, "link:" + chatId + ":" + index)`, three per row, as `joinRequest` does) plus `Button(text("button.groupNoLink"), "link:" + chatId + ":-")`; status LINKED → `group.linkedTo(title, project)`, no keyboard; DECLINED → `group.declined(title)`. `case GROUP_LINKED -> plain(format("group.greeting", escape(payload.path("projects").asText())))`.

`GroupLinks`: `open` stores the Kv JSON unless one exists (return false then). `link`: `!groups.mayManage(presser.ref())` → `NOT_ALLOWED`; no Kv entry, index out of range, or the chat already a group chat → `STALE`; `writer.link(chatId, title, projects.get(index))` inside `try` (a `RuntimeException` → log `group.link_failed` with chat and project in `afterCommit`, return `CONFIG_FAILED`); then delete the Kv entry, `Outbox.enqueue(tx, null, OutboxKind.GROUP_LINKED, Refs.chat(chatId), null, Json.object().put("projects", project), now)`, `afterCommit(() -> groups.replace(updated))`, `afterCommit(wakeOutbox)`, log `group.linked`. `decline`: `NOT_ALLOWED` for a non-manager, `STALE` without an open prompt, otherwise delete the Kv entry and return `DECLINED`. `migrated`: `writer.migrate` then `groups.replace` in `afterCommit`; failure → log `group.migrate_failed` with both ids (the old `telegram.group_migrated` error line stays as the fallback message).

`UpdateHandler`:
- constructor: add `GroupLinks groupLinks` as the last parameter of the long constructor (null in the short one and in `handlerWithWorkers` → behave exactly as today: leave).
- `onMembershipChange` / `ignoreForeignChat` for a group: if `groupLinks != null && groups.mayManage(Refs.user(from.id))` (and, for a message, the message is a command addressed to this bot) → `askToLink(tx, chatId, title, from.id)`; else `leave` as now.
- `askToLink`: `if (!groupLinks.open(tx, chatId, title, projectNames())) return;` then render `GROUP_LINK` (status OPEN) and in `afterCommit` call `api.sendMessage(fromId, null, html, null, keyboard)` inside `try`; on any exception log `group.link_prompt_refused` and `api.leaveChat(chatId)` (best effort).
- `onCallback`: `data.startsWith("link:")` with 3 parts and the callback in the presser's private chat → `onLinkButton`: `-` → `decline` (then `leaveChat` after commit), a number → `link`; answer `callback.groupLinked/Declined/Stale/LinkFailed/notAdmin`; on LINKED or DECLINED redraw the prompt with `editMessageText` (status LINKED with the project, or DECLINED).
- `migrate_to_chat_id` branch: `if (groupLinks != null) groupLinks.migrated(tx, chatId, newChatId)` before the existing log line.

`App.java`: `GroupLinks groupLinks = new GroupLinks(groups, GroupWriter.file(configFile, environment), clock, outboxSignal::wake);` and pass it to `UpdateHandler`.

- [ ] **Step 4: Run and watch them pass**

Run: `./mvnw -q test -Dtest='UpdateHandlerTest,RendererTest,AppTest,TeamWorkersTest'`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add src/main src/test
git commit -m "Link a group to a project from Telegram, and follow it when it becomes a supergroup"
```

---

### Task 4: Groups in the Mini App — list and unlink

**Files:**
- Modify: `src/main/java/dispatch/ui/ManageApi.java` (route `/api/manage/groups/unlink`, optional `Function<String, BotApi> bots` constructor), `src/main/java/dispatch/ui/UiRoutes.java` (pass `bots`)
- Create: `ui/src/mini/GroupsPage.tsx`
- Modify: `ui/src/api.ts` (`unlinkGroup`), `ui/src/mini/paths.ts` (`/groups` page, admin-only), `ui/src/mini/HomePage.tsx` (row), `ui/src/App.tsx` (route)
- Test: `src/test/java/dispatch/ui/ManageApiTest.java`, `ui/src/mini/MiniApp.test.tsx`

**Interfaces:**
- Consumes: `ConfigView.groups[].chatId` (already served), `useMiniConfig`, `MiniManaged`, `Section`, `Row` (UI-3c).
- Produces: `POST /api/manage/groups/unlink {version, name}` → `Saved`; `unlinkGroup(version: string, name: string) => Promise<Saved>`.

- [ ] **Step 1: Write the failing tests**

`ManageApiTest`:

```java
    @Test
    void unlinkingAGroupRemovesItsChatAndKeepsItsProjects() throws Exception {
        // the fixture's team group has chatId -1001234567890 and project alm
        Saved saved = post("/api/manage/groups/unlink", Json.object().put("version", version()).put("name", "backend"));

        assertTrue(saved.restartNeeded());
        Config.Group group = ConfigLoader.load(config, ENV).telegram().groups().getFirst();
        assertNull(group.chatId());
        assertEquals(List.of("alm"), group.projects());
    }
```

(Use the test class's existing `post`/`version()` helpers and fixture names; if its team fixture needs `workers` only because of the chat, unlinking still loads because `workers` stays present.)

`MiniApp.test.tsx`:

```tsx
  it("lists linked groups and unlinks one after asking on the page", async () => {
    vi.mocked(api.getMe).mockResolvedValue(ADMIN);
    vi.mocked(api.unlinkGroup).mockResolvedValue(saved);
    window.history.pushState(null, "", "/groups");
    render(<App />);

    fireEvent.click(await row("acme"));
    fireEvent.click(await row("Тийм, салгах"));

    await waitFor(() => expect(api.unlinkGroup).toHaveBeenCalledWith("v1", "acme"));
  });

  it("says how to link a group, since only Telegram can add the bot", async () => {
    vi.mocked(api.getMe).mockResolvedValue(ADMIN);
    window.history.pushState(null, "", "/groups");
    render(<App />);
    expect(await screen.findByText(/ботыг группт нэмнэ/)).toBeInTheDocument();
  });
```

(Add `unlinkGroup: vi.fn()` to the file's `vi.mock("../api")` list.)

- [ ] **Step 2: Run and watch them fail**

Run: `./mvnw -q test -Dtest=ManageApiTest` and `cd ui && npx vitest run src/mini/MiniApp.test.tsx`
Expected: FAIL — no route; no `/groups` screen.

- [ ] **Step 3: Implement**

`ManageApi`: add the route; `unlinkGroup(body)` = `save(body, (text, config) -> { group(config, name) must have a chatId else CliException(name + " has no group chat to unlink"); return ConfigEdit.remove(text, At.of("telegram","groups").item("name", name).key("chatId")); })`; after a successful save, when `bots != null`, `bots.apply(token).leaveChat(chatId)` best effort (catch and log `group.leave_failed`). Keep the 3-argument constructor delegating with `bots = null`; `UiRoutes.management` passes its `bots`.

`GroupsPage.tsx` (admin): `useMiniConfig`, `MiniManaged`; a `Section title="Холбосон группүүд"` with a `Row` per group whose `chatId !== null` (title = group name, subtitle = its projects joined by ", "), tap → on-page confirm rows as `ProjectPage`'s `RemoveRows` (text "Энэ группт мэдэгдэл гарахаа болино, бот группээс гарна.", "Тийм, салгах", "Болих") → `save(v => unlinkGroup(v, name))`. Empty → `Row title="Холбосон групп алга"`. Below, a `Section` with one non-clickable `Row` explaining: "Групп холбохдоо ботыг группт нэмнэ (эсвэл тэнд /status@бот гэж бичнэ): бот танд хувийн чатаар аль төсөл болохыг асууна."

`paths.ts`: add `"/groups"` to `PagePath`, `PAGES` and `ADMIN_PAGES`. `HomePage`: in the Dispatch section, a `Row` "Группүүд" (icon `MessageOutlined`, subtitle "Төсөл бүрийн мэдэгдлийн групп") → `/groups`. `App.tsx` `MiniPage`: `/groups` renders `<GroupsPage />`.

- [ ] **Step 4: Run and watch them pass, then the UI suite and typecheck**

Run: `./mvnw -q test -Dtest='ManageApiTest,MiniAppServerTest'` and `cd ui && npx vitest run && npx tsc --noEmit`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add src/main/java/dispatch/ui src/test/java/dispatch/ui ui/src
git commit -m "List and unlink groups in the Mini App"
```

---

### Task 5: Docs, the guide, and the live check

**Files:**
- Create: `docs/adr/0023-a-personal-bot-may-link-a-group-per-project.md` (amends 0014; shape of 0021: decision, why, rejected alternatives, `## Consequences`)
- Modify: `docs/ARCHITECTURE.md` (decision row, G-1 milestone row), `README.md` ("A bot for your team" / personal: linking a group), `SECURITY.md` (who may link; the group sees headlines)
- Modify: the Mongolian guide artifact `https://claude.ai/artifact/EDeGqVh4EcYdHsPgYdAtSL` (read it first; add: setup, config file keys, adding a project from `dispatch project add` / the Mini App, linking a group, the Mini App's screens)

- [ ] **Step 1: Write ADR 0023 and the doc updates.** Rejected alternatives to record: requiring a worker for a personal bot with a group (ceremony for one person on one computer); linking by editing YAML by hand (what this replaces); linking from the Mini App (it cannot learn a chat id).

- [ ] **Step 2: Full suites.**

Run: `./mvnw -Pui verify` (after `cd ui && npm run build`)
Expected: BUILD SUCCESS, 0 failures.

- [ ] **Step 3: Live check on the personal Dispatch** (the done criterion). `systemctl --user stop dispatch`, back up and replace `~/.local/share/dispatch/dispatch.jar` with `target/dispatch-0.1.0.jar`, start it. Ask the user to send `/status@dispatch_task_bot` in "note" and tap **life**; confirm in the log `event=group.linked` and in `dispatch.yaml` the `chatId`; then a private task for life must post `TASK_QUEUED` to "note" (outbox row with `chat_ref = telegram:-4883391545`), with no restart in between.

- [ ] **Step 4: Update and republish the guide artifact**, then commit the docs.

```bash
git add docs README.md SECURITY.md
git commit -m "Record ADR 0023 and document linking a group to a project"
```
