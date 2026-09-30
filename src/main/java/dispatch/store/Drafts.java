package dispatch.store;

import com.fasterxml.jackson.databind.JsonNode;
import dispatch.Json;
import dispatch.domain.Draft;
import dispatch.domain.DraftStatus;
import dispatch.domain.Priority;
import dispatch.domain.Requester;
import dispatch.domain.SplitState;
import java.sql.SQLException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/** SQL for drafts. Changes are conditional on the draft still being open. */
public final class Drafts {

    private static final String COLUMNS = """
            id, requester_ref, requester_name, chat_ref, origin_ref, description, project, status, task_id, prompt_ref, split_state,
            topics, parent_id, part, created_at, updated_at, source_ref, priority, detail""";

    private Drafts() {
    }

    /**
     * @param project   null when the member still has to choose
     * @param parentId  the whole message's draft when this is one of its parts, otherwise null
     * @param part      the part's number from 1, null for a whole message
     * @param sourceRef someone's message the draft was given in reply to, whose replies are additions to it; null if none
     */
    public record NewDraft(Requester requester, String chatRef, String originRef, String description, String project, Long parentId,
                           Integer part, String sourceRef) {
    }

    public static long insert(Tx tx, NewDraft draft, Instant now) {
        return tx.insert("""
                        INSERT INTO draft (requester_ref, requester_name, chat_ref, origin_ref, description, project, status,
                                           parent_id, part, created_at, updated_at, source_ref, detail)
                        VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)""",
                draft.requester().ref(), draft.requester().name(), draft.chatRef(), draft.originRef(), draft.description(),
                draft.project(), DraftStatus.OPEN, draft.parentId(), draft.part(), now, now, draft.sourceRef(),
                // Without a project the short view has nothing to send: the prompt opens on its detail view, and stays there
                // once a project is chosen until ↩️.
                draft.project() == null ? 1 : 0);
    }

    public static Optional<Draft> find(Tx tx, long id) {
        return tx.one("SELECT " + COLUMNS + " FROM draft WHERE id = ?", Drafts::map, id);
    }

    public static boolean existsWithOrigin(Tx tx, String originRef) {
        return tx.one("SELECT 1 AS found FROM draft WHERE origin_ref = ?", row -> true, originRef).isPresent();
    }

    public static Optional<DraftStatus> statusByOrigin(Tx tx, String originRef) {
        return tx.one("SELECT status FROM draft WHERE origin_ref = ?", row -> DraftStatus.valueOf(row.string("status")), originRef);
    }

    /**
     * The drafts given in reply to someone's message {@code messageRef} (G-1b, G-1c), still open or given as tasks: a
     * split whole is left out for its parts, which carry the same message.
     */
    public static List<Draft> fromSource(Tx tx, String messageRef) {
        return tx.list("SELECT " + COLUMNS + " FROM draft WHERE source_ref = ? AND status IN (?, ?) ORDER BY id", Drafts::map,
                messageRef, DraftStatus.OPEN, DraftStatus.CREATED);
    }

    /** The open drafts message {@code messageRef} gave, matched as {@link Tasks#fromMessage} matches tasks. */
    public static List<Draft> openFromMessage(Tx tx, String messageRef) {
        return tx.list("""
                        SELECT %s FROM draft WHERE status = ? AND (origin_ref = ? OR origin_ref GLOB ? OR origin_ref GLOB ?)
                        ORDER BY id""".formatted(COLUMNS), Drafts::map,
                DraftStatus.OPEN, messageRef, messageRef + "@*", messageRef + "#*");
    }

    public static boolean chooseProject(Tx tx, long id, String project, Instant now) {
        return tx.update("UPDATE draft SET project = ?, updated_at = ? WHERE id = ? AND status = ?",
                project, now, id, DraftStatus.OPEN) == 1;
    }

    public static boolean choosePriority(Tx tx, long id, Priority priority, Instant now) {
        return tx.update("UPDATE draft SET priority = ?, updated_at = ? WHERE id = ? AND status = ?",
                priority, now, id, DraftStatus.OPEN) == 1;
    }

    /** Shows the prompt's detail view, or its short one; false when the draft is no longer open. */
    public static boolean showDetail(Tx tx, long id, boolean detail, Instant now) {
        return tx.update("UPDATE draft SET detail = ?, updated_at = ? WHERE id = ? AND status = ?",
                detail ? 1 : 0, now, id, DraftStatus.OPEN) == 1;
    }

