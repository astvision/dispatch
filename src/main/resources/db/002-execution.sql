-- M2: delivery and corrections.

-- The task's draft pull request, set by its first delivery.
ALTER TABLE task ADD COLUMN pr_url TEXT;

-- Display name of whoever queued the run (the approver of an execution run), for commit trailers. NULL before v2.
ALTER TABLE run ADD COLUMN requested_by_name TEXT;

-- A reply to a bot message is matched to the task through the message it replies to.
CREATE UNIQUE INDEX outbox_sent_ref ON outbox (sent_ref);
