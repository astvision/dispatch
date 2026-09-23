-- W-3: "this run's agent session started" is its own fact. pid stays for an agent this machine started, so startup
-- recovery kills exactly its own orphans; a remote worker's run has none and its own startup cleans up after it.
ALTER TABLE run ADD COLUMN agent_started_at TEXT;
UPDATE run SET agent_started_at = pid_start WHERE pid IS NOT NULL;
