-- A-1: the bot's assistant. One Claude Code session per member's private chat, resumed until /new or 12 hours of silence.
CREATE TABLE assistant_session (
    member_ref   TEXT PRIMARY KEY,
    session_id   TEXT NOT NULL,
    last_used_at TEXT NOT NULL
);
-- Each turn's model and cost, for the log and for /stats' chat spend; there is no cap (the owner's choice).
CREATE TABLE assistant_turn (
    id         INTEGER PRIMARY KEY,
    member_ref TEXT NOT NULL,
    model      TEXT NOT NULL,
    cost_usd   TEXT,
    at         TEXT NOT NULL
);
CREATE INDEX assistant_turn_member_at ON assistant_turn (member_ref, at);
-- A change the assistant proposed, shown as a button; it runs once, when its member taps it, and outcome says how it
-- ended (DONE, STALE, NOT_ALLOWED), so the redrawn reply marks only what was actually carried out.
CREATE TABLE assistant_action (
    id         INTEGER PRIMARY KEY,
    member_ref TEXT NOT NULL,
    payload    TEXT NOT NULL,
    created_at TEXT NOT NULL,
    used_at    TEXT,
    outcome    TEXT
);
