-- The human message a group-given draft was given in reply to (G-1b, G-1c): someone's message another member turned into
-- a task by replying with a mention. A reply to that message is an addition to the task too, as one to the task's own is.
ALTER TABLE draft ADD COLUMN source_ref TEXT;
CREATE INDEX draft_source ON draft (source_ref);
