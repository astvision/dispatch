-- ADR 0031: a redraw may name its original's outbox row instead of its Telegram message, so an answer given before the
-- question reached the chat redraws it once it is sent, instead of the question being sent a second time.
ALTER TABLE outbox ADD COLUMN edit_of INTEGER REFERENCES outbox (id);
