package dispatch.store;

import java.math.BigDecimal;
import java.nio.file.Path;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.UUID;

/** Typed, null-explicit access to the current row of a query. */
public final class Row {

    private final ResultSet resultSet;

    Row(ResultSet resultSet) {
        this.resultSet = resultSet;
    }

    public String string(String column) throws SQLException {
        return resultSet.getString(column);
    }

    public long longValue(String column) throws SQLException {
        long value = resultSet.getLong(column);
        if (resultSet.wasNull()) {
            throw new SQLException("column " + column + " is null");
        }
        return value;
    }

    public Long longOrNull(String column) throws SQLException {
        long value = resultSet.getLong(column);
        return resultSet.wasNull() ? null : value;
    }

    public int intValue(String column) throws SQLException {
        int value = resultSet.getInt(column);
        if (resultSet.wasNull()) {
            throw new SQLException("column " + column + " is null");
        }
        return value;
    }

    public Integer intOrNull(String column) throws SQLException {
        int value = resultSet.getInt(column);
        return resultSet.wasNull() ? null : value;
    }

    public Instant instant(String column) throws SQLException {
        String value = resultSet.getString(column);
        return value == null ? null : Instant.parse(value);
    }

    public <E extends Enum<E>> E enumValue(String column, Class<E> type) throws SQLException {
        String value = resultSet.getString(column);
        return value == null ? null : Enum.valueOf(type, value);
    }

    public UUID uuid(String column) throws SQLException {
        String value = resultSet.getString(column);
        return value == null ? null : UUID.fromString(value);
    }

    public BigDecimal decimal(String column) throws SQLException {
        String value = resultSet.getString(column);
        return value == null ? null : new BigDecimal(value);
    }

    public Path path(String column) throws SQLException {
        String value = resultSet.getString(column);
        return value == null ? null : Path.of(value);
    }
}
