-- M3g: photos and documents sent with a task. They belong to its draft until the task is given, then to the task.
CREATE TABLE attachment (
    id        INTEGER PRIMARY KEY,
    draft_id  INTEGER REFERENCES draft (id),
    task_id   INTEGER REFERENCES task (id),
    file_ref  TEXT NOT NULL,
    name      TEXT NOT NULL,
    size      INTEGER
);
CREATE INDEX attachment_draft ON attachment (draft_id);
CREATE INDEX attachment_task ON attachment (task_id);
