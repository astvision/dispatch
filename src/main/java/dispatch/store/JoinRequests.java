package dispatch.store;

import dispatch.domain.Requester;
import java.sql.SQLException;
import java.time.Instant;
import java.util.Optional;

/** SQL for requests to join a shared bot (ADR 0015). Decisions are conditional on the request still being open. */
public final class JoinRequests {

    private static final String COLUMNS = "id, requester_ref, requester_name, username, status, group_name, decided_by_name, decided_at";

    private JoinRequests() {
    }

    /**
     * @param username  the Telegram @handle without "@", null when the person has none
     * @param groupName the group they were added to, once approved
     */
    public record JoinRequest(long id, Requester requester, String username, String status, String groupName, String decidedByName,
                              Instant decidedAt) {
    }

    public static long insert(Tx tx, Requester requester, String username, Instant now) {
        return tx.insert("INSERT INTO join_request (requester_ref, requester_name, username, status, created_at) VALUES (?, ?, ?, 'OPEN', ?)",
                requester.ref(), requester.name(), username, now);
    }

    public static Optional<JoinRequest> find(Tx tx, long id) {
        return tx.one("SELECT " + COLUMNS + " FROM join_request WHERE id = ?", JoinRequests::map, id);
    }

    /** The person's most recent request, whatever became of it. */
    public static Optional<JoinRequest> latest(Tx tx, String requesterRef) {
        return tx.one("SELECT " + COLUMNS + " FROM join_request WHERE requester_ref = ? ORDER BY id DESC LIMIT 1", JoinRequests::map,
                requesterRef);
    }

    public static boolean approve(Tx tx, long id, String groupName, Requester admin, Instant now) {
        return tx.update("""
                        UPDATE join_request SET status = 'APPROVED', group_name = ?, decided_by = ?, decided_by_name = ?, decided_at = ?
                        WHERE id = ? AND status = 'OPEN'""",
                groupName, admin.ref(), admin.name(), now, id) == 1;
    }

    public static boolean deny(Tx tx, long id, Requester admin, Instant now) {
        return tx.update("""
                        UPDATE join_request SET status = 'DENIED', decided_by = ?, decided_by_name = ?, decided_at = ?
                        WHERE id = ? AND status = 'OPEN'""",
                admin.ref(), admin.name(), now, id) == 1;
    }

    private static JoinRequest map(Row row) throws SQLException {
        return new JoinRequest(row.longValue("id"), new Requester(row.string("requester_ref"), row.string("requester_name")),
                row.string("username"), row.string("status"), row.string("group_name"), row.string("decided_by_name"),
                row.instant("decided_at"));
    }
}