    public static boolean created(Tx tx, long id, long taskId, Instant now) {
        return tx.update("UPDATE draft SET status = ?, task_id = ?, updated_at = ? WHERE id = ? AND status = ?",
                DraftStatus.CREATED, taskId, now, id, DraftStatus.OPEN) == 1;
    }

    /** Only a whole message that is open and not split yet (or whose split failed) can start splitting. */
    public static boolean startSplit(Tx tx, long id, String promptRef, Instant now) {
        return tx.update("""
                        UPDATE draft SET split_state = ?, prompt_ref = ?, updated_at = ?
                        WHERE id = ? AND status = ? AND parent_id IS NULL AND (split_state IS NULL OR split_state = ?)""",
                SplitState.SPLITTING, promptRef, now, id, DraftStatus.OPEN, SplitState.FAILED) == 1;
    }

    /** Records how a running split ended; false when the draft is no longer open or splitting. */
    public static boolean finishSplit(Tx tx, long id, SplitState state, List<String> topics, Instant now) {
        return tx.update("UPDATE draft SET split_state = ?, topics = ?, updated_at = ? WHERE id = ? AND status = ? AND split_state = ?",
                state, topics.isEmpty() ? null : Json.write(topics), now, id, DraftStatus.OPEN, SplitState.SPLITTING) == 1;
    }

    /** The writer took the proposal: the whole message's draft is closed in favour of its parts. */
    public static boolean split(Tx tx, long id, Instant now) {
        return tx.update("UPDATE draft SET status = ?, updated_at = ? WHERE id = ? AND status = ? AND split_state = ?",
                DraftStatus.SPLIT, now, id, DraftStatus.OPEN, SplitState.PROPOSED) == 1;
    }

    public static boolean keepWhole(Tx tx, long id, Instant now) {
        return tx.update("UPDATE draft SET split_state = ?, topics = NULL, updated_at = ? WHERE id = ? AND status = ? AND split_state = ?",
                SplitState.KEPT, now, id, DraftStatus.OPEN, SplitState.PROPOSED) == 1;
    }

    public static List<Draft> openAndSplitting(Tx tx) {
        return tx.list("SELECT " + COLUMNS + " FROM draft WHERE status = ? AND split_state = ? ORDER BY id", Drafts::map,
                DraftStatus.OPEN, SplitState.SPLITTING);
    }

    public static List<Draft> openCreatedBefore(Tx tx, Instant cutoff) {
        return tx.list("SELECT " + COLUMNS + " FROM draft WHERE status = ? AND created_at < ? ORDER BY id", Drafts::map,
                DraftStatus.OPEN, cutoff);
    }

    public static boolean expire(Tx tx, long id, Instant now) {
        return tx.update("UPDATE draft SET status = ?, updated_at = ? WHERE id = ? AND status = ?",
                DraftStatus.EXPIRED, now, id, DraftStatus.OPEN) == 1;
    }

    /** Closes an open draft its writer says is not a task; false when it was no longer open. */
    public static boolean discard(Tx tx, long id, Instant now) {
        return tx.update("UPDATE draft SET status = ?, updated_at = ? WHERE id = ? AND status = ?",
                DraftStatus.DISCARDED, now, id, DraftStatus.OPEN) == 1;
    }

    private static Draft map(Row row) throws SQLException {
        return new Draft(
                row.longValue("id"),
                row.string("requester_ref"),
                row.string("requester_name"),
                row.string("chat_ref"),
                row.string("origin_ref"),
                row.string("description"),
                row.string("project"),
                row.enumValue("status", DraftStatus.class),
                row.longOrNull("task_id"),
                row.string("prompt_ref"),
                row.enumValue("split_state", SplitState.class),
                topics(row.string("topics")),
                row.longOrNull("parent_id"),
                row.intOrNull("part"),
                row.instant("created_at"),
                row.instant("updated_at"),
                row.string("source_ref"),
                Objects.requireNonNullElse(row.enumValue("priority", Priority.class), Priority.LOW),
                row.intValue("detail") == 1);
    }

    private static List<String> topics(String json) {
        List<String> topics = new ArrayList<>();
        if (json != null) {
            for (JsonNode topic : Json.read(json)) {
                topics.add(topic.asText());
            }
        }
        return List.copyOf(topics);
    }
}
