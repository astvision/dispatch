-- RM-1: each step of a run as it starts and ends (implement, test, fix, review, deliver, plan), so the Mini App shows a
-- running execution step by step. detail is JSON: a test's tail, a review's findings, or an error.
CREATE TABLE run_step (
    task_id    INTEGER NOT NULL,
    seq        INTEGER NOT NULL,
    n          INTEGER NOT NULL,
    kind       TEXT    NOT NULL CHECK (kind IN ('PLAN', 'IMPLEMENT', 'TEST', 'FIX', 'REVIEW', 'DELIVER')),
    round      INTEGER NOT NULL,
    started_at TEXT    NOT NULL,
    ended_at   TEXT,
    outcome    TEXT CHECK (outcome IN ('DONE', 'PASSED', 'FAILED', 'OK', 'FINDINGS', 'SKIPPED', 'STOPPED')),
    detail     TEXT,
    PRIMARY KEY (task_id, seq, n),
    FOREIGN KEY (task_id, seq) REFERENCES run (task_id, seq)
);
