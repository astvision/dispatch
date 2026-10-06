-- The hold a machine's agent is under once its usage limit cut a run short (spec: usage limit, ADR 0040): machine 0 is
-- the bot's own computer (personal mode), any other a worker.id. One row per machine and agent; a new hit replaces it.
CREATE TABLE agent_limit (
    machine   INTEGER NOT NULL,
    agent     TEXT    NOT NULL,
    resets_at TEXT    NOT NULL,
    type      TEXT    NOT NULL,
    PRIMARY KEY (machine, agent)
);
