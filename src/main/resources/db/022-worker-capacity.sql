-- How many runs a member's computer takes at once: its worker.yaml's maxConcurrentRuns, reported on each poll.
-- NULL means it never said, which counts as one, the most any computer took before this column.
ALTER TABLE worker ADD COLUMN max_runs INTEGER;
