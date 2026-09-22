package dispatch.config;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dispatch.config.ConfigEdit.At;
import org.junit.jupiter.api.Test;

class ConfigEditTest {

    private static final String CONFIG = """
            # my dispatch
            team: bold
            telegram:
              admins:
                - 100        # me
              groups:
                - name: bold
                  members:
                    - id: 100
                      name: 'Bold'
                    - id: 222
                      name: 'Ali'
                  projects:
                    - alm
                    - life

            limits:
              plan:
                timeout: 15m     # keep it short
                budgetUsd: 2

            projects:
              - name: alm
                path: '/home/bold/alm'
                baseBranch: main
                # model: opus    # maybe later
              - name: life
                path: '/home/bold/life'
                baseBranch: master
                model: opus
            """;

    @Test
    void anExistingValueIsReplacedAndItsCommentStays() {
        String after = ConfigEdit.set(CONFIG, At.of("limits", "plan", "timeout"), "30m");

        assertEquals(CONFIG.replace("timeout: 15m     # keep it short", "timeout: 30m     # keep it short"), after);
    }

    @Test
    void aQuotedValueIsReplacedWithItsQuotes() {
        String after = ConfigEdit.set(CONFIG, At.of("telegram", "groups").item("name", "bold").key("members").item("id", "222").key("name"),
                "Ali Ba'ba");

        assertEquals(CONFIG.replace("name: 'Ali'", "name: 'Ali Ba''ba'"), after);
    }

    @Test
    void aMissingKeyGoesAfterTheLastLineOfItsMapping() {
        String after = ConfigEdit.set(CONFIG, At.of("projects").item("name", "life").key("effort"), "high");

        assertEquals(CONFIG.replace("    model: opus\n", "    model: opus\n    effort: high\n"), after);
    }

    @Test
    void aMissingBlockIsCreatedWithItsKey() {
        String after = ConfigEdit.set(CONFIG, At.of("projects").item("name", "alm").key("plan", "model"), "opus");

        assertEquals(CONFIG.replace("    baseBranch: main\n", "    baseBranch: main\n    plan:\n      model: opus\n"), after,
                "after the project's last value, before the comment lines under it");
    }

    @Test
    void anEmptyValueIsFilledIn() {
        String before = "projects:\n  - name: alm\n    plan:\n    agent: claude-code\n";

        String model = ConfigEdit.set(before, At.of("projects").item("name", "alm").key("plan", "model"), "opus");
        String empty = ConfigEdit.set("a:\n  b:   # later\n", At.of("a", "b"), "x");

        assertEquals("projects:\n  - name: alm\n    plan:\n      model: opus\n    agent: claude-code\n", model);
        assertEquals("a:\n  b: x   # later\n", empty);
    }

    @Test
    void typedTextCannotBecomeYaml() {
        ConfigException injected = assertThrows(ConfigException.class, () -> ConfigEdit.set(CONFIG, At.of("team"), "x\nadmins: [1]"));
        String reserved = ConfigEdit.set(CONFIG, At.of("team"), "no");
        String colon = ConfigEdit.set(CONFIG, At.of("projects").item("name", "alm").key("baseBranch"), "a: b");

        assertEquals("a value must be plain text on one line", injected.getMessage());
        assertTrue(reserved.contains("team: 'no'\n"), reserved);
        assertTrue(colon.contains("baseBranch: 'a: b'\n"), colon);
    }

    @Test
    void aKeyIsRemovedWithItsBlock() {
        String before = CONFIG.replace("    model: opus\n", "    model: opus\n    plan:\n      model: opus   # deep\n      effort: max\n");

        String after = ConfigEdit.remove(before, At.of("projects").item("name", "life").key("plan"));

        assertEquals(CONFIG, after);
    }

    @Test
    void listItemsAreRemovedWithAllTheirLines() {
        String project = ConfigEdit.remove(CONFIG, At.of("projects").item("name", "alm"));
        String listed = ConfigEdit.remove(CONFIG, At.of("telegram", "groups").item("name", "bold").key("projects").value("alm"));
        String member = ConfigEdit.remove(CONFIG, At.of("telegram", "groups").item("name", "bold").key("members").item("id", "222"));
        String admin = ConfigEdit.remove(CONFIG, At.of("telegram", "admins").value("100"));

        assertEquals(CONFIG.replace("""
                  - name: alm
                    path: '/home/bold/alm'
                    baseBranch: main
                """, ""), project, "the comment under the project stays: it may be about the next one");
        assertEquals(CONFIG.replace("        - alm\n", ""), listed);
        assertEquals(CONFIG.replace("        - id: 222\n          name: 'Ali'\n", ""), member);
        assertEquals(CONFIG.replace("    - 100        # me\n", ""), admin);
    }

    @Test
    void anAdminIsAppendedAndAnEmptyListIsFilled() {
        String more = ConfigEdit.append(CONFIG, At.of("telegram", "admins"), "222");
        String emptied = ConfigEdit.append("telegram:\n  admins:\n  groups: []\n", At.of("telegram", "admins"), "222");
        String missing = ConfigEdit.append("telegram:\n  groups: []\n", At.of("telegram", "admins"), "222");

        assertEquals(CONFIG.replace("    - 100        # me\n", "    - 100        # me\n    - 222\n"), more);
        assertEquals("telegram:\n  admins:\n    - 222\n  groups: []\n", emptied);
        assertEquals("telegram:\n  groups: []\n  admins:\n    - 222\n", missing);
    }

    @Test
    void windowsLineEndingsAreKept() {
        String before = "projects:\r\n  - name: alm\r\n    baseBranch: main\r\n";

        String after = ConfigEdit.set(before, At.of("projects").item("name", "alm").key("plan", "effort"), "high");

        assertEquals("projects:\r\n  - name: alm\r\n    baseBranch: main\r\n    plan:\r\n      effort: high\r\n", after);
    }

