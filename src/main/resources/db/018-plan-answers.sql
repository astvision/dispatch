-- G-1d: answers to a plan's open questions, given one by one with buttons or as replies. Once every question of the plan
-- is answered they go to the agent together as one correction. The question and force-reply prompt messages are found
-- through their outbox rows' sent_ref; message_ref is the question message redrawn with the answer.
CREATE TABLE plan_answer (
    task_id        INTEGER NOT NULL REFERENCES task (id),
    plan_seq       INTEGER NOT NULL,
    question_index INTEGER NOT NULL,
    answer         TEXT    NOT NULL,
    answered_by    TEXT    NOT NULL,
    answered_at    TEXT    NOT NULL,
    message_ref    TEXT,
    PRIMARY KEY (task_id, plan_seq, question_index)
);
