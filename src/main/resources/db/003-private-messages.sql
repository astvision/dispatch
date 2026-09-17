-- M3: task details go to the requester privately, falling back to the group (ADR 0011).

-- Where a message goes if its private chat refuses it; fell_back marks a message that went there.
ALTER TABLE outbox ADD COLUMN fallback_chat_ref TEXT;
ALTER TABLE outbox ADD COLUMN fallback_reply_to_ref TEXT;
ALTER TABLE outbox ADD COLUMN fell_back INTEGER NOT NULL DEFAULT 0 CHECK (fell_back IN (0, 1));

-- /tasks became /status. A task list still waiting to be sent can no longer be rendered.
UPDATE outbox SET status = 'FAILED', last_error = 'kind TASK_LIST was removed in schema version 3'
WHERE kind = 'TASK_LIST' AND status = 'PENDING';
