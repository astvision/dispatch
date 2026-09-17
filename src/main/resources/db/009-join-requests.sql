-- M3e: people ask to use a shared bot, and an admin decides in Telegram (ADR 0015).
CREATE TABLE join_request (
    id             INTEGER PRIMARY KEY,
    requester_ref  TEXT NOT NULL,
    requester_name TEXT NOT NULL,
    username       TEXT,
    status         TEXT NOT NULL CHECK (status IN ('OPEN', 'APPROVED', 'DENIED')),
    group_name     TEXT,
    decided_by     TEXT,
    decided_by_name TEXT,
    created_at     TEXT NOT NULL,
    decided_at     TEXT
);

CREATE INDEX join_request_requester ON join_request (requester_ref, id);
