# D-1 The Desktop UI in Mongolian, as a Dispatcher's Board — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Every page `dispatch ui` serves speaks Mongolian or English (the server's messages too), in the dark "board" look,
with projects edited in a side panel, people managed by group and logs laid out as filterable rows.

**Architecture:** The server gets one `Text` type (a key and its arguments) rendered at the HTTP edge in the language the
page asks for (`Accept-Language`) from `texts_en.properties` and `texts_mn.properties`; the terminal and the log render
English. The page gets a small `t()` over two typed dictionaries and a provider that also switches antd's locale. The
desktop shell becomes the board: a strip of lamps across the top and a rail on the left, themed from `board.ts` and
`board.css`; the pages it shows are restyled in it, and keep working at phone width inside the Mini App.

**Tech Stack:** Java 25 (`ResourceBundle`, `MessageFormat`, Jackson), React 19, Ant Design 6 (`mn_MN` locale), Vite,
vitest, Playwright, `@fontsource-variable/onest` and `@fontsource-variable/jetbrains-mono` (OFL-1.1).

**Spec:** `docs/superpowers/specs/2026-09-28-desktop-ui-design.md`

## Global Constraints

- Build after X-1 merges into local `main`, on a branch `d1-desktop` in its own worktree; merged locally with `--no-ff`.
- Languages: `mn` and `en`. The desktop's language is `localStorage["dispatch.language"]` when it holds `mn` or `en`,
  else `mn` when any of `navigator.languages` has the primary tag `mn`, else `en`. Storage is read and written inside
  try/catch. The Mini App is always `mn`.
- Server text: `texts_en.properties`, `texts_mn.properties` (UTF-8, `MessageFormat`: an apostrophe is written twice). A
  page's request sends `Accept-Language: mn` or `en`; `mn` when the header's first range has the primary tag `mn`, else
  `en`. The terminal and every log line render English. What another program wrote is an argument, never translated.
- No i18n library. `t(key, params?)`, `{name}` placeholders, English plurals as `….one`/`….other` keys.
- Colours, dark only: ground `#10191b`, panel `#172427`, strip `#0c1416`, ink `#e4ece8`, secondary `#9fb4ae`, hint
  `#7d948f`, rule `#24363a`, amber `#f0b44c` (needs you, main action; dark ink on it), green `#58c28d` (running, OK), red
  `#ef6a5a` (failed, danger). Every text colour 4.5:1 on its ground.
- Type: Onest for everything, JetBrains Mono only for code values (paths, branches, ids, log events).
- A lamp is never colour alone: its words say the same. Motion only answers the person, and none with reduced motion.
- The glossary in the spec is the vocabulary: Тойм, Даалгавар, Төслүүд/төсөл, Хүмүүс, Тохиргоо, Лог, Бүлэг, Гишүүн, Админ,
  Товч нэр, Эхлэх салбар, Хавтас, Агент, Төлөвлөх/хэрэгжүүлэх, Төлөвлөгөө, Сервис, Шалгалт, Дахин эхлүүлэх, Хадгалах,
  Болих, Засах, Хасах, Төсөл нэмэх, Явж байна, Таныг хүлээж, Дараалал, Зардал, Компьютер, Дагах, Түвшин, Үйл явдал, Хайх,
  Загвар (model), Сэтгэх түвшин (effort).
- Existing tests keep passing in English; the terminal's English is unchanged (tests that assert it stay as they are,
  except where a message's type changes from `String` to `Text` and they call `.english()`).
- Secret hygiene: scan the diff before every push (one `/usr/bin/grep -E` pattern per call, `"$HOME"` for the home path).
- Commit messages: a plain sentence subject, a body saying why, the session's two trailers.

## Review Focus

1. **Browser storage that throws** (a private window, blocked site data): the page still opens in the detected language
   and the switch still works for the session. Task 5 pins it.
