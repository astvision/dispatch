-- ADR 0039: the worker protocol a computer said it speaks on its last poll; 0 for a worker from before the protocol,
-- NULL until it polls a team machine that asks.
ALTER TABLE worker ADD COLUMN protocol INTEGER;
