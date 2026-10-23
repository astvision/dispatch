package dispatch.config;

/**
 * A project's production Mongo, reached for reports through an SSH tunnel (ADR 0043).
 *
 * @param ssh      the host to tunnel through, as ssh knows it
 * @param mongo    host:port of Mongo as that host sees it
 * @param database the database a report reads
 * @param uri      the read-only connection string, resolved from dispatch.env at load; never {@code ${...}} and never logged
 */
public record DataSource(String ssh, String mongo, String database, String uri) {

    private static final String READ_PREFERENCE = "readPreference=";

    /**
     * The URI pointed at the local end of the tunnel: its hosts become 127.0.0.1:localPort, credentials, path and query stay,
     * and reads prefer a secondary so a report does not load the primary.
     */
    public String connectionString(int localPort) {
        int schemeEnd = uri.indexOf("://") + 3;
        int query = uri.indexOf('?', schemeEnd);
        String beforeQuery = query < 0 ? uri : uri.substring(0, query);
        String queryString = query < 0 ? "" : uri.substring(query + 1);
        int path = beforeQuery.indexOf('/', schemeEnd);
        String authority = path < 0 ? beforeQuery.substring(schemeEnd) : beforeQuery.substring(schemeEnd, path);
        String pathPart = path < 0 ? "/" + database : beforeQuery.substring(path);
        // The password may hold an encoded '@' (%40), never a raw one, so the last '@' ends the credentials.
        String credentials = authority.substring(0, authority.lastIndexOf('@') + 1);

        String rewritten = uri.substring(0, schemeEnd) + credentials + "127.0.0.1:" + localPort + pathPart;
        if (queryString.contains(READ_PREFERENCE)) {
            return rewritten + "?" + queryString;
        }
        String preference = READ_PREFERENCE + "secondaryPreferred";
        return rewritten + "?" + (queryString.isEmpty() ? preference : queryString + "&" + preference);
    }

    @Override
    public String toString() {
        return "DataSource[ssh=" + ssh + ", mongo=" + mongo + ", database=" + database + ", uri=[redacted]]";
    }
}
