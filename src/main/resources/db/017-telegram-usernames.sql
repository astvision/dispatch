-- G-1c: members' Telegram usernames, so an @username in a linked group resolves to the member it names. Telegram gives
-- a user id only with a text_mention; a plain @username has to be looked up here. Usernames move between accounts, so
-- one username has at most one row.
CREATE TABLE telegram_user (
    user_id    INTEGER PRIMARY KEY,
    username   TEXT,
    updated_at TEXT
);

CREATE UNIQUE INDEX telegram_user_username ON telegram_user (lower(username));
