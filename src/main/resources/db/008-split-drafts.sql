-- M3c: a draft's message can be split into parts, each a draft of its own (ADR 0013). SQLite cannot change a CHECK
-- constraint, so the table is rebuilt with the SPLIT status and the split's columns.
CREATE TABLE draft_v8 (
    id             INTEGER PRIMARY KEY,
    requester_ref  TEXT NOT NULL,
    requester_name TEXT NOT NULL,
    chat_ref       TEXT NOT NULL,
    origin_ref     TEXT NOT NULL UNIQUE,
    description    TEXT NOT NULL,
    project        TEXT,
    status         TEXT NOT NULL CHECK (status IN ('OPEN', 'CREATED', 'EXPIRED', 'SPLIT')),
    task_id        INTEGER REFERENCES task (id),
    prompt_ref     TEXT,
    split_state    TEXT CHECK (split_state IN ('SPLITTING', 'PROPOSED', 'ONE_TOPIC', 'KEPT', 'FAILED')),
    topics         TEXT,
    parent_id      INTEGER REFERENCES draft (id),
    part           INTEGER,
    created_at     TEXT NOT NULL,
    updated_at     TEXT NOT NULL
);

INSERT INTO draft_v8 (id, requester_ref, requester_name, chat_ref, origin_ref, description, project, status, task_id,
                      created_at, updated_at)
SELECT id, requester_ref, requester_name, chat_ref, origin_ref, description, project, status, task_id, created_at, updated_at
FROM draft;

DROP TABLE draft;

ALTER TABLE draft_v8 RENAME TO draft;

CREATE INDEX draft_open ON draft (status, created_at);