    @Test
    void flowStyleIsRefusedWithWhatToDo() {
        String flow = "telegram:\n  groups:\n    - { name: bold, projects: [alm, life] }\n";

        ConfigException list = assertThrows(ConfigException.class,
                () -> ConfigEdit.remove(flow, At.of("telegram", "groups").item("name", "bold").key("projects").value("life")));
        ConfigException map = assertThrows(ConfigException.class,
                () -> ConfigEdit.set(flow, At.of("telegram", "groups").item("name", "bold").key("chatId"), "-100"));

        assertEquals("telegram.groups[name=bold].projects is written in flow style ({...} or [...]); rewrite it as a block, "
                + "one entry per line, to change it here", list.getMessage());
        assertTrue(map.getMessage().startsWith("telegram.groups[name=bold] is written in flow style"), map.getMessage());
    }

    @Test
    void whatIsNotThereIsNamed() {
        ConfigException project = assertThrows(ConfigException.class, () -> ConfigEdit.remove(CONFIG, At.of("projects").item("name", "crm")));
        ConfigException nested = assertThrows(ConfigException.class,
                () -> ConfigEdit.set(CONFIG, At.of("projects").item("name", "crm").key("model"), "opus"));
        ConfigException firstKey = assertThrows(ConfigException.class,
                () -> ConfigEdit.remove(CONFIG, At.of("projects").item("name", "alm").key("name")));

        assertEquals("no projects[name=crm] in the config", project.getMessage());
        assertEquals("no projects[name=crm] in the config", nested.getMessage());
        assertTrue(firstKey.getMessage().contains("shares its line"), firstKey.getMessage());
    }

    @Test
    void controlAndUnicodeLineSeparatorsInAValueAreRejected() {
        ConfigException ls = assertThrows(ConfigException.class, () -> ConfigEdit.set(CONFIG, At.of("team"), "a\u2028b"));
        ConfigException ps = assertThrows(ConfigException.class, () -> ConfigEdit.set(CONFIG, At.of("team"), "a\u2029b"));
        ConfigException nel = assertThrows(ConfigException.class, () -> ConfigEdit.set(CONFIG, At.of("team"), "a\u0085b"));
        ConfigException tab = assertThrows(ConfigException.class, () -> ConfigEdit.set(CONFIG, At.of("team"), "a\tb"));

        assertEquals("a value must be plain text on one line", ls.getMessage());
        assertEquals("a value must be plain text on one line", ps.getMessage());
        assertEquals("a value must be plain text on one line", nel.getMessage());
        assertEquals("a value must be plain text on one line", tab.getMessage());
    }

    @Test
    void aConfigContainingAUnicodeLineSeparatorIsRefused() {
        String before = "team: bold\u2028\nx: 1\n";

        ConfigException e = assertThrows(ConfigException.class, () -> ConfigEdit.set(before, At.of("x"), "2"));

        assertEquals("the config contains a Unicode line separator; remove it and try again", e.getMessage());
    }

    @Test
    void aConfigContainingALoneCarriageReturnIsRefused() {
        // SnakeYAML reads a lone \r as its own line break; Lines counts only '\n', so a file holding one (e.g. from
        // before this was refused at write time) must be refused too, rather than silently misplacing every edit.
        String before = "team: bold\rx\nprojects:\n  - name: alm\n";

        ConfigException e = assertThrows(ConfigException.class, () -> ConfigEdit.set(before, At.of("team"), "x"));

        assertEquals("the config contains a carriage return without a line feed; remove it and try again", e.getMessage());
    }

    @Test
    void aKeyWithABlockScalarValueIsRefused() {
        String before = "a:\n  b: |\n    text\n  c: 1\nd: 2\n";

        ConfigException e = assertThrows(ConfigException.class, () -> ConfigEdit.remove(before, At.of("a", "b")));

        assertEquals("a.b is a multi-line value; edit it by hand", e.getMessage());
    }

    @Test
    void settingABlockScalarValueIsRefused() {
        String before = "a:\n  b: |\n    text\nc: 1\n";

        ConfigException e = assertThrows(ConfigException.class, () -> ConfigEdit.set(before, At.of("a", "b"), "x"));

        assertEquals("a.b is a multi-line value; edit it by hand", e.getMessage());
    }

    @Test
    void aMissingKeyAfterABlockScalarIsRefused() {
        String before = "a:\n  b: |\n    text\nc: 1\n";

        ConfigException e = assertThrows(ConfigException.class, () -> ConfigEdit.set(before, At.of("a", "d"), "x"));

        assertEquals("a is a multi-line value; edit it by hand", e.getMessage());
    }

    @Test
    void aliasedValuesAreRefused() {
        String before = "defs:\n  greeting: &g hello\nx: *g\n";

        ConfigException setEx = assertThrows(ConfigException.class, () -> ConfigEdit.set(before, At.of("x"), "hi"));
        ConfigException removeEx = assertThrows(ConfigException.class, () -> ConfigEdit.remove(before, At.of("x")));

        assertEquals("x uses a YAML alias; edit it by hand", setEx.getMessage());
        assertEquals("x uses a YAML alias; edit it by hand", removeEx.getMessage());
    }

    @Test
    void aKeyNameMustBeAWord() {
        ConfigException e = assertThrows(ConfigException.class, () -> ConfigEdit.set("a:\n", At.of("a", "x: y\nz"), "v"));

        assertEquals("a config key must be letters, digits, '_' or '-'", e.getMessage());
    }

    @Test
    void aDashValueIsQuoted() {
        String after = ConfigEdit.set(CONFIG, At.of("team"), "-");

        assertTrue(after.contains("team: '-'\n"), after);
    }
}
