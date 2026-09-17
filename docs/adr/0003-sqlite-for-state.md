# SQLite for task and run state

Dispatch stores its state in one SQLite file (WAL mode, sqlite-jdbc) with plain `.sql` migrations tracked by `PRAGMA user_version`, not MongoDB, even though MongoDB is our usual database. The data is small with a single writer, and an embedded file means accepting a task never depends on a separate database server being up. State changes are conditional updates (`... WHERE id = ? AND status = ?`), so a transition that lost a race affects zero rows and is logged instead of silently overwriting.
