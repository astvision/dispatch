-- When the task's pull request was merged by its requester's tap, or seen merged when they tapped: later changes to it
-- become a new task instead of a follow-up on a branch that is gone.
ALTER TABLE task ADD COLUMN merged_at TEXT;
