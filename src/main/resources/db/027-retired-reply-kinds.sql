-- ADR 0031: a task command's refusal is one REFUSED reply, worded where it was refused, and giving a task refuses in its
-- words too. Replies of the old kinds still waiting to be sent can no longer be rendered.
UPDATE outbox SET status = 'FAILED', last_error = 'kind ' || kind || ' was removed in schema version 27'
WHERE status = 'PENDING'
  AND kind IN ('CANCEL_REFUSED', 'RETRY_REFUSED', 'FOLLOW_UP_REFUSED', 'CORRECTION_REFUSED', 'UNKNOWN_PROJECT', 'PROJECT_UNAVAILABLE');
