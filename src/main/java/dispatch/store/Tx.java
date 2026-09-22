package dispatch.store;

import java.math.BigDecimal;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Types;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** One open transaction. Only valid inside the {@link Database} call that created it. */
public final class Tx {

    private final Connection connection;
    private final List<Runnable> afterCommit = new ArrayList<>();

    Tx(Connection connection) {
        this.connection = connection;
    }

    /** Runs {@code action} once this transaction has committed; dropped if it rolls back. */
    public void afterCommit(Runnable action) {
        afterCommit.add(action);
    }

    List<Runnable> afterCommitActions() {
        return List.copyOf(afterCommit);
    }

    /** "?, ?, ?" for an IN list of {@code count} parameters; callers handle an empty list themselves. */
    static String placeholders(int count) {
        return String.join(", ", java.util.Collections.nCopies(count, "?"));
    }

    public int update(String sql, Object... params) {
        try (PreparedStatement statement = prepare(sql, params)) {
            return statement.executeUpdate();
        } catch (SQLException e) {
            throw new DatabaseException("SQL failed: " + sql, e);
        }
    }

    /** Executes an INSERT and returns the new row id. */
    public long insert(String sql, Object... params) {
        update(sql, params);
        return one("SELECT last_insert_rowid() AS id", row -> row.longValue("id")).orElseThrow();
    }

    /**
     * The single matching row, or empty when there is none. A mapped {@code null} (a nullable column, or an aggregate
     * over zero rows) is a present row whose value is null, not "no row" — this returns {@code Optional.empty()} for
     * both, since the caller only ever wants "is there a value", and {@code Optional} cannot hold a null to tell them
     * apart anyway.
     */
    public <T> Optional<T> one(String sql, RowMapper<T> mapper, Object... params) {
        List<T> rows = list(sql, mapper, params);
        if (rows.size() > 1) {
            throw new IllegalStateException("expected at most one row, got " + rows.size() + ": " + sql);
        }
        return rows.isEmpty() ? Optional.empty() : Optional.ofNullable(rows.get(0));
    }

    public <T> List<T> list(String sql, RowMapper<T> mapper, Object... params) {
        try (PreparedStatement statement = prepare(sql, params); ResultSet resultSet = statement.executeQuery()) {
            List<T> rows = new ArrayList<>();
            Row row = new Row(resultSet);
            while (resultSet.next()) {
                rows.add(mapper.map(row));
            }
            return rows;
        } catch (SQLException e) {
            throw new DatabaseException("SQL failed: " + sql, e);
        }
    }

    private PreparedStatement prepare(String sql, Object... params) throws SQLException {
        PreparedStatement statement = connection.prepareStatement(sql);
        try {
            for (int i = 0; i < params.length; i++) {
                bind(statement, i + 1, params[i]);
            }
            return statement;
        } catch (SQLException | RuntimeException e) {
            statement.close();
            throw e;
        }
    }

    private static void bind(PreparedStatement statement, int index, Object value) throws SQLException {
        switch (value) {
            case null -> statement.setNull(index, Types.NULL);
            case String text -> statement.setString(index, text);
            case Long number -> statement.setLong(index, number);
            case Integer number -> statement.setInt(index, number);
            case Instant instant -> statement.setString(index, Timestamps.format(instant));
            case Enum<?> constant -> statement.setString(index, constant.name());
            case UUID uuid -> statement.setString(index, uuid.toString());
            case BigDecimal decimal -> statement.setString(index, decimal.toPlainString());
            case Path path -> statement.setString(index, path.toString());
            default -> throw new IllegalArgumentException("unsupported SQL parameter type " + value.getClass().getName());
        }
    }
}
