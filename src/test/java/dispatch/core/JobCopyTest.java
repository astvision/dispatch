package dispatch.core;

import static org.junit.jupiter.api.Assertions.assertEquals;

import dispatch.agent.RunRequest;
import dispatch.domain.Attachment;
import dispatch.domain.RunKind;
import java.lang.reflect.RecordComponent;
import java.math.BigDecimal;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/** A copy made by the record itself changes what it names and keeps every other field, whatever fields are added later. */
class JobCopyTest {

    private static final Job.Project PROJECT = new Job.Project("alm", "git@github.com:acme/alm.git", "/team/alm", "main",
            "claude-code", List.of(".env"), "./mvnw -q test", true, true);
    private static final Job JOB = new Job(7, 2, RunKind.EXECUTE, PROJECT, "main", "6f3030a", "/w/7",
            "https://github.com/acme/alm/pull/9", UUID.fromString("11111111-2222-3333-4444-555555555555"), true, "Implement",
            "opus", "low", 1000L, new BigDecimal("2"), List.of(new Attachment("id", "1-a.jpg", 3L)), "dispatch #7: x",
            List.of("Requested-by: Bold"), "summary", "dispatch/team/7", "Review it", "9c1e2d4", List.of("playwright"));

    @Test
    void aJobWithAnotherProjectKeepsEveryOtherField() throws Exception {
        Job.Project mine = PROJECT.withPath("/home/ann/alm");

        Job copy = JOB.withProject(mine);

        assertEquals(mine, copy.project());
        assertSameExcept(JOB, copy, Set.of("project"));
        assertEquals("/home/ann/alm", mine.path());
        assertSameExcept(PROJECT, mine, Set.of("path"));
    }

    @Test
    void aJobWithAnotherModelKeepsEveryOtherField() throws Exception {
        Job copy = JOB.withModel("sonnet", "high");

        assertEquals("sonnet", copy.model());
        assertEquals("high", copy.effort());
        assertSameExcept(JOB, copy, Set.of("model", "effort"));
    }

    @Test
    void aRunsNextCallKeepsWhereAndWithWhatItRuns() throws Exception {
        RunRequest first = new RunRequest(RunKind.EXECUTE, Path.of("/w/7"), "Implement", UUID.randomUUID(), false,
                List.of(Path.of("/files")), new BigDecimal("2"), "opus", "low", Path.of("/runs/7/2"), Map.of("A", "b"),
                List.of(Path.of("/plugins/dispatch")), List.of("playwright"));
        UUID session = UUID.randomUUID();

        RunRequest review = first.as(RunKind.REVIEW, "Review it", session, false, new BigDecimal("1"), Path.of("/runs/7/2.review"));

        assertEquals(new RunRequest(RunKind.REVIEW, Path.of("/w/7"), "Review it", session, false, List.of(Path.of("/files")),
                new BigDecimal("1"), "opus", "low", Path.of("/runs/7/2.review"), Map.of("A", "b"),
                List.of(Path.of("/plugins/dispatch")), List.of("playwright")), review);
        assertSameExcept(first, review, Set.of("kind", "prompt", "sessionId", "resume", "budgetUsd", "logBase"));
    }

    private static void assertSameExcept(Record original, Record copy, Set<String> changed) throws Exception {
        for (RecordComponent component : original.getClass().getRecordComponents()) {
            if (!changed.contains(component.getName())) {
                assertEquals(component.getAccessor().invoke(original), component.getAccessor().invoke(copy), component.getName());
            }
        }
    }
}
