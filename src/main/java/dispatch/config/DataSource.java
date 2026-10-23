package dispatch.config;

import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * A project's production Mongo, reached for reports through an SSH tunnel (ADR 0043).
 *
 * @param ssh      the host to tunnel through, as ssh knows it
 * @param mongo    host:port of Mongo as that host sees it
 * @param database the database a report reads
 * @param uri      the read-only connection string, resolved from dispatch.env at load; never {@code ${...}} and never logged
 */
public record DataSource(String ssh, String mongo, String database, String uri) {

    /**
     * The URI pointed at the local end of the tunnel: its hosts become 127.0.0.1:localPort, credentials, path and query stay.
     * One forwarded port reaches one member, so the replica set is dropped and the driver connects directly instead of
     * discovering members the tunnel cannot reach: reads go to the host {@code mongo} names, which should be a secondary.
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
        List<String> options = new ArrayList<>();
        for (String option : queryString.split("&")) {
            if (!option.isEmpty() && !hasName(option, "replicaSet")) {
                options.add(option);
            }
        }
        if (options.stream().noneMatch(option -> hasName(option, "directConnection"))) {
            options.add("directConnection=true");
        }
        if (options.stream().noneMatch(option -> hasName(option, "readPreference"))) {
            options.add("readPreference=secondaryPreferred");
        }
        return rewritten + "?" + String.join("&", options);
    }

    /**
     * What the instance's Redactor masks: the URI, and its password as written and decoded, since a shell that echoes the
     * password alone matches no pattern. Empty parts are left out.
     */
    public List<String> secrets() {
        List<String> secrets = new ArrayList<>(List.of(uri));
        int schemeEnd = uri.indexOf("://") + 3;
        int at = uri.lastIndexOf('@');
        int colon = uri.indexOf(':', schemeEnd);
        if (at > schemeEnd && colon > 0 && colon < at) {
            String password = uri.substring(colon + 1, at);
            secrets.add(password);
            secrets.add(URLDecoder.decode(password, StandardCharsets.UTF_8));
        }
        return secrets;
    }

    /** URI option names are case-insensitive. */
    private static boolean hasName(String option, String name) {
        return option.toLowerCase(Locale.ROOT).startsWith(name.toLowerCase(Locale.ROOT) + "=");
    }

    @Override
    public String toString() {
        return "DataSource[ssh=" + ssh + ", mongo=" + mongo + ", database=" + database + ", uri=[redacted]]";
    }
}
