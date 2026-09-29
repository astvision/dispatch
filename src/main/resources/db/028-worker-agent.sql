-- ADR 0026: what a member's computer says about each agent other than Claude Code (codex, gemini), so a project's run
-- is held only by the agent it runs on. Claude Code's check stays in worker.claude_ok, which every worker has reported
-- since T-1 and which older team machines read. An agent with no row holds nothing, as a project with no row does not.
CREATE TABLE worker_agent (
    worker_id INTEGER NOT NULL REFERENCES worker (id),
    agent     TEXT    NOT NULL,
    ok        INTEGER NOT NULL,
    detail    TEXT,
    PRIMARY KEY (worker_id, agent)
);
