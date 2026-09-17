-- M3b: priority orders the queue (ADR 0012). Tasks from before are normal.
ALTER TABLE task ADD COLUMN priority TEXT NOT NULL DEFAULT 'NORMAL' CHECK (priority IN ('URGENT', 'NORMAL', 'LOW'));
