package dispatch.store;

import dispatch.domain.Draft;
import dispatch.domain.DraftStatus;
import dispatch.domain.Requester;
import java.sql.SQLException;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

/** SQL for drafts. Changes are conditional on the draft still being open. */
public final class Drafts {

    private static final String COLUMNS = """
            id, requester_ref, requester_name, chat_ref, origin_ref, description, project, status, task_id, created_at, updated_at""";

    private Drafts() {
    }

    /** @param project null when the member still has to choose */
    public record NewDraft(Requester requester, String chatRef, String originRef, String description, String project) {
    }

    public static long insert(Tx tx, NewDraft draft, Instant now) {
        return tx.insert("""
                        INSERT INTO draft (requester_ref, requester_name, chat_ref, origin_ref, description, project, status,
                                           created_at, updated_at)
                        VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)""",
                draft.requester().ref(), draft.requester().name(), draft.chatRef(), draft.originRef(), draft.description(),
                draft.project(), DraftStatus.OPEN, now, now);
    }

    public static Optional<Draft> find(Tx tx, long id) {
        return tx.one("SELECT " + COLUMNS + " FROM draft WHERE id = ?", Drafts::map, id);
    }

    public static boolean existsWithOrigin(Tx tx, String originRef) {
        return tx.one("SELECT 1 AS found FROM draft WHERE origin_ref = ?", row -> true, originRef).isPresent();
    }

    public static boolean chooseProject(Tx tx, long id, String project, Instant now) {
        return tx.update("UPDATE draft SET project = ?, updated_at = ? WHERE id = ? AND status = ?",
                project, now, id, DraftStatus.OPEN) == 1;
    }

    public static boolean created(Tx tx, long id, long taskId, Instant now) {
        return tx.update("UPDATE draft SET status = ?, task_id = ?, updated_at = ? WHERE id = ? AND status = ?",
                DraftStatus.CREATED, taskId, now, id, DraftStatus.OPEN) == 1;
    }

    public static List<Draft> openCreatedBefore(Tx tx, Instant cutoff) {
        return tx.list("SELECT " + COLUMNS + " FROM draft WHERE status = ? AND created_at < ? ORDER BY id", Drafts::map,
                DraftStatus.OPEN, cutoff);
    }

    public static boolean expire(Tx tx, long id, Instant now) {
        return tx.update("UPDATE draft SET status = ?, updated_at = ? WHERE id = ? AND status = ?",
                DraftStatus.EXPIRED, now, id, DraftStatus.OPEN) == 1;
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
                row.instant("created_at"),
                row.instant("updated_at"));
    }
}
