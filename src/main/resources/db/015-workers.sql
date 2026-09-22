-- W-3: a member's own computers. Only the SHA-256 of a worker key or a pairing code is ever stored, so this file
-- cannot be used to reach anyone's machine.
CREATE TABLE worker (
    id           INTEGER PRIMARY KEY,
    member_ref   TEXT NOT NULL,
    name         TEXT NOT NULL,
    key_sha256   TEXT NOT NULL UNIQUE,
    created_at   TEXT NOT NULL,
    last_seen_at TEXT,
    revoked_at   TEXT
);

CREATE INDEX worker_member ON worker (member_ref, revoked_at);

-- One-time codes from /worker, exchanged for a key within ten minutes.
CREATE TABLE pairing_code (
    code_sha256 TEXT PRIMARY KEY,
    member_ref  TEXT NOT NULL,
    member_name TEXT NOT NULL,
    created_at  TEXT NOT NULL,
    expires_at  TEXT NOT NULL,
    used_at     TEXT
);

-- A task's worktree and agent session live on one computer, so every later run of it goes back to that worker.
ALTER TABLE task ADD COLUMN worker_id INTEGER REFERENCES worker (id);
