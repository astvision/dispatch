-- The lighter draft prompt: the priority chosen on it (NULL is LOW, the default) and whether it shows its detail view,
-- kept so a redraw, such as one after a restart, shows the same view.
ALTER TABLE draft ADD COLUMN priority TEXT CHECK (priority IN ('URGENT', 'NORMAL', 'LOW'));
ALTER TABLE draft ADD COLUMN detail INTEGER NOT NULL DEFAULT 0;
