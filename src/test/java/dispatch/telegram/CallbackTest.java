package dispatch.telegram;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dispatch.core.TaskCommand;
import dispatch.domain.Priority;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class CallbackTest {

    /** One sample of every button, with the exact data it has always carried: chats hold these, so they may not change. */
    private static final Map<Callback, String> GOLDEN = new LinkedHashMap<>();

    static {
        GOLDEN.put(new Callback.Approve(42, 3), "approve:42:3");
        GOLDEN.put(new Callback.Reject(42, 3), "reject:42:3");
        GOLDEN.put(new Callback.StatusPriority(42, Priority.URGENT), "prio:42:URGENT");
        GOLDEN.put(new Callback.DraftProject(7, "backend"), "draft:7:p:backend");
        GOLDEN.put(new Callback.DraftPriority(7, Priority.LOW), "draft:7:prio:LOW");
        GOLDEN.put(new Callback.DraftSplit(7, Callback.Split.ASK), "draft:7:split:ask");
        GOLDEN.put(new Callback.DraftSplit(7, Callback.Split.YES), "draft:7:split:yes");
        GOLDEN.put(new Callback.DraftSplit(7, Callback.Split.NO), "draft:7:split:no");
        GOLDEN.put(new Callback.DraftDiscard(7), "draft:7:discard:x");
        GOLDEN.put(new Callback.DraftSend(7), "draft:7:send:x");
        GOLDEN.put(new Callback.DraftView(7, true), "draft:7:view:detail");
        GOLDEN.put(new Callback.DraftView(7, false), "draft:7:view:default");
        GOLDEN.put(new Callback.DraftPick(7, Priority.URGENT), "draft:7:pick:URGENT");
        GOLDEN.put(new Callback.Answer(42, 2, 1, new TaskCommand.Choice.Option(3)), "q:42:2:1:3");
        GOLDEN.put(new Callback.Answer(42, 2, 1, new TaskCommand.Choice.YouDecide()), "q:42:2:1:d");
        GOLDEN.put(new Callback.WriteAnswer(42, 2, 1), "q:42:2:1:w");
        GOLDEN.put(new Callback.AssistantAction(9), "as:9");
        GOLDEN.put(new Callback.Addition(11), "ad:11");
        GOLDEN.put(new Callback.Merge(42), "merge:42");
        GOLDEN.put(new Callback.Link(-1001234567890L, 2), "link:-1001234567890:2");
        GOLDEN.put(new Callback.NoLink(-1001234567890L), "link:-1001234567890:-");
        GOLDEN.put(new Callback.Join(5, "backend"), "join:5:backend");
        GOLDEN.put(new Callback.JoinDeny(5), "join:5:-");
        GOLDEN.put(new Callback.Stats("week", "me"), "stats:week:me");
        GOLDEN.put(new Callback.Stats("all", "people"), "stats:all:people");
        GOLDEN.put(new Callback.Stats("month", "group:backend"), "stats:month:group:backend");
        GOLDEN.put(new Callback.Help("status"), "help:status");
    }

    @Test
    void everyButtonCarriesItsFixedData() {
        GOLDEN.forEach((callback, data) -> assertEquals(data, callback.data(), callback.toString()));
    }

    @Test
    void everyButtonReadsBackAsItself() {
        GOLDEN.forEach((callback, data) -> assertEquals(Optional.of(callback), Callback.parse(callback.data()), data));
    }

    @Test
    void readsWhatTheOldParserAccepted() {
        // Blanks and a sign around a number, as Long.parseLong(strip()) always allowed.
        assertEquals(Optional.of(new Callback.Approve(42, 3)), Callback.parse("approve: 42 :+3"));
        // Trailing empty parts are dropped by split, so a trailing ':' changes nothing.
        assertEquals(Optional.of(new Callback.Help("task")), Callback.parse("help:task:"));
        assertEquals(Optional.of(new Callback.Merge(42)), Callback.parse("merge:42::"));
        // Any page is a page: an unknown one shows the home screen.
        assertEquals(Optional.of(new Callback.Help("nosuchpage")), Callback.parse("help:nosuchpage"));
        // Any fourth part discards.
        assertEquals(Optional.of(new Callback.DraftDiscard(7)), Callback.parse("draft:7:discard:whatever"));
        // A cut project name is read as written; TaskService resolves it by prefix.
        assertEquals(Optional.of(new Callback.DraftProject(7, "back")), Callback.parse("draft:7:p:back"));
        // Any option number, even one the question does not have: the command refuses it.
        assertEquals(Optional.of(new Callback.Answer(1, 1, 1, new TaskCommand.Choice.Option(12))), Callback.parse("q:1:1:1:12"));
        // Stats keeps the rest after the period, colons and all, for statsPayload to judge.
        assertEquals(Optional.of(new Callback.Stats("week", "group:a:b")), Callback.parse("stats:week:group:a:b"));
        assertEquals(Optional.of(new Callback.Stats("week", "")), Callback.parse("stats:week:"));
        assertEquals(Optional.of(new Callback.Stats("decade", "nobody")), Callback.parse("stats:decade:nobody"));
    }

    @Test
    void malformedDataIsNoButton() {
        for (String data : new String[] {"", ":", ":::", "unknown:1", "approve:1", "approve:1:2:3", "approve:x:1", "approve:1:x",
                "reject:1:", "prio:1:HIGH", "prio:1:urgent", "draft:1:p:", "draft:x:p:backend", "draft:1:prio:HIGH",
                "draft:1:split:ASK", "draft:1:split:maybe", "draft:1:other:x", "draft:1::x", "draft:1:p:a:b", "draft:1:view:other", "draft:1:pick:HIGH", "q:1:1:1",
                "q:1:1:1:x", "q:x:1:1:0", "q:1:1:1:0:0", "as:", "as:x", "ad:1:2", "merge:", "merge:x", "link:1", "link:x:1",
                "link:1:x", "link:1: -", "join:1", "join:x:backend", "join:1:", "help", "help:", "help:a:b", "stats:week",
                "stats", "Approve:1:1", " approve:1:1", null}) {
            assertEquals(Optional.empty(), Callback.parse(data), String.valueOf(data));
        }
    }

    @Test
    void theLongestGroupNameStillFits() {
        String group = "g".repeat(40); // ConfigLoader.MAX_GROUP_NAME

        assertTrue(bytes(new Callback.Join(1_000_000_000L, group).data()) <= Callback.LIMIT);
        assertTrue(bytes(new Callback.Stats("month", "group:" + group).data()) <= Callback.LIMIT);
    }

    @Test
    void aLongProjectNameIsCutToFit() {
        String name = "p".repeat(100);

        String data = new Callback.DraftProject(123, name).data();

        assertEquals(Callback.LIMIT, bytes(data));
        assertEquals("draft:123:p:" + "p".repeat(Callback.LIMIT - "draft:123:p:".length()), data);
    }

    @Test
    void dataPastTheLimitIsRefusedWhenBuilt() {
        assertThrows(IllegalArgumentException.class, () -> new Callback.Help("h".repeat(60)).data());
        // Multi-byte characters count as bytes, not chars: 30 Cyrillic letters are 60 bytes.
        assertThrows(IllegalArgumentException.class, () -> new Callback.Stats("week", "я".repeat(30)).data());
    }

    @Test
    void aGroupNamedLikeADenialCannotBeOffered() {
        assertThrows(IllegalArgumentException.class, () -> new Callback.Join(1, "-"));
    }

    @Test
    void aWrittenAnswerHasNoButton() {
        assertThrows(IllegalArgumentException.class, () -> new Callback.Answer(1, 1, 1, new TaskCommand.Choice.Written("x")));
    }

    private static int bytes(String data) {
        return data.getBytes(StandardCharsets.UTF_8).length;
    }
}
