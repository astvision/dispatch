-- G-1e: a member's own choice of how a group hears about their task, replacing the old envelope line by default.
CREATE TABLE member_pref (
    user_id    INTEGER PRIMARY KEY,
    group_ack  TEXT NOT NULL,
    updated_at TEXT NOT NULL
);
