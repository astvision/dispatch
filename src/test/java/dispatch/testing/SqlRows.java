package dispatch.testing;

import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Reads the state file over its own JDBC connection so assertions never reuse production queries. */
public final class SqlRows {

    private SqlRows() {
    }

    /** Every column as text (SQLite converts), null stays null. */
    public static List<Map<String, String>> query(Path dbFile, String sql, Object... params) {
        try (Connection connection = DriverManager.getConnection("jdbc:sqlite:" + dbFile);
             PreparedStatement statement = connection.prepareStatement(sql)) {
            for (int i = 0; i < params.length; i++) {
                statement.setObject(i + 1, params[i]);
            }
            try (ResultSet resultSet = statement.executeQuery()) {
                ResultSetMetaData meta = resultSet.getMetaData();
                List<Map<String, String>> rows = new ArrayList<>();
                while (resultSet.next()) {
                    Map<String, String> row = new LinkedHashMap<>();
                    for (int c = 1; c <= meta.getColumnCount(); c++) {
                        row.put(meta.getColumnLabel(c), resultSet.getString(c));
                    }
                    rows.add(row);
                }
                return rows;
            }
        } catch (SQLException e) {
            throw new IllegalStateException("test query failed: " + sql, e);
        }
    }

    public static Map<String, String> single(Path dbFile, String sql, Object... params) {
        List<Map<String, String>> rows = query(dbFile, sql, params);
        if (rows.size() != 1) {
            throw new AssertionError("expected exactly one row, got " + rows.size() + " for " + sql + ": " + rows);
        }
        return rows.getFirst();
    }
}
