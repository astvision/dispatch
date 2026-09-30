-- ADR 0032: the commit Dispatch last left the task's branch at: its base once the first planning run made the worktree,
-- then each delivery's commit. An execution or delivery run refuses a branch found anywhere else, since every agent in
-- the clone can move its refs. NULL checks nothing: a task from before this, or one a worker from before it delivered.
ALTER TABLE task ADD COLUMN head_sha TEXT;
