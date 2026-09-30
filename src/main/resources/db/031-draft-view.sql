-- The lighter draft prompt: the priority chosen on it (NULL is LOW, the default) and whether it shows its detail view,
-- kept so a redraw, such as one after a restart, shows the same view; and how many replies to it added context.
ALTER TABLE draft ADD COLUMN priority TEXT CHECK (priority IN ('URGENT', 'NORMAL', 'LOW'));
ALTER TABLE draft ADD COLUMN detail INTEGER NOT NULL DEFAULT 0;
ALTER TABLE draft ADD COLUMN additions INTEGER NOT NULL DEFAULT 0;
-- An open draft still without a project stays on the detail view, the only one that asks for a project.
UPDATE draft SET detail = 1 WHERE status = 'OPEN' AND project IS NULL;
