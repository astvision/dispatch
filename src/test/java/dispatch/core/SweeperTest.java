package dispatch.core;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dispatch.domain.Phase;
import dispatch.workspace.Workspaces.WorktreeState;
import java.util.List;
import org.junit.jupiter.api.Test;

/** Whether an idle worktree may go, decided from what is known about its task: no git, no store. */
class SweeperTest {

    private static final WorktreeState CLEAN_PUSHED = new WorktreeState(List.of(), true);
    private static final WorktreeState CLEAN_UNPUSHED = new WorktreeState(List.of(), false);
    private static final WorktreeState DIRTY = new WorktreeState(List.of("src/A.java"), true);

    @Test
    void aWorktreeWithNothingToLoseGoesWhateverIsKnownOfItsTask() {
        assertTrue(Sweeper.mayRemove(Phase.COMPLETED, false, CLEAN_PUSHED));
        assertTrue(Sweeper.mayRemove(Phase.FAILED, false, CLEAN_PUSHED));
        assertTrue(Sweeper.mayRemove(null, false, CLEAN_PUSHED), "a member's computer knows no phase");
    }

    @Test
    void workNotOnOriginIsKeptUnlessTheTaskWasAbandonedOrMerged() {
        assertFalse(Sweeper.mayRemove(Phase.COMPLETED, false, CLEAN_UNPUSHED));
        assertFalse(Sweeper.mayRemove(Phase.FAILED, false, DIRTY));
        assertFalse(Sweeper.mayRemove(null, false, CLEAN_UNPUSHED));
        assertFalse(Sweeper.mayRemove(null, false, DIRTY));
        assertTrue(Sweeper.mayRemove(Phase.REJECTED, false, DIRTY), "nothing in a rejected task's worktree was wanted");
        assertTrue(Sweeper.mayRemove(Phase.CANCELLED, false, CLEAN_UNPUSHED));
        assertTrue(Sweeper.mayRemove(Phase.COMPLETED, true, CLEAN_UNPUSHED), "the merge may have deleted the branch on origin");
    }

    @Test
    void aMergedTasksUndeliveredChangesAreStillKept() {
        assertFalse(Sweeper.mayRemove(Phase.COMPLETED, true, DIRTY));
    }
}
