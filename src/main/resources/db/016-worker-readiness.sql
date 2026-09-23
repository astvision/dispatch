-- T-1: what a member's computer says about itself, so a run is never claimed for a computer that cannot do the work.
-- Stored as columns rather than a JSON blob so the claim query stays a plain join (no SQLite JSON1 dependency).
-- NULL everywhere means "reported nothing", which counts as ready: a team machine upgraded before its workers
-- must not stall them.
ALTER TABLE worker ADD COLUMN claude_ok INTEGER;
ALTER TABLE worker ADD COLUMN claude_detail TEXT;
ALTER TABLE worker ADD COLUMN gh_ok INTEGER;
ALTER TABLE worker ADD COLUMN gh_detail TEXT;
ALTER TABLE worker ADD COLUMN readiness_at TEXT;

-- One row per project the worker reported on; a project with no row is not held.
CREATE TABLE worker_project (
    worker_id INTEGER NOT NULL REFERENCES worker (id),
    project   TEXT    NOT NULL,
    ok        INTEGER NOT NULL,
    detail    TEXT,
    PRIMARY KEY (worker_id, project)
);

-- Why a task's next run is not starting, so it is said once and /status can show it.
ALTER TABLE task ADD COLUMN blocked_reason TEXT;
