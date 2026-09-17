-- Dispatch schema v1. Timestamps are UTC text 'yyyy-MM-ddTHH:mm:ss.SSSZ' (sortable); money is decimal text.

CREATE TABLE task (
    id             INTEGER PRIMARY KEY,
    project        TEXT NOT NULL,
    title          TEXT NOT NULL,
    description    TEXT NOT NULL,
    phase          TEXT NOT NULL CHECK (phase IN ('PLANNING', 'AWAITING_APPROVAL', 'EXECUTING', 'COMPLETED', 'FAILED', 'REJECTED', 'CANCELLED')),
    requester_ref  TEXT NOT NULL,
    requester_name TEXT NOT NULL,
    origin_ref     TEXT NOT NULL UNIQUE,
    chat_ref       TEXT NOT NULL,
    session_id     TEXT NOT NULL,
    base_branch    TEXT NOT NULL,
    base_sha       TEXT,
    worktree       TEXT,
    plan_json      TEXT,
    failure_reason TEXT CHECK (failure_reason IN ('SETUP', 'AGENT', 'TIMEOUT', 'BUDGET', 'INTERRUPTED', 'DELIVERY', 'INTERNAL')),
    failure_detail TEXT,
    created_at     TEXT NOT NULL,
    started_at     TEXT,
    completed_at   TEXT,
    updated_at     TEXT NOT NULL
);

CREATE INDEX task_phase ON task (phase);

CREATE TABLE run (
    task_id        INTEGER NOT NULL REFERENCES task (id),
    seq            INTEGER NOT NULL,
    kind           TEXT    NOT NULL CHECK (kind IN ('PLAN', 'EXECUTE', 'DELIVER')),
    status         TEXT    NOT NULL CHECK (status IN ('QUEUED', 'RUNNING', 'SUCCEEDED', 'FAILED', 'CANCELLED')),
    instruction    TEXT    NOT NULL,
    requested_by   TEXT    NOT NULL,
    pid            INTEGER,
    pid_start      TEXT,
    queued_at      TEXT    NOT NULL,
    started_at     TEXT,
    finished_at    TEXT,
    exit_code      INTEGER,
    failure_reason TEXT CHECK (failure_reason IN ('SETUP', 'AGENT', 'TIMEOUT', 'BUDGET', 'INTERRUPTED', 'DELIVERY', 'INTERNAL')),
    error_detail   TEXT,
    cost_usd       TEXT,
    turns          INTEGER,
    output         TEXT,
    denials        TEXT,
    PRIMARY KEY (task_id, seq)
);

CREATE INDEX run_status ON run (status, queued_at);

-- Append-only audit trail of phase changes.
CREATE TABLE task_event (
    id         INTEGER PRIMARY KEY,
    task_id    INTEGER NOT NULL REFERENCES task (id),
    run_seq    INTEGER,
    at         TEXT    NOT NULL,
    actor      TEXT    NOT NULL,
    from_phase TEXT,
    to_phase   TEXT    NOT NULL,
    reason     TEXT    NOT NULL
);

CREATE INDEX task_event_task ON task_event (task_id);

-- Messages for the team, written in the same transaction as the change they report (ADR 0010).
CREATE TABLE outbox (
    id              INTEGER PRIMARY KEY,
    task_id         INTEGER REFERENCES task (id),
    kind            TEXT    NOT NULL,
    chat_ref        TEXT    NOT NULL,
    reply_to_ref    TEXT,
    payload         TEXT    NOT NULL,
    status          TEXT    NOT NULL CHECK (status IN ('PENDING', 'SENT', 'FAILED')),
    attempts        INTEGER NOT NULL DEFAULT 0,
    next_attempt_at TEXT    NOT NULL,
    last_error      TEXT,
    created_at      TEXT    NOT NULL,
    sent_at         TEXT,
    sent_ref        TEXT
);

CREATE INDEX outbox_due ON outbox (status, next_attempt_at);

CREATE TABLE kv (
    key   TEXT PRIMARY KEY,
    value TEXT NOT NULL
);
