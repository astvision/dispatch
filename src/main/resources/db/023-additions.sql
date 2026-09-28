-- An addition: more instructions someone wrote in a linked group as a reply to the message a task came from. It is offered
-- to the task's requester privately as a button and applied once, when they tap it. It is kept by that message's
-- origin_ref, not a task id, so an addition to a draft not yet given as a task can be applied once it is one.
CREATE TABLE addition (
    id         INTEGER PRIMARY KEY,
    origin_ref TEXT NOT NULL,
    member_ref TEXT NOT NULL,
    author     TEXT NOT NULL,
    text       TEXT NOT NULL,
    created_at TEXT NOT NULL,
    used_at    TEXT
);
