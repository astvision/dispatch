-- M3f: execution runs continue their own agent session, started from the approved plan (ADR 0017).
ALTER TABLE task ADD COLUMN build_session_id TEXT;
