-- A draft its writer says is not a task, e.g. a question that mentioned them in a group, is discarded. SQLite cannot
-- change a CHECK constraint, so the table is rebuilt with the DISCARDED status, as 008 did for SPLIT. attachment and
-- draft.parent_id refer to draft by name; Database.apply runs this with foreign keys off and checks them before commit.

CREATE TABLE draft_v21 (
    id             INTEGER PRIMARY KEY,
    requester_ref  TEXT NOT NULL,
    requester_name TEXT NOT NULL,
    chat_ref       TEXT NOT NULL,
    origin_ref     TEXT NOT NULL UNIQUE,
    description    TEXT NOT NULL,
    project        TEXT,
    status         TEXT NOT NULL CHECK (status IN ('OPEN', 'CREATED', 'EXPIRED', 'SPLIT', 'DISCARDED')),
    task_id        INTEGER REFERENCES task (id),
    prompt_ref     TEXT,
    split_state    TEXT CHECK (split_state IN ('SPLITTING', 'PROPOSED', 'ONE_TOPIC', 'KEPT', 'FAILED')),
    topics         TEXT,
    parent_id      INTEGER REFERENCES draft (id),
    part           INTEGER,
    created_at     TEXT NOT NULL,
    updated_at     TEXT NOT NULL
);

INSERT INTO draft_v21 SELECT id, requester_ref, requester_name, chat_ref, origin_ref, description, project, status, task_id,
                             prompt_ref, split_state, topics, parent_id, part, created_at, updated_at
FROM draft;

DROP TABLE draft;

ALTER TABLE draft_v21 RENAME TO draft;

CREATE INDEX draft_open ON draft (status, created_at);
