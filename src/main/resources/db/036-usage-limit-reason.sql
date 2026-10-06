-- A run cut short by Claude's usage limit ends with reason USAGE_LIMIT, not AGENT (spec: usage limit, 2026-10-06).
-- SQLite cannot change a CHECK constraint, so task and run are rebuilt, as 021 and 033 did; Database.apply runs this
-- with foreign keys off, so the tables that refer to task and run keep referring to the rebuilt ones by name.

CREATE TABLE task_v36 (
    id               INTEGER PRIMARY KEY,
    project          TEXT NOT NULL,
    title            TEXT NOT NULL,
    description      TEXT NOT NULL,
    phase            TEXT NOT NULL CHECK (phase IN ('PLANNING', 'AWAITING_APPROVAL', 'EXECUTING', 'COMPLETED', 'FAILED', 'REJECTED', 'CANCELLED')),
    requester_ref    TEXT NOT NULL,
    requester_name   TEXT NOT NULL,
    origin_ref       TEXT NOT NULL UNIQUE,
    chat_ref         TEXT NOT NULL,
    session_id       TEXT NOT NULL,
    base_branch      TEXT NOT NULL,
    base_sha         TEXT,
    worktree         TEXT,
    plan_json        TEXT,
    failure_reason   TEXT CHECK (failure_reason IN ('SETUP', 'AGENT', 'TIMEOUT', 'BUDGET', 'INTERRUPTED', 'DELIVERY', 'INTERNAL', 'USAGE_LIMIT')),
    failure_detail   TEXT,
    created_at       TEXT NOT NULL,
    started_at       TEXT,
    completed_at     TEXT,
    updated_at       TEXT NOT NULL,
    pr_url           TEXT,
    priority         TEXT NOT NULL DEFAULT 'NORMAL' CHECK (priority IN ('URGENT', 'NORMAL', 'LOW')),
    topic_ref        TEXT,
    build_session_id TEXT,
    worker_id        INTEGER REFERENCES worker (id),
    blocked_reason   TEXT,
    merged_at        TEXT,
    head_sha         TEXT
);

INSERT INTO task_v36 (id, project, title, description, phase, requester_ref, requester_name, origin_ref, chat_ref, session_id,
                      base_branch, base_sha, worktree, plan_json, failure_reason, failure_detail, created_at, started_at,
                      completed_at, updated_at, pr_url, priority, topic_ref, build_session_id, worker_id, blocked_reason,
                      merged_at, head_sha)
SELECT id, project, title, description, phase, requester_ref, requester_name, origin_ref, chat_ref, session_id,
       base_branch, base_sha, worktree, plan_json, failure_reason, failure_detail, created_at, started_at,
       completed_at, updated_at, pr_url, priority, topic_ref, build_session_id, worker_id, blocked_reason,
       merged_at, head_sha
FROM task;

DROP TABLE task;

ALTER TABLE task_v36 RENAME TO task;

CREATE INDEX task_phase ON task (phase);
CREATE INDEX task_topic ON task (requester_ref, topic_ref);
CREATE INDEX task_worker ON task (worker_id);

CREATE TABLE run_v36 (
    task_id           INTEGER NOT NULL REFERENCES task (id),
    seq               INTEGER NOT NULL,
    kind              TEXT    NOT NULL CHECK (kind IN ('PLAN', 'EXECUTE', 'DELIVER')),
    status            TEXT    NOT NULL CHECK (status IN ('QUEUED', 'RUNNING', 'SUCCEEDED', 'FAILED', 'CANCELLED')),
    instruction       TEXT    NOT NULL,
    requested_by      TEXT    NOT NULL,
    pid               INTEGER,
    pid_start         TEXT,
    queued_at         TEXT    NOT NULL,
    started_at        TEXT,
    finished_at       TEXT,
    exit_code         INTEGER,
    failure_reason    TEXT CHECK (failure_reason IN ('SETUP', 'AGENT', 'TIMEOUT', 'BUDGET', 'INTERRUPTED', 'DELIVERY', 'INTERNAL', 'USAGE_LIMIT')),
    error_detail      TEXT,
    cost_usd          TEXT,
    turns             INTEGER,
    output            TEXT,
    denials           TEXT,
    requested_by_name TEXT,
    model             TEXT,
    cause             TEXT,
    agent_started_at  TEXT,
    sandbox           TEXT,
    PRIMARY KEY (task_id, seq)
);

INSERT INTO run_v36 (task_id, seq, kind, status, instruction, requested_by, pid, pid_start, queued_at, started_at, finished_at,
                     exit_code, failure_reason, error_detail, cost_usd, turns, output, denials, requested_by_name, model, cause,
                     agent_started_at, sandbox)
SELECT task_id, seq, kind, status, instruction, requested_by, pid, pid_start, queued_at, started_at, finished_at,
       exit_code, failure_reason, error_detail, cost_usd, turns, output, denials, requested_by_name, model, cause,
       agent_started_at, sandbox
FROM run;

DROP TABLE run;

ALTER TABLE run_v36 RENAME TO run;

CREATE INDEX run_status ON run (status, queued_at);