2. **A log line that is not logfmt** (a stack trace line, a line cut at the tail's start): it shows as a plain row, never
   dropped and never thrown on. Task 11 pins it.
3. **A Mongolian bundle missing a key, or with other placeholders than English**: a page shows English, never a raw key,
   and the bundle test fails the build. Task 1 pins it.
4. **An apostrophe in a message** ("members' computers", "Claude Code's default"): it shows once. Task 1 pins it.
5. **Long Mongolian words at 390 pixels** ("Хэрэгжүүлэх үе шат", "Шалгалт: 2 анхааруулга"): no page scrolls sideways, in
   either shell. Task 14 pins it.

---

### Task 1: `Text` and `Language` on the server; refusals speak the page's language

**Files:**
- Create: `src/main/java/dispatch/Language.java`, `src/main/java/dispatch/Text.java`, `src/main/java/dispatch/Texts.java`
- Create: `src/main/resources/texts_en.properties`, `src/main/resources/texts_mn.properties`
- Modify: `src/main/java/dispatch/Json.java` (write in a language), `src/main/java/dispatch/ui/ApiException.java`,
  `src/main/java/dispatch/cli/CliException.java`, `src/main/java/dispatch/ui/UiServer.java`
- Modify, every `new ApiException(...)`: `ui/UiRoutes.java:77`, `ui/TasksApi.java` (21 sites, lines 71–285),
  `ui/TelegramAuth.java` (10 sites, 100–173), `ui/UiAuth.java:102,107`, `ui/MiniApp.java:118`, `ui/ManageApi.java:512`,
  `ui/SetupApi.java:434` (passes an exception's words on: `Text.raw`)
- Test: `src/test/java/dispatch/TextTest.java`, `src/test/java/dispatch/LanguageTest.java`, one case each in
  `src/test/java/dispatch/ui/UiServerTest.java` and `src/test/java/dispatch/ui/TasksApiTest.java`

**Interfaces:**
- Produces: `dispatch.Language` (`EN`, `MN`, `static Language fromAcceptLanguage(String header)`);
  `dispatch.Text` (`render(Language)`, `english()`, `static Text of(String key, Object... args)`,
  `static Text raw(String words)`, `static Text joined(String separator, List<Text> parts)`; serialized by Jackson as
  its words in the writer's `Language` attribute, English without one); `Json.write(Object value, Language language)`;
  `new ApiException(int status, String code, Text message)` with `text()`; `new CliException(Text message)` with
  `text()` (a `String` message becomes `Text.raw`). Keys are `area.what`, lower camel after the dot
  (`refusal.notAdmin`, `refusal.noTask`).

- [ ] **Step 1: Write the failing tests**

`LanguageTest`:

```java
package dispatch;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

class LanguageTest {

    @Test
    void theFirstRangeOfAcceptLanguageDecides() {
        assertEquals(Language.MN, Language.fromAcceptLanguage("mn"));
        assertEquals(Language.MN, Language.fromAcceptLanguage("mn-MN,mn;q=0.9,en;q=0.8"));
        assertEquals(Language.MN, Language.fromAcceptLanguage("MN-Cyrl-MN"));
        assertEquals(Language.EN, Language.fromAcceptLanguage("en-US,en;q=0.9,mn;q=0.8"));
        assertEquals(Language.EN, Language.fromAcceptLanguage("ru"));
        assertEquals(Language.EN, Language.fromAcceptLanguage(""));
        assertEquals(Language.EN, Language.fromAcceptLanguage(null));
    }
}
```

`TextTest` (the bundle checks read the two files as `Properties`, UTF-8):

```java
package dispatch;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.text.MessageFormat;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;

class TextTest {

    @Test
    void aKeyedTextRendersInEachLanguageWithItsArguments() {
        Text noTask = Text.of("refusal.noTask", 42);

        assertEquals("no task #42 here", noTask.render(Language.EN));
        assertEquals("#42 даалгавар энд алга", noTask.render(Language.MN));
        assertEquals("no task #42 here", noTask.toString(), "string concatenation and the log read English");
    }

    @Test
    void anArgumentThatIsItselfATextRendersInTheSameLanguage() {
        Text outer = Text.of("test.wrapped", Text.of("refusal.noTask", 7));

        assertEquals("wrapped: no task #7 here", outer.render(Language.EN));
        assertEquals("ороосон: #7 даалгавар энд алга", outer.render(Language.MN));
    }

    @Test
    void rawWordsAndJoinedPartsReadTheSameInEveryLanguageExceptTheirKeyedParts() {
        Text joined = Text.joined("; ", List.of(Text.raw("git: fatal"), Text.of("refusal.noTask", 3)));

        assertEquals("git: fatal; no task #3 here", joined.render(Language.EN));
        assertEquals("git: fatal; #3 даалгавар энд алга", joined.render(Language.MN));
    }

    @Test
    void anApostropheShowsOnce() {
        assertEquals("members' computers make the pull requests", Text.of("test.apostrophe").render(Language.EN));
    }

    @Test
    void aKeyMongolianLacksFallsBackToEnglishNeverToTheKey() {
        assertEquals("only in English", Text.of("test.englishOnly").render(Language.MN));
    }

    @Test
    void bothBundlesHaveTheSameKeysAndTheSamePlaceholders() throws IOException {
        Properties en = bundle("texts_en.properties");
        Properties mn = bundle("texts_mn.properties");
        Set<String> missing = new TreeSet<>(en.stringPropertyNames());
        missing.removeAll(mn.stringPropertyNames());
        missing.remove("test.englishOnly");
        assertEquals(Set.of(), missing, "keys Mongolian lacks");
        Set<String> extra = new TreeSet<>(mn.stringPropertyNames());
        extra.removeAll(en.stringPropertyNames());
        assertEquals(Set.of(), extra, "keys English lacks");
        for (String key : mn.stringPropertyNames()) {
            assertEquals(placeholders(en.getProperty(key)), placeholders(mn.getProperty(key)), key);
            new MessageFormat(mn.getProperty(key)); // a lone apostrophe or a broken {…} fails here
        }
    }

    @Test
    void everyKeyTheCodeNamesIsInTheBundles() throws IOException {
        Properties en = bundle("texts_en.properties");
        Pattern named = Pattern.compile("Text\\.of\\(\\s*\"([A-Za-z0-9_.]+)\"");
        Set<String> unknown = new TreeSet<>();
        try (Stream<Path> sources = Files.walk(Path.of("src/main/java"))) {
            for (Path source : sources.filter(file -> file.toString().endsWith(".java")).toList()) {
                Matcher matcher = named.matcher(Files.readString(source));
                while (matcher.find()) {
                    if (!en.containsKey(matcher.group(1))) {
                        unknown.add(matcher.group(1) + " (" + source.getFileName() + ")");
                    }
                }
            }
        }
        assertEquals(Set.of(), unknown);
    }

    private static Set<String> placeholders(String pattern) {
        Set<String> found = new TreeSet<>();
        Matcher matcher = Pattern.compile("\\{(\\d+)").matcher(pattern);
        while (matcher.find()) {
            found.add(matcher.group(1));
        }
        return found;
    }

    private static Properties bundle(String name) throws IOException {
        Properties properties = new Properties();
        try (InputStreamReader reader = new InputStreamReader(TextTest.class.getResourceAsStream("/" + name), StandardCharsets.UTF_8)) {
            properties.load(reader);
        }
        return properties;
    }
}
```

The `test.*` keys are the tests' own and live in the bundles with a comment saying so:

```properties
# texts_en.properties
# Used only by TextTest.
test.wrapped=wrapped: {0}
test.apostrophe=members'' computers make the pull requests
test.englishOnly=only in English
```

```properties
# texts_mn.properties
# Used only by TextTest (test.englishOnly is left out on purpose).
test.wrapped=ороосон: {0}
test.apostrophe=гишүүдийн компьютер pull request үүсгэнэ
```

In `UiServerTest` (it already starts a server with a login link; reuse its helpers), a request with
`Accept-Language: mn` to `/api/nope` answers 404 with `"message":"Ийм API алга: /api/nope"`. In `TasksApiTest`, the
missing-task case asked with `Accept-Language: mn` answers `#99 даалгавар энд алга`.

- [ ] **Step 2: Run them to see them fail**

Run: `./mvnw -q -o test -Dtest='TextTest,LanguageTest,UiServerTest,TasksApiTest' -Dsurefire.failIfNoSpecifiedTests=false`
Expected: compilation fails (`Text`, `Language` missing).

- [ ] **Step 3: Write `Language`, `Text`, `Texts`**

```java
package dispatch;

import java.util.Locale;

/** The two languages a page can ask for; the terminal and the log are English. */
public enum Language {
    EN, MN;

    /** Mongolian when the first range of an Accept-Language header names it (mn, mn-MN, mn-Cyrl-MN); English otherwise. */
    public static Language fromAcceptLanguage(String header) {
        if (header == null || header.isBlank()) {
            return EN;
        }
        String first = header.split(",", 2)[0];
        String primary = first.split("[-;]", 2)[0].strip().toLowerCase(Locale.ROOT);
        return primary.equals("mn") ? MN : EN;
    }
}
```

```java
package dispatch;

import com.fasterxml.jackson.core.JsonGenerator;
import com.fasterxml.jackson.databind.SerializerProvider;
import com.fasterxml.jackson.databind.ser.std.StdSerializer;
import java.io.IOException;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.stream.Collectors;

/**
 * Words for a person, in their language: a key into texts_en.properties and texts_mn.properties with its arguments,
 * words that read the same in every language (a name, or what another program wrote), or parts joined. A page reads the
 * language its request asked for; the terminal and the log read English, which is also what toString gives.
 */
public sealed interface Text {

    String render(Language language);

    default String english() {
        return render(Language.EN);
    }

    /** @param args strings, numbers or other texts; a text renders in the same language */
    static Text of(String key, Object... args) {
        return new Keyed(key, Collections.unmodifiableList(Arrays.asList(args.clone())));
    }

    static Text raw(String words) {
        return new Raw(words);
    }

    static Text joined(String separator, List<Text> parts) {
        return new Joined(separator, List.copyOf(parts));
    }

    record Keyed(String key, List<Object> args) implements Text {
        @Override
        public String render(Language language) {
            return Texts.format(language, key, args);
        }

        @Override
        public String toString() {
            return english();
        }
    }

    record Raw(String words) implements Text {
        @Override
        public String render(Language language) {
            return words;
        }

        @Override
        public String toString() {
            return words;
        }
    }

    record Joined(String separator, List<Text> parts) implements Text {
        @Override
        public String render(Language language) {
            return parts.stream().map(part -> part.render(language)).collect(Collectors.joining(separator));
        }

        @Override
        public String toString() {
            return english();
        }
    }

    /** Writes a text as its words, in the language the writer was given (Json.write), English without one; Json.MAPPER registers it. */
    final class Serializer extends StdSerializer<Text> {
        public Serializer() {
            super(Text.class);
        }

        @Override
        public void serialize(Text text, JsonGenerator out, SerializerProvider provider) throws IOException {
            Object language = provider.getAttribute(Language.class);
            out.writeString(text.render(language instanceof Language chosen ? chosen : Language.EN));
        }
    }
}
```

```java
package dispatch;

import java.text.MessageFormat;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.ResourceBundle;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/** The bundles behind {@link Text}: texts_en.properties and texts_mn.properties. */
final class Texts {

    private static final Map<Language, ResourceBundle> BUNDLES = Map.of(
            Language.EN, bundle(Locale.of("en")),
            Language.MN, bundle(Locale.of("mn")));
    private static final Set<String> MISSING = ConcurrentHashMap.newKeySet();

    private Texts() {
    }

    static String format(Language language, String key, List<Object> args) {
        Object[] words = new Object[args.size()];
        for (int i = 0; i < words.length; i++) {
            Object arg = args.get(i);
            // Strings, so MessageFormat never groups a number's digits; a text in the same language as the whole.
            words[i] = arg instanceof Text text ? text.render(language) : String.valueOf(arg);
        }
        return new MessageFormat(pattern(language, key), Locale.ROOT).format(words);
    }

    private static String pattern(Language language, String key) {
        ResourceBundle bundle = BUNDLES.get(language);
        if (bundle.containsKey(key)) {
            return bundle.getString(key);
        }
        if (language == Language.EN) {
            throw new IllegalStateException("no text " + key + " in texts_en.properties");
        }
        if (MISSING.add(language + "/" + key)) {
            Log.warn("text.missing", "key", key, "language", language);
        }
        return pattern(Language.EN, key);
    }

    private static ResourceBundle bundle(Locale locale) {
        return ResourceBundle.getBundle("texts", locale,
                ResourceBundle.Control.getNoFallbackControl(ResourceBundle.Control.FORMAT_PROPERTIES));
    }
}
```

- [ ] **Step 4: Render at the edge.** `Json.MAPPER` registers the serializer for every `Text`
  (`new SimpleModule().addSerializer(Text.class, new Text.Serializer())`: an annotation on the interface is not applied
  to its records reliably), and `Json` gains:

```java
    /** {@link #write(Object)} with every {@link Text} in {@code language}. */
    public static String write(Object value, Language language) {
        try {
            return MAPPER.writer().withAttribute(Language.class, language).writeValueAsString(value);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("cannot write JSON", e);
        }
    }
```

`ApiException(int status, String code, Text message)`: `super(message.english())`, keeps `message` as `text()`.
`CliException`: a `Text text` field; `CliException(String message)` sets `Text.raw(message)`; new `CliException(Text)`
sets `super(text.english())`; `text()` returns it. In `UiServer`, the language is read once in `respond`:

```java
        Language language = Language.fromAcceptLanguage(exchange.getRequestHeaders().getFirst("Accept-Language"));
```

and passed to `api(...)`; `Json.write(result, language)`; every `error(code, message)` becomes
`error(code, text.render(language))`, the server's own ones keyed: `refusal.noApi` ("no such API: {0}" / "Ийм API алга:
{0}"), `refusal.method` ("{0} only answers {1}" / "{0} зөвхөн {1} хүлээн авна"), `refusal.tooLarge` ("the request is larger
than {0} KiB" / "хүсэлт {0} KiB-аас их байна"), `refusal.notJson` ("the request is not JSON" / "хүсэлт JSON биш байна"),
`refusal.internal` ("something went wrong; the terminal running dispatch ui shows what" / "алдаа гарлаа; юу болсныг dispatch
ui ажиллаж буй терминал харуулна"). `CliException` answers `e.text().render(language)`.

- [ ] **Step 5: Key every refusal.** Each `new ApiException(status, code, "…")` becomes
`new ApiException(status, code, Text.of("refusal.<name>", args…))`, with its English exactly as it was (tests keep
asserting it) and its Mongolian from the glossary. A constant (`NOT_ADMIN`, `NOT_YOURS`, `UNAUTHORIZED`, `EXPIRED`,
`NOT_A_MEMBER`, `CHANGED`) becomes a `Text` constant. The keys, one per distinct message: `refusal.notAdmin`,
`refusal.noTask`, `refusal.cancelNotYours`, `refusal.ended`, `refusal.retryNotYours`, `refusal.retryNotFailed`,
`refusal.answerEmpty`, `refusal.answered`, `refusal.outOfOrder`, `refusal.notYours`, `refusal.openQuestions`,
`refusal.notWaiting`, `refusal.stalePlan`, `refusal.notMember`, `refusal.missingField`, `refusal.missingTaskId`,
`refusal.wrongHost`, `refusal.unauthorized`, `refusal.expired`, `refusal.sessionEnded`, `refusal.otherOrigin`,
`refusal.groupAck`, `refusal.changed`. Examples:

```properties
# texts_en.properties
refusal.noTask=no task #{0} here
refusal.notAdmin=only an admin may manage Dispatch; ask one of them, or use dispatch ui on the machine
refusal.changed=the config changed on disk since this page loaded it; reload to see the change
```

```properties
# texts_mn.properties
refusal.noTask=#{0} даалгавар энд алга
refusal.notAdmin=Dispatch-ийг зөвхөн админ удирдана; админаас хүсэх эсвэл машин дээр dispatch ui ажиллуулна уу
refusal.changed=энэ хуудас уншсанаас хойш тохиргооны файл өөрчлөгдсөн; өөрчлөлтийг харахын тулд дахин уншина уу
```

- [ ] **Step 6: Run the tests, then the whole suite**

Run: `./mvnw -q -o test -Dtest='TextTest,LanguageTest,UiServerTest,TasksApiTest' -Dsurefire.failIfNoSpecifiedTests=false`
then `./mvnw -q -o test`. Expected: exit 0.

- [ ] **Step 7: Commit** — "Answer a page's refusals in the language it asks for".

### Task 2: Check findings and service notes in the page's language

**Files:**
- Modify: `src/main/java/dispatch/cli/Checks.java` (`Finding(Level level, String area, Text message)`; the 33 findings,
  lines 69–255), `src/main/java/dispatch/cli/CheckCommand.java:45-47` (prints `finding.message().english()`),
  `src/main/java/dispatch/cli/Service.java` (`Status.notes` becomes `List<Text>`), `SystemdService.java`,
  `LaunchdService.java`, `WindowsTaskService.java` (their notes), `src/main/java/dispatch/ui/OverviewApi.java`
  (`ServiceView.notes` is `List<Text>`)
- Test: `src/test/java/dispatch/cli/ChecksTest.java` and the other tests that read `message()` or `notes()` call
  `.english()`; one new case in `src/test/java/dispatch/ui/OverviewApiTest.java`

**Interfaces:**
- Consumes: `Text`, `Language`, `Json.write(Object, Language)` (Task 1).
- Produces: `Checks.Finding(Level, String area, Text message)`; `Service.Status(boolean, boolean, String detail,
  List<Text> notes)` (`detail` is what the OS said, passed on as it came).

- [ ] **Step 1: Write the failing test.** In `OverviewApiTest`, the overview written with `Json.write(overview,
  Language.MN)` for a config with no Mini App has the finding `"message":"miniApp: унтраалттай; Telegram-д юу ч үйлчлэхгүй,
  бот Удирдах товч харуулахгүй"`.
- [ ] **Step 2: Run it:** `./mvnw -q -o test -Dtest=OverviewApiTest -Dsurefire.failIfNoSpecifiedTests=false`; expected FAIL
  (the finding is English).
- [ ] **Step 3: Key the findings.** Each `run.add(level, area, "…")` becomes `run.add(level, area, Text.of("check.<name>",
  args…))`; the English keeps its `area: ` start and every word, so `dispatch check` reads as before. What a program or
  exception said (`e.getMessage()`, a version string) is an argument. Keys: `check.configInvalid`, `check.config`,
  `check.ghNotNeeded`, `check.botTokenShape`, `check.bot`, `check.botRefused`, `check.agentNotLoggedIn`, `check.agent`,
  `check.notCloned`, `check.projectUnavailable`, `check.noBaseBranch`, `check.project`, `check.noClaudeMd`,
  `check.stateNew`, `check.stateOpen`, `check.state`, `check.stateUnreadable`, `check.ghToken`, `check.ghLoggedIn`,
  `check.ghNotLoggedIn`, `check.workersLocal`, `check.workersPublic`, `check.workersUnreachable`, `check.workersOther`,
  `check.workersNone`, `check.miniAppOff`, `check.miniAppLocal`, `check.miniAppPublic`, `check.miniAppUnreachable`,
  `check.miniAppOther`, `check.miniAppNone`. The services' notes likewise, `service.<name>`.
- [ ] **Step 4: Run** `./mvnw -q -o test`; expected exit 0 (tests that compared `message()` with a `String` now call
  `.english()`, with the same expected words).
- [ ] **Step 5: Commit** — "Write check findings and service notes in the page's language".

### Task 3: Validation, management and setup messages in the page's language

**Files:**
- Modify: `src/main/java/dispatch/config/ConfigException.java` (carries a `Text`), `ConfigLoader.java` (51 messages,
  lines 53–415, collected as `List<Text>` and joined), `ConfigEdit.java` (10, lines 121–199),
  `src/main/java/dispatch/ui/ManageApi.java` (30 `CliException`s; `ConfigException` passed on as
  `new CliException(e.text())`), `src/main/java/dispatch/ui/SetupApi.java` (24), `src/main/java/dispatch/cli/ProjectProbe.java`
  (2)
- Test: one new case in `src/test/java/dispatch/ui/ManageApiTest.java`

**Interfaces:**
- Consumes: Task 1's `Text`, `CliException(Text)`.
- Produces: `new ConfigException(Text message)` with `text()`; `new ConfigException(String)` stays for the parser's own
  messages (`Text.raw`).

- [ ] **Step 1: Write the failing test.** `ManageApiTest`: editing a project with the alias `a b`, answered through
  `Json.write` in Mongolian, fails with a message containing `үсэг, цифр, '.', '_', '-' л болно` (the alias rule), and the
  same save in English contains `letters, digits, '.', '_' and '-' only` as today.
- [ ] **Step 2: Run it** — FAIL (English in both).
- [ ] **Step 3: Key them.** `ConfigLoader`'s `errors` becomes `List<Text>` (`config.<field><Rule>` keys, e.g.
  `config.teamRequired`, `config.statePathAbsolute`, `config.groupNameShape`, `config.aliasShape`); the thrown message is
  `Text.of("config.invalid", file, Text.joined("\n  - ", errors))` with `config.invalid={0} is invalid:\n  - {1}`, so the
  terminal prints exactly what it did. `ConfigEdit`'s ten become `edit.*`, `ManageApi`'s `manage.*`, `SetupApi`'s
  `setup.*`, `ProjectProbe`'s `probe.*`.
- [ ] **Step 4: Run** `./mvnw -q -o test`; expected exit 0.
- [ ] **Step 5: Commit** — "Write validation, management and setup messages in the page's language".

### Task 4: The logs endpoint filters by task and by text

**Files:**
- Modify: `src/main/java/dispatch/ui/ManageApi.java:408-444` (`logs`, `tail`)
- Test: `src/test/java/dispatch/ui/ManageApiTest.java`

**Interfaces:**
- Produces: `POST /api/manage/logs` takes `task` (a whole number from 1) and `text` (any words, matched ignoring case)
  beside `lines`, `level`, `event`; a bad `task` is refused with `manage.logsTask`.

- [ ] **Step 1: Write the failing test.** Three lines in `dispatch.log`: `… event=run.finished task=12 …`,
  `… event=run.finished task=120 …`, `… event=outbox.failed error="Bad Request" …`. `{"task":12}` answers only the first
  (not `task=120`); `{"text":"bad request"}` only the third; `{"task":0}` is refused.
- [ ] **Step 2: Run it** — FAIL (unknown fields are ignored today, so all three come back).
- [ ] **Step 3: Implement.** A line matches `task` when it has the field ` task=<n>` followed by a space or the line's end;
  `text` with `line.toLowerCase(Locale.ROOT).contains(text.toLowerCase(Locale.ROOT))`. Both apply after redaction, as the
  level and event filters do.
- [ ] **Step 4: Run** `./mvnw -q -o test -Dtest=ManageApiTest -Dsurefire.failIfNoSpecifiedTests=false`; PASS.
- [ ] **Step 5: Commit** — "Filter the logs by task and by any text".

### Task 5: The page's language: `t()`, the dictionaries, the provider

**Files:**
- Create: `ui/src/i18n/en.ts`, `ui/src/i18n/mn.ts`, `ui/src/i18n/i18n.tsx`, `ui/src/i18n/i18n.test.tsx`
- Modify: `ui/src/api.ts` (`send` adds `Accept-Language`), `ui/src/main.tsx` (the provider wraps the app)

**Interfaces:**
- Produces: `type Language = "mn" | "en"`; `type Key = keyof typeof en`; `detectLanguage(storage?, languages?)`;
  `<LanguageProvider fixed?: Language>` (sets antd's `ConfigProvider locale`, `<html lang>`, and the module's current
  language for `api.ts`); `useT(): (key: Key, params?: Record<string, string | number>) => string`;
  `useLanguage(): { language, choose(language) }`; `currentLanguage(): Language`.

- [ ] **Step 1: Write the failing tests**

```tsx
import { fireEvent, render, screen } from "@testing-library/react";
import { expect, test } from "vitest";
import { detectLanguage, LanguageProvider, useLanguage, useT } from "./i18n";

const throwing = { getItem: () => { throw new Error("blocked"); }, setItem: () => { throw new Error("blocked"); } };

test("a remembered choice wins, then the browser's languages", () => {
  expect(detectLanguage({ getItem: () => "en", setItem: () => {} }, ["mn-MN"])).toBe("en");
  expect(detectLanguage({ getItem: () => null, setItem: () => {} }, ["en-US", "mn"])).toBe("mn");
  expect(detectLanguage({ getItem: () => null, setItem: () => {} }, ["en-US", "ru"])).toBe("en");
  expect(detectLanguage({ getItem: () => "xx", setItem: () => {} }, ["mn"])).toBe("mn");
});

test("storage that throws still gives a language", () => {
  expect(detectLanguage(throwing, ["mn"])).toBe("mn");
});

function Probe() {
  const t = useT();
  const { choose } = useLanguage();
  return <><p>{t("nav.projects")}</p><p>{t("strip.checksWarn.other", { count: 2 })}</p>
    <button onClick={() => choose("mn")}>mn</button></>;
}

test("choosing Mongolian renders Mongolian at once and remembers it", () => {
  const saved: Record<string, string> = {};
  render(<LanguageProvider storage={{ getItem: (k) => saved[k] ?? null, setItem: (k, v) => { saved[k] = v; } }}
                           languages={["en-US"]}><Probe /></LanguageProvider>);
  expect(screen.getByText("Projects")).toBeInTheDocument();
  expect(screen.getByText("2 warnings")).toBeInTheDocument();
  fireEvent.click(screen.getByRole("button", { name: "mn" }));
  expect(screen.getByText("Төслүүд")).toBeInTheDocument();
  expect(screen.getByText("2 анхааруулга")).toBeInTheDocument();
  expect(saved["dispatch.language"]).toBe("mn");
  expect(document.documentElement.lang).toBe("mn");
});

test("the Mini App's fixed language ignores the browser and the switch", () => {
  render(<LanguageProvider fixed="mn" storage={throwing} languages={["en-US"]}><Probe /></LanguageProvider>);
  expect(screen.getByText("Төслүүд")).toBeInTheDocument();
});

test("a page outside any provider reads English, as the tests do", () => {
  render(<Probe />);
  expect(screen.getByText("Projects")).toBeInTheDocument();
});
```

- [ ] **Step 2: Run** `cd ui && npx vitest run src/i18n` — FAIL (module missing).

- [ ] **Step 3: Write the dictionaries' start and `i18n.tsx`**

```ts
// ui/src/i18n/en.ts — every phrase a desktop page writes; mn.ts must have each key (TypeScript checks it).
export const en = {
  "nav.overview": "Overview",
  "nav.projects": "Projects",
  "nav.people": "People",
  "nav.settings": "Settings",
  "nav.logs": "Logs",
  "nav.setup": "Setup",
  "strip.service.running": "Service running",
  "strip.service.stopped": "Service stopped",
  "strip.service.none": "No background service",
  "strip.service.notSetUp": "Not set up yet",
  "strip.checksOk": "Checks OK",
  "strip.checksWarn.one": "{count} warning",
  "strip.checksWarn.other": "{count} warnings",
  "strip.checksFail.one": "{count} problem",
  "strip.checksFail.other": "{count} problems",
  "strip.restart": "Restart to apply",
  "strip.restartNow": "Restart now",
  "strip.language": "Language",
} as const;
```

```ts
// ui/src/i18n/mn.ts
import type { Key } from "./i18n";

export const mn: Record<Key, string> = {
  "nav.overview": "Тойм",
  "nav.projects": "Төслүүд",
  "nav.people": "Хүмүүс",
  "nav.settings": "Тохиргоо",
  "nav.logs": "Лог",
  "nav.setup": "Тохируулах",
  "strip.service.running": "Сервис ажиллаж байна",
  "strip.service.stopped": "Сервис зогссон",
  "strip.service.none": "Арын сервис алга",
  "strip.service.notSetUp": "Тохируулаагүй",
  "strip.checksOk": "Шалгалт хэвийн",
  "strip.checksWarn.one": "{count} анхааруулга",
  "strip.checksWarn.other": "{count} анхааруулга",
  "strip.checksFail.one": "{count} асуудал",
  "strip.checksFail.other": "{count} асуудал",
  "strip.restart": "Дахин эхлүүлбэл хэрэгжинэ",
  "strip.restartNow": "Одоо дахин эхлүүлэх",
  "strip.language": "Хэл",
};
```

```tsx
// ui/src/i18n/i18n.tsx
import { ConfigProvider } from "antd";
import enUS from "antd/locale/en_US";
import mnMN from "antd/locale/mn_MN";
import { createContext, useContext, useEffect, useMemo, useState, type ReactNode } from "react";
import { en } from "./en";
import { mn } from "./mn";

export type Language = "mn" | "en";
export type Key = keyof typeof en;
type Storage = Pick<globalThis.Storage, "getItem" | "setItem">;

const STORED = "dispatch.language";
const DICTIONARIES: Record<Language, Record<Key, string>> = { en, mn };
let current: Language = "en";

/** The language requests are sent in (api.ts); the provider keeps it. */
export const currentLanguage = () => current;

/** A remembered mn or en, else mn when the browser lists Mongolian, else English. Storage may throw. */
export function detectLanguage(storage: Storage | undefined, languages: readonly string[]): Language {
  try {
    const stored = storage?.getItem(STORED);
    if (stored === "mn" || stored === "en") return stored;
  } catch {
    // Blocked storage: the browser's languages still decide.
  }
  return languages.some((tag) => tag.toLowerCase().split("-")[0] === "mn") ? "mn" : "en";
}

function format(language: Language, key: Key, params?: Record<string, string | number>) {
  const text = DICTIONARIES[language][key] ?? DICTIONARIES.en[key] ?? key;
  return params ? text.replace(/\{(\w+)\}/g, (whole, name: string) => (name in params ? String(params[name]) : whole)) : text;
}

interface Chosen {
  language: Language;
  choose: (language: Language) => void;
}

const LanguageContext = createContext<Chosen>({ language: "en", choose: () => {} });

export function LanguageProvider({ fixed, storage = safeStorage(), languages = navigator.languages ?? [], children }: {
  fixed?: Language;
  storage?: Storage;
  languages?: readonly string[];
  children: ReactNode;
}) {
  const [chosen, setChosen] = useState<Language>(() => fixed ?? detectLanguage(storage, languages));
  const language = fixed ?? chosen;
  current = language;
  useEffect(() => {
    document.documentElement.lang = language;
  }, [language]);
  const value = useMemo<Chosen>(() => ({
    language,
    choose: (next) => {
      if (fixed) return;
      try {
        storage?.setItem(STORED, next);
      } catch {
        // Remembered for this visit only.
      }
      setChosen(next);
    },
  }), [language, fixed, storage]);
  return (
    <LanguageContext.Provider value={value}>
      <ConfigProvider locale={language === "mn" ? mnMN : enUS}>{children}</ConfigProvider>
    </LanguageContext.Provider>
  );
}

function safeStorage(): Storage | undefined {
  try {
    return window.localStorage;
  } catch {
    return undefined;
  }
}

export const useLanguage = () => useContext(LanguageContext);

/** t(key, params): the phrase in the page's language. An English plural is two keys (`.one`, `.other`); the caller picks by the count. */
export function useT() {
  const { language } = useLanguage();
  return (key: Key, params?: Record<string, string | number>) => format(language, key, params);
}
```

`ConfigProvider` nested inside `main.tsx`'s themed `ConfigProvider` keeps the theme and adds the locale. In `api.ts`'s
`send`, the headers gain `"Accept-Language": currentLanguage()`.

- [ ] **Step 4: Run** `cd ui && npx vitest run && npm run typecheck`; PASS.
- [ ] **Step 5: Commit** — "Give the page a language: t(), two dictionaries and a switch that remembers".

### Task 6: The board: theme, fonts, the strip and the rail

**Files:**
- Create: `ui/src/board.ts`, `ui/src/board.css`, `ui/src/desktop/Shell.tsx`, `ui/src/desktop/status.tsx`,
  `ui/src/desktop/Shell.test.tsx`, `ui/src/restart.ts` (the restart context, moved from `mini/data.tsx`)
- Modify: `ui/src/App.tsx` (`WebUi` renders `desktop/Shell`), `ui/src/theme.ts` (the desktop's theme is the board's),
  `ui/src/mini/data.tsx` (imports `restart.ts`), `ui/src/manage/useManagedConfig.ts` (a save that needs a restart marks
  the context), `ui/src/manage/ManagedPage.tsx` (no inline notice; the shell shows it), `ui/package.json` (the two font
  packages)

**Interfaces:**
- Consumes: `useT`, `useLanguage` (Task 5).
- Produces: `BOARD` (the palette), `boardTheme(): ThemeConfig`; `<Shell pages selected onSelect>`;
  `DesktopStatus { overview, reload, loading }` from `useDesktopStatus()`; `RestartContext`/`useRestartNeeded()` in
  `restart.ts` (`{ installed: boolean | null, mark(installed: boolean) }`).

- [ ] **Step 1: Write the failing test** (`Shell.test.tsx`): with a mocked `getOverview` answering a running service and
  one WARN finding, the strip shows "Service running" and "1 warning"; after `mark(true)` it shows "Restart to apply" with
  a "Restart now" button; clicking the rail's "People" calls `onSelect("/people")`; choosing "Монгол" renders "Хүмүүс".
- [ ] **Step 2: Run** `cd ui && npx vitest run src/desktop` — FAIL.
- [ ] **Step 3: Write `board.ts`**

```ts
import { theme as antdTheme, type ThemeConfig } from "antd";

/** The desktop's board: dark slate, amber for what needs the owner, green for running, red for failed. Dark only. */
export const BOARD = {
  ground: "#10191b",
  panel: "#172427",
  strip: "#0c1416",
  ink: "#e4ece8",
  secondary: "#9fb4ae",
  hint: "#7d948f",
  rule: "#24363a",
  amber: "#f0b44c",
  amberInk: "#10191b",
  green: "#58c28d",
  red: "#ef6a5a",
} as const;

export const BOARD_FONT = `"Onest Variable", system-ui, sans-serif`;
export const BOARD_MONO = `"JetBrains Mono Variable", ui-monospace, monospace`;

export function boardTheme(): ThemeConfig {
  return {
    algorithm: antdTheme.darkAlgorithm,
    token: {
      colorBgBase: BOARD.ground,
      colorBgLayout: BOARD.ground,
      colorBgContainer: BOARD.panel,
      colorBgElevated: BOARD.panel,
      colorTextBase: BOARD.ink,
      colorTextSecondary: BOARD.secondary,
      colorTextTertiary: BOARD.hint,
      colorBorder: BOARD.rule,
      colorSplit: BOARD.rule,
      colorPrimary: BOARD.amber,
      colorTextLightSolid: BOARD.amberInk,
      colorLink: BOARD.amber,
      colorSuccess: BOARD.green,
      colorError: BOARD.red,
      colorWarning: BOARD.amber,
      fontFamily: BOARD_FONT,
      fontFamilyCode: BOARD_MONO,
      borderRadius: 5,
    },
  };
}
```

`board.css` holds `.board-strip` (strip ground, rule below, 10 px 18 px), `.lamp` (a 9 px dot and its words),
`.board-rail` (142 px, rule on the right; the current item amber with dark ink), `:focus-visible` (2 px amber outline),
`code, .mono` (JetBrains Mono), and `@media (prefers-reduced-motion: reduce) { * { transition: none !important;
animation: none !important; } }`. `main.tsx` imports `@fontsource-variable/onest`,
`@fontsource-variable/jetbrains-mono` and `board.css`; `theme.ts` answers `boardTheme()` outside Telegram.

- [ ] **Step 4: Write `desktop/status.tsx` and `desktop/Shell.tsx`.** `DesktopStatusProvider` loads the overview once
  (`useOverview`) and gives it to the strip and the Overview page; `Shell` renders the strip (instance name, service lamp,
  checks lamp from the findings' levels, the restart lamp when `RestartContext.installed !== null` with a Restart button
  when installed, and the `Монгол / English` switch as two buttons with `aria-pressed`), the rail (antd `Menu`, items from
  `t("nav.*")`), and the page. Below 640 px the rail collapses to a menu button in the strip.
- [ ] **Step 5: Move the restart context** from `mini/data.tsx` to `restart.ts`; `useManagedConfig`'s `save` calls
  `mark(config.service.installed)` when the save answers `restartNeeded`; `ManagedPage` stops rendering `RestartNotice`.
  The Mini App's `MiniShell` keeps showing its notice from the same context; the desktop shows the lamp.
- [ ] **Step 6: Run** `cd ui && npx vitest run && npm run typecheck`; PASS. Existing tests that looked for "Saved.
  Restart to apply" under a page now render the page inside the shell's provider and find the strip's "Restart to
  apply".
- [ ] **Step 7: Commit** — "Put the desktop on the board: a strip of lamps, a rail, dark slate and amber".

### Task 7: Тойм (Overview) on the board

**Files:** Modify `ui/src/OverviewPage.tsx`, `ui/src/OverviewPage.test.tsx`, `ui/src/RestartNotice.tsx`, the dictionaries.

- [ ] **Step 1: Test first:** the page reads the shared `useDesktopStatus()` (no second fetch: `getOverview` called
  once for strip and page together); in Mongolian it shows "Сервис", "Шалгалт", "Дахин шалгах"; a finding's area shows
  its label (`area.bot` → "Бот") or its own name for a project.
- [ ] **Step 2: Run** — FAIL.
- [ ] **Step 3: Implement:** the page's words through `t()` (`overview.*` keys: title, version, config, state, service,
  install, restart, stop, notService, checks, checkAgain, noChecks, notSetUp, notSetUpHint, cannotShow, tryAgain; and
  `area.*`: config, gh, bot, claude, codex, gemini, state, workers, miniApp); the service and checks as panels; lamps with
  words beside every icon.
- [ ] **Step 4: Run** `cd ui && npx vitest run src/OverviewPage.test.tsx`; PASS.
- [ ] **Step 5: Commit** — "Show the overview on the board, in the page's language".

### Task 8: Төслүүд (Projects) with the side panel

**Files:** Modify `ui/src/manage/ProjectsPage.tsx`, `ui/src/manage/ProjectForm.tsx`, `ui/src/manage/AddProject.tsx`,
`ui/src/options.ts` (labels become keys), `ui/src/manage/ProjectsPage.test.tsx`, the dictionaries.

**Interfaces:**
- Produces: `ProjectForm` groups its fields under `projects.where` ("Where it starts"), `projects.who` ("Who does it"),
  `projects.phases` ("Planning and execution"), with a help line under each; `options.ts` exports functions of `t`:
  `models(t)`, `efforts(t)`, `phaseModels(t)`, `phaseEfforts(t)`, `agentDefault(t, agent)`.

- [ ] **Step 1: Tests first:** clicking a row opens a `Drawer` titled with the project's name holding the form; saving
  closes it; "Add a project" opens the same drawer at the folder browser; below 640 px the drawer is full width (`width`
  is `"100%"` when `matchMedia("(max-width: 640px)")` matches); Remove asks "Remove {name}?" and says the clone stays; in
  Mongolian the columns read "Төсөл", "Бүлэг", "Хавтас", "Салбар", "Агент" and the help under Alias reads "Даалгавар
  өгөхдөө төслийг ингэж дуудна; хоосон бол байхгүй".
- [ ] **Step 2: Run** — FAIL.
- [ ] **Step 3: Implement:** the table (project + alias chip, group, folder in mono, branch in mono, agent); `Drawer`
  (placement right, 420 px, full width at ≤640 px) with `ProjectForm` or `AddProject`; the words through `t()`
  (`projects.*`, `options.*`).
- [ ] **Step 4: Run** `cd ui && npx vitest run src/manage`; PASS.
- [ ] **Step 5: Commit** — "Edit and add projects in a side panel".

### Task 9: Хүмүүс (People) by group

**Files:** Modify `ui/src/manage/PeoplePage.tsx`, `ui/src/manage/PeoplePage.test.tsx`, the dictionaries.

- [ ] **Step 1: Tests first:** one section per group with its linked chat id and "Unlink the chat" (Popconfirm, then
  `unlinkGroup`), its projects as chips, and its members with an admin `Switch` (`aria-label` "Admin: {name}", calls
  `setAdmin`), Rename (inline input, Save/Cancel) and Remove (asks "Remove {name} from {group}?"); a group without a chat
  says "No chat: only you" and has no Unlink; a personal bot shows no admin switch; in Mongolian the admin switch reads
  "Админ: {name}".
- [ ] **Step 2: Run** — FAIL.
- [ ] **Step 3: Implement** with `ConfigView.groups` (chatId, members, projects) and `admins`; words `people.*`.
- [ ] **Step 4: Run** `cd ui && npx vitest run src/manage/PeoplePage.test.tsx`; PASS.
- [ ] **Step 5: Commit** — "Manage people by group: chat, projects, members and admins together".

### Task 10: Тохиргоо (Settings), grouped

**Files:** Modify `ui/src/manage/SettingsPage.tsx`, `ui/src/manage/SettingsPage.test.tsx`, the dictionaries.

- [ ] **Step 1: Tests first:** three groups ("Limits per run", "Commits", "Commands") with a help line under each field;
  the fields and saving as today; in Mongolian "Нэг удаагийн хязгаар", "Коммит", "Командууд".
- [ ] **Step 2: Run** — FAIL.
- [ ] **Step 3: Implement**; words `settings.*` (every label and help line of today's page, plus the group titles).
- [ ] **Step 4: Run** `cd ui && npx vitest run src/manage/SettingsPage.test.tsx`; PASS.
- [ ] **Step 5: Commit** — "Group the settings, with a line of help under each".

### Task 11: Лог (Logs) as rows

**Files:** Create `ui/src/manage/logfmt.ts`, `ui/src/manage/logfmt.test.ts`; modify `ui/src/manage/LogsPage.tsx`,
`ui/src/manage/LogsPage.test.tsx`, `ui/src/api.ts` (`getLogs` takes `task` and `text`), the dictionaries.

**Interfaces:**
- Produces: `interface LogRow { line: string; ts: string | null; level: string | null; event: string | null;
  task: string | null; fields: [string, string][] }`; `parseLogLine(line: string): LogRow` (never throws: a line that is
  not logfmt comes back with only `line` set and `fields` empty).

- [ ] **Step 1: Write the failing parser tests**

```ts
import { expect, test } from "vitest";
import { parseLogLine } from "./logfmt";

test("a line becomes its fields, in order, with time, level, event and task picked out", () => {
  const row = parseLogLine('ts=2026-09-28T06:25:24.949Z level=INFO event=run.finished task=12 run=1 status=FAILED cost_usd=null');
  expect(row.ts).toBe("2026-09-28T06:25:24.949Z");
  expect(row.level).toBe("INFO");
  expect(row.event).toBe("run.finished");
  expect(row.task).toBe("12");
  expect(row.fields).toEqual([["run", "1"], ["status", "FAILED"], ["cost_usd", "null"]]);
});

test("a quoted value keeps its spaces, quotes, backslashes and line breaks", () => {
  const row = parseLogLine('ts=t level=ERROR event=outbox.failed error="Bad \\"Request\\": x\\\\y\\nnext" kind=TASK_MERGED');
  expect(row.fields).toEqual([["error", 'Bad "Request": x\\y\nnext'], ["kind", "TASK_MERGED"]]);
});

test("an empty value is kept as empty", () => {
  expect(parseLogLine('ts=t level=INFO event=e detail=""').fields).toEqual([["detail", ""]]);
});

test("a line that is not logfmt comes back as it is", () => {
  const row = parseLogLine("\tat dispatch.core.Coordinator.execute(Coordinator.java:60)");
  expect(row).toEqual({ line: "\tat dispatch.core.Coordinator.execute(Coordinator.java:60)", ts: null, level: null,
    event: null, task: null, fields: [] });
});

test("an unterminated quote ends the line instead of throwing", () => {
  expect(parseLogLine('ts=t level=WARN event=e error="cut off').fields).toEqual([["error", "cut off"]]);
});
```

- [ ] **Step 2: Run** `cd ui && npx vitest run src/manage/logfmt.test.ts` — FAIL.
- [ ] **Step 3: Write the parser**

```ts
/** One line of Dispatch's log (logfmt, as Log.java writes it), for the Logs page. */
export interface LogRow {
  line: string;
  ts: string | null;
  level: string | null;
  event: string | null;
  task: string | null;
  fields: [string, string][];
}

const PICKED = new Set(["ts", "level", "event", "task"]);

/** Never throws: a line that is not logfmt (a stack trace, a line the tail cut) keeps only its text. */
export function parseLogLine(line: string): LogRow {
  const pairs: [string, string][] = [];
  let i = 0;
  while (i < line.length) {
    while (line[i] === " ") i++;
    const eq = line.indexOf("=", i);
    const key = eq < 0 ? "" : line.slice(i, eq);
    if (eq < 0 || !/^[A-Za-z_][A-Za-z0-9_.]*$/.test(key)) break;
    i = eq + 1;
    let value = "";
    if (line[i] === '"') {
      i++;
      while (i < line.length && line[i] !== '"') {
        if (line[i] === "\\" && i + 1 < line.length) {
          const next = line[i + 1];
          value += next === "n" ? "\n" : next === "r" ? "\r" : next;
          i += 2;
        } else {
          value += line[i++];
        }
      }
      i++;
    } else {
      const end = line.indexOf(" ", i);
      value = end < 0 ? line.slice(i) : line.slice(i, end);
      i = end < 0 ? line.length : end;
    }
    pairs.push([key, value]);
  }
  const get = (key: string) => pairs.find(([name]) => name === key)?.[1] ?? null;
  if (get("ts") === null || get("event") === null) {
    return { line, ts: null, level: null, event: null, task: null, fields: [] };
  }
  return { line, ts: get("ts"), level: get("level"), event: get("event"), task: get("task"),
    fields: pairs.filter(([key]) => !PICKED.has(key)) };
}
```

- [ ] **Step 4: Rebuild the page (tests first):** newest first; a row shows the time (HH:MM:SS, mono), a level dot with
  its word hidden for sight and read aloud, the event (mono), the task number, the other fields (key dimmed, value mono);
  a WARN row amber-tinted, an ERROR row red-tinted; a line that is not logfmt shows whole in mono. Filters: level
  (`Segmented`), event, task number, text; "Follow" (`Switch`, on by default) re-reads every 2 s while on and while
  `document.visibilityState === "visible"`. At ≤640 px a row stacks its fields under its time. Words `logs.*`.
  Tests: a WARN row and a non-logfmt row render; typing a task number calls `getLogs` with `task`; turning Follow off
  stops the re-reads (fake timers).
- [ ] **Step 5: Run** `cd ui && npx vitest run src/manage`; PASS.
- [ ] **Step 6: Commit** — "Lay the log out as rows to filter and follow".

### Task 12: Setup in the page's language, on the board

**Files:** Modify `ui/src/setup/SetupPage.tsx`, `WhoStep.tsx`, `BotStep.tsx`, `PeopleStep.tsx`, `ClaudeStep.tsx`,
`ProjectsStep.tsx`, `CommitsStep.tsx`, `SummaryStep.tsx`, `FolderBrowser.tsx`, their tests, the dictionaries.

- [ ] **Step 1: Test first:** `SetupPage.test.tsx` renders the wizard in Mongolian and finds "Dispatch-ийг тохируулах",
  the step titles ("Хэн", "Бот", "Та", "Claude Code", "Төслүүд", "Коммит", "Дүгнэлт") and "Эцэст нь хураангуйг батлах
  хүртэл юу ч бичигдэхгүй."
- [ ] **Step 2: Run** — FAIL.
- [ ] **Step 3: Implement:** every phrase of the eight files into `setup.*` keys (about 130), the layout unchanged but
  themed by the board; the existing English tests keep their words.
- [ ] **Step 4: Run** `cd ui && npx vitest run src/setup`; PASS.
- [ ] **Step 5: Commit** — "Set Dispatch up in Mongolian or English".

### Task 13: The Mini App: Mongolian throughout, the shared pages at phone width

**Files:** Modify `ui/src/App.tsx` (`MiniApp` inside `<LanguageProvider fixed="mn">`), `ui/src/mini/MiniApp.test.tsx`.

- [ ] **Step 1: Test first:** inside Telegram the People page (shared) reads "Хүмүүс" and its Rename button
  "Нэр солих"; an API refusal shows its Mongolian message (the request carried `Accept-Language: mn`); the Logs page at
  390 px stacks a row (`matchMedia` answering `(max-width: 640px)`).
- [ ] **Step 2: Run** — FAIL.
- [ ] **Step 3: Implement:** the provider fixed at `mn`; the shared pages already follow the shell's antd theme, so the
  Mini App keeps its world; the Mini App's own pages keep their Mongolian as written.
- [ ] **Step 4: Run** `cd ui && npx vitest run`; PASS.
- [ ] **Step 5: Commit** — "Speak Mongolian on every Mini App page, the shared ones included".

### Task 14: Browser tests, screenshots, and the docs

**Files:** Create `ui/e2e/language.spec.ts`; modify `ui/e2e/settings.spec.ts` and `ui/e2e/projects.spec.ts` (the restart
notice is the strip's lamp; projects are edited in the panel); `docs/adr/0029-pages-speak-mongolian-and-english.md`;
`docs/ARCHITECTURE.md` (the web UI section); `README.md`, `README.en.md` (a line on the language switch).

- [ ] **Step 1: Write the language test**

```ts
import { expect, test } from "@playwright/test";

test.describe("in Mongolian", () => {
  test.use({ locale: "mn-MN" });

  test("a browser that prefers Mongolian opens in Mongolian, down to a server's refusal", async ({ page }) => {
    await page.goto("/settings");
    await expect(page.getByRole("heading", { name: "Тохиргоо" })).toBeVisible();
    await page.getByLabel("Нэг удаад зэрэг ажиллах дээд тоо").fill("0");
    await page.getByRole("button", { name: "Хадгалах", exact: true }).click();
    await expect(page.getByText(/хамгийн багадаа 1/)).toBeVisible();
  });
});

test("the switch turns the page to Mongolian and remembers it", async ({ page }) => {
  await page.goto("/");
  await page.getByRole("button", { name: "Монгол" }).click();
  await expect(page.getByText("Тойм")).toBeVisible();
  await page.reload();
  await expect(page.getByText("Тойм")).toBeVisible();
  await page.getByRole("button", { name: "English" }).click();
});

test("no page scrolls sideways at phone width, in either language", async ({ page }) => {
  await page.setViewportSize({ width: 390, height: 844 });
  for (const language of ["English", "Монгол"]) {
    await page.goto("/");
    await page.getByRole("button", { name: language }).click();
    for (const path of ["/", "/projects", "/people", "/settings", "/logs"]) {
      await page.goto(path);
      const overflow = await page.evaluate(() => document.documentElement.scrollWidth - window.innerWidth);
      expect(overflow, `${path} in ${language}`).toBeLessThanOrEqual(0);
    }
  }
  await page.getByRole("button", { name: "English" }).click();
});
```

The Mongolian labels used here come from Task 10's dictionary; adjust the test to the exact words written there.

- [ ] **Step 2: Run** `(cd ui && npm run build) && ./mvnw -q -Pui package -DskipTests && (cd ui && npm run e2e)`; fix
  until PASS.
- [ ] **Step 3: Screenshots:** Playwright captures every page at 1280 and 390 pixels in both languages and the Mini
  App's shared pages at 390; review them against the spec's look (the frontend skill's self-critique), fix what reads
  badly, and send the owner the Mongolian ones to check the words.
- [ ] **Step 4: Docs:** ADR 0029 (pages speak Mongolian and English; the server renders its messages from two bundles by
  `Accept-Language`; the terminal stays English; the Mini App follows the bot's Mongolian; rejected: an i18n library,
  client-side translation of server codes, a light scheme); ARCHITECTURE's web UI section (the board, `Text`, the
  bundles); READMEs.
- [ ] **Step 5: Commit** — "Test the pages in Mongolian in a browser, and write the decision down".

### Task 15: Push, watch, review, merge

- [ ] **Step 1:** Scan the diff (`git diff main > ../d1.diff`; the patterns of the Global Constraints, `"$HOME"` included).
- [ ] **Step 2:** Push `d1-desktop`, open a draft PR, watch the run on the three OSes to the end, fix red.
- [ ] **Step 3:** A reviewer on the whole branch with the spec and this plan; fix Critical and Important test-first.
- [ ] **Step 4:** Merge into local `main` with `--no-ff`; the whole suite and `npm test` on the merge; ask the owner
  before pushing `main` or deploying (the Mini App's shared pages change with the jar).
