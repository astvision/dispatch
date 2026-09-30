-- RM-5: a run paused before its review waits as a step of its own (PAUSE), so the monitor shows it with its time and a
-- team worker reports it as any other step. SQLite cannot change a CHECK constraint, so the table is rebuilt, as 021 did.

CREATE TABLE run_step_v33 (
    task_id    INTEGER NOT NULL,
    seq        INTEGER NOT NULL,
    n          INTEGER NOT NULL,
    kind       TEXT    NOT NULL CHECK (kind IN ('PLAN', 'IMPLEMENT', 'TEST', 'FIX', 'PAUSE', 'REVIEW', 'DELIVER')),
    round      INTEGER NOT NULL,
    started_at TEXT    NOT NULL,
    ended_at   TEXT,
    outcome    TEXT CHECK (outcome IN ('DONE', 'PASSED', 'FAILED', 'OK', 'FINDINGS', 'SKIPPED', 'STOPPED')),
    detail     TEXT,
    PRIMARY KEY (task_id, seq, n),
    FOREIGN KEY (task_id, seq) REFERENCES run (task_id, seq)
);

INSERT INTO run_step_v33 (task_id, seq, n, kind, round, started_at, ended_at, outcome, detail)
SELECT task_id, seq, n, kind, round, started_at, ended_at, outcome, detail FROM run_step;

DROP TABLE run_step;

ALTER TABLE run_step_v33 RENAME TO run_step;
