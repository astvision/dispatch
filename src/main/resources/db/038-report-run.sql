-- A run can be a REPORT: an approved report plan's queries, run read-only, returning the report (spec: data reports,
-- ADR 0043). SQLite cannot change a CHECK constraint, so run (as 036 left it) and run_step (as 033 left it) are rebuilt;
-- Database.apply runs this with foreign keys off, so the tables that refer to run keep referring to the rebuilt one by name.

CREATE TABLE run_v38 (
    task_id           INTEGER NOT NULL REFERENCES task (id),
    seq               INTEGER NOT NULL,
    kind              TEXT    NOT NULL CHECK (kind IN ('PLAN', 'EXECUTE', 'DELIVER', 'REPORT')),
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

INSERT INTO run_v38 (task_id, seq, kind, status, instruction, requested_by, pid, pid_start, queued_at, started_at, finished_at,
                     exit_code, failure_reason, error_detail, cost_usd, turns, output, denials, requested_by_name, model, cause,
                     agent_started_at, sandbox)
SELECT task_id, seq, kind, status, instruction, requested_by, pid, pid_start, queued_at, started_at, finished_at,
       exit_code, failure_reason, error_detail, cost_usd, turns, output, denials, requested_by_name, model, cause,
       agent_started_at, sandbox
FROM run;

DROP TABLE run;

ALTER TABLE run_v38 RENAME TO run;

CREATE INDEX run_status ON run (status, queued_at);

CREATE TABLE run_step_v38 (
    task_id    INTEGER NOT NULL,
    seq        INTEGER NOT NULL,
    n          INTEGER NOT NULL,
    kind       TEXT    NOT NULL CHECK (kind IN ('PLAN', 'IMPLEMENT', 'TEST', 'FIX', 'PAUSE', 'REVIEW', 'DELIVER', 'REPORT')),
    round      INTEGER NOT NULL,
    started_at TEXT    NOT NULL,
    ended_at   TEXT,
    outcome    TEXT CHECK (outcome IN ('DONE', 'PASSED', 'FAILED', 'OK', 'FINDINGS', 'SKIPPED', 'STOPPED')),
    detail     TEXT,
    PRIMARY KEY (task_id, seq, n),
    FOREIGN KEY (task_id, seq) REFERENCES run (task_id, seq)
);

INSERT INTO run_step_v38 (task_id, seq, n, kind, round, started_at, ended_at, outcome, detail)
SELECT task_id, seq, n, kind, round, started_at, ended_at, outcome, detail FROM run_step;

DROP TABLE run_step;

ALTER TABLE run_step_v38 RENAME TO run_step;
