-- CI watch (ADR 0041): the checks of the commit Dispatch last delivered to a task's pull request. One row per watched
-- task: armed PENDING by each delivery that pushed, then settled by the watcher. reason says why a watch gave up
-- (CAP, UNCHANGED, STUCK, CANCELLED) or stopped (MERGED, CLOSED, MOVED, OFF, ENDED). fix_rounds counts the automatic
-- fix runs in a row; a run a member caused starts it afresh.
CREATE TABLE task_ci (
    task_id     INTEGER PRIMARY KEY REFERENCES task (id),
    head_sha    TEXT    NOT NULL,
    state       TEXT    NOT NULL CHECK (state IN ('PENDING', 'PASSED', 'FIXING', 'GAVE_UP', 'NONE', 'STOPPED')),
    reason      TEXT,
    fix_rounds  INTEGER NOT NULL DEFAULT 0,
    checks_json TEXT,
    armed_at    TEXT    NOT NULL,
    checked_at  TEXT
);
