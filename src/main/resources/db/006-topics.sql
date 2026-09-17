-- M3c: each task can have its own topic in the requester's private chat with the bot (ADR 0012).
ALTER TABLE task ADD COLUMN topic_ref TEXT;

CREATE INDEX task_topic ON task (requester_ref, topic_ref);
