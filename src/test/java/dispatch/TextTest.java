package dispatch;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.text.MessageFormat;
import java.util.List;
import java.util.Properties;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;

/** Words for a person in their language: the bundles behind Text, and how a text renders. */
class TextTest {

    @Test
    void aKeyedTextRendersInEachLanguageWithItsArguments() {
        Text noTask = Text.of("refusal.noTask", 42);

        assertEquals("no task #42 here", noTask.render(Language.EN));
        assertEquals("#42 даалгавар энд алга", noTask.render(Language.MN));
        assertEquals("no task #42 here", noTask.toString(), "string concatenation and the log read English");
    }

    @Test
    void aLargeNumberKeepsItsDigitsTogether() {
        assertEquals("no task #12345 here", Text.of("refusal.noTask", 12345).render(Language.EN));
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
    void aTextIsWrittenToJsonInTheLanguageTheWriterWasGiven() {
        record Answer(Text message) {
        }

        assertEquals("{\"message\":\"#5 даалгавар энд алга\"}", Json.write(new Answer(Text.of("refusal.noTask", 5)), Language.MN));
        assertEquals("{\"message\":\"no task #5 here\"}", Json.write(new Answer(Text.of("refusal.noTask", 5))));
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
        for (String key : en.stringPropertyNames()) {
            new MessageFormat(en.getProperty(key)); // a broken {…} fails here
            if (mn.containsKey(key)) {
                assertEquals(placeholders(en.getProperty(key)), placeholders(mn.getProperty(key)), key);
                new MessageFormat(mn.getProperty(key));
            }
        }
    }

    /**
     * A key the code names but the English bundle lacks would answer a page with a 500 on that path; not every message's
     * path has a test of its own, so the names are read from the source. Keys are always literals, never built.
     */
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
