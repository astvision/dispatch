package dispatch.store;

import java.sql.SQLException;

@FunctionalInterface
public interface RowMapper<T> {

    T map(Row row) throws SQLException;
}
