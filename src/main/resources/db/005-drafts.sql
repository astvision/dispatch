-- M3b: tasks are given in a private chat. A message waits here for its project and priority (ADR 0012).
CREATE TABLE draft (
    id             INTEGER PRIMARY KEY,
    requester_ref  TEXT NOT NULL,
    requester_name TEXT NOT NULL,
    chat_ref       TEXT NOT NULL,
    origin_ref     TEXT NOT NULL UNIQUE,
    description    TEXT NOT NULL,
    project        TEXT,
    status         TEXT NOT NULL CHECK (status IN ('OPEN', 'CREATED', 'EXPIRED')),
    task_id        INTEGER REFERENCES task (id),
    created_at     TEXT NOT NULL,
    updated_at     TEXT NOT NULL
);

CREATE INDEX draft_open ON draft (status, created_at);

-- /task is now given privately; the group's pointer to do it there is gone.
UPDATE outbox SET status = 'FAILED', last_error = 'kind TASK_IN_GROUP_ONLY was removed in schema version 5'
WHERE kind = 'TASK_IN_GROUP_ONLY' AND status = 'PENDING';
