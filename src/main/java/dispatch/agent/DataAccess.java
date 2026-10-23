package dispatch.agent;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * A run's read-only access to a project's prod Mongo through a pinned MongoDB MCP server (spec: data reports). A plan sees
 * only the data's shape; the report run ZB approved reads it. The tool sets and the disabled lists were probed on
 * mongodb-mcp-server 2.1.2: {@code export} and {@code explain} run pipelines, and {@code find} returns raw values.
 *
 * @param connectionString the URI through the run's tunnel; it travels only in the run's environment, never on a command line
 * @param seen             whether the run's session has seen report data already, so it may not answer directly even
 *                         without a data call of its own
 */
public record DataAccess(Level level, String connectionString, boolean seen) {

    /** The MCP server's name, so its tools are {@code mcp__dispatch_data__<tool>}. */
    public static final String SERVER = "dispatch_data";
    public static final String TOOL_PREFIX = "mcp__" + SERVER + "__";
    /** The run environment variable the server's connection string is expanded from. */
    public static final String URI_VARIABLE = "DISPATCH_DATA_URI";

    public enum Level {
        SCHEMA(Set.of("list-databases", "list-collections", "collection-indexes", "collection-schema", "collection-storage-size",
                "count", "db-stats"),
                "aggregate,aggregate-db,export,explain,find,connect,disconnect,list-connections,mongodb-logs,"
                        + "atlas-local-connect-deployment,atlas-local-list-deployments,search-knowledge,list-knowledge-sources"),
        FULL(Set.of("list-databases", "list-collections", "collection-indexes", "collection-schema", "collection-storage-size",
                "count", "db-stats", "find", "aggregate", "explain"),
                "aggregate-db,export,connect,disconnect,list-connections,mongodb-logs,"
                        + "atlas-local-connect-deployment,atlas-local-list-deployments,search-knowledge,list-knowledge-sources");

        private final Set<String> allowedTools;
        private final String disabledTools;

        Level(Set<String> allowedTools, String disabledTools) {
            this.allowedTools = allowedTools;
            this.disabledTools = disabledTools;
        }

        /** The server's tools a run at this level may see, without {@link #TOOL_PREFIX}; any other one stops the run. */
        public Set<String> allowedTools() {
            return allowedTools;
        }

        /** {@code MDB_MCP_DISABLED_TOOLS}: what leaves exactly {@link #allowedTools()} once the server is read-only. */
        public String disabledTools() {
            return disabledTools;
        }

        /** The server's {@code env}: no secret, the connection string is a reference to {@link #URI_VARIABLE}. */
        public Map<String, String> serverEnvironment() {
            Map<String, String> env = new LinkedHashMap<>();
            env.put("MDB_MCP_CONNECTION_STRING", "${" + URI_VARIABLE + "}");
            env.put("MDB_MCP_READ_ONLY", "true");
            env.put("MDB_MCP_DISABLED_TOOLS", disabledTools);
            env.put("MDB_MCP_MAX_TIME_MS", "60000");
            env.put("MDB_MCP_TELEMETRY", "disabled");
            if (this == FULL) {
                // The server's default of 100 rows is too few for a report: daily counts over 3 years are about 1,100.
                env.put("MDB_MCP_MAX_DOCUMENTS_PER_QUERY", "5000");
            }
            return env;
        }
    }

    public DataAccess {
        Objects.requireNonNull(level, "level");
        Objects.requireNonNull(connectionString, "connectionString");
    }

    /** For a session that has seen no report data. */
    public DataAccess(Level level, String connectionString) {
        this(level, connectionString, false);
    }

    /** Masked: a request is printed in logs and errors, and the URI holds the read-only user's password. */
    @Override
    public String toString() {
        return "DataAccess[level=" + level + ", connectionString=***, seen=" + seen + "]";
    }
}
