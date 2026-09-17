-- M3c: an outbox message can redraw a message sent earlier instead of sending a new one (ADR 0013).
ALTER TABLE outbox ADD COLUMN edit_ref TEXT;
