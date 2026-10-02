package dispatch.core;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dispatch.domain.CuratedPlugins;
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

    /** What an execution left out reaches the team in its summary, on a line of its own (ponytail). */
    @Test
    void anExecutionSummaryNamesWhatWasLeftOut() {
        assertTrue(Prompts.execute(task(), "{}").contains("skipped: <what>, add when <when>"), Prompts.execute(task(), "{}"));
    }

    /** Ponytail by default (ADR 0034): the simplest change that works, planned, built and fixed where every caller passes. */
    @Test
    void planExecuteAndTestFixRunsAreToldToMakeTheSimplestChangeThatWorks() {
        for (Prompts.SkillNote note : List.of(Prompts.SkillNote.PLAN, Prompts.SkillNote.EXECUTE, Prompts.SkillNote.FIX_TEST)) {
            assertTrue(note.text().contains("dispatch:ponytail"), note + ": " + note.text());
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

    @Test
    void thePluginNoteNamesEveryCuratedPluginAndWhenToPickIt() {
        for (CuratedPlugins.Entry entry : CuratedPlugins.ALL) {
            assertTrue(Prompts.PLUGIN_NOTE.contains("- " + entry.name() + ": when " + entry.when()), Prompts.PLUGIN_NOTE);
        }
        assertTrue(Prompts.PLUGIN_NOTE.contains("plugins field"), Prompts.PLUGIN_NOTE);
    }
}
