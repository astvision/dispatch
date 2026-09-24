package dispatch.store;

import dispatch.domain.GroupAck;
import java.time.Instant;

/** A member's own choice of how a group hears about their task (G-1e). Unset defaults to reaction. */
public final class MemberPrefs {

    private MemberPrefs() {
    }

    public static GroupAck groupAck(Tx tx, long userId) {
        return tx.one("SELECT group_ack FROM member_pref WHERE user_id = ?",
                row -> GroupAck.fromValue(row.string("group_ack")), userId).orElse(GroupAck.REACTION);
    }

    public static void setGroupAck(Tx tx, long userId, GroupAck value, Instant now) {
        tx.update("""
                        INSERT INTO member_pref (user_id, group_ack, updated_at) VALUES (?, ?, ?)
                        ON CONFLICT (user_id) DO UPDATE SET group_ack = excluded.group_ack, updated_at = excluded.updated_at""",
                userId, value.value(), now);
    }
}
