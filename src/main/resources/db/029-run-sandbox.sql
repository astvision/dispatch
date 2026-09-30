-- Spec 2026-09-30-agent-sandbox-design: the sandbox a run's agent ran in ("bubblewrap" or "none"); NULL for runs
-- from before it and for runs without an agent (DELIVER).
ALTER TABLE run ADD COLUMN sandbox TEXT;
