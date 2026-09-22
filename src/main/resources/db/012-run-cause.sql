-- M3g: why a run was queued. A retry or a follow-up gets its own prompt and shows as such in /history.
ALTER TABLE run ADD COLUMN cause TEXT;
UPDATE run SET cause = CASE WHEN kind = 'EXECUTE' THEN 'APPROVAL' WHEN seq = 1 THEN 'TASK' ELSE 'CORRECTION' END;
