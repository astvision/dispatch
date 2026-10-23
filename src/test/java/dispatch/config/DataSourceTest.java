package dispatch.config;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

import org.junit.jupiter.api.Test;

class DataSourceTest {

    private static DataSource source(String uri) {
        return new DataSource("webapp-prod", "127.0.0.1:27017", "webapp", uri);
    }

    @Test
    void rewritesHostsAndKeepsCredentialsAndQuery() {
        DataSource source = source("mongodb://ro:p%40ss@" + "db1:27017,db2:27017/webapp?authSource=admin");

        assertEquals("mongodb://ro:p%40ss@127.0.0.1:40001/webapp?authSource=admin&readPreference=secondaryPreferred",
                source.connectionString(40001));
    }

    @Test
    void addsTheDatabaseWhenTheUriHasNoPath() {
        DataSource source = source("mongodb://ro:pw@" + "db1");

        assertEquals("mongodb://ro:pw@127.0.0.1:40001/webapp?readPreference=secondaryPreferred", source.connectionString(40001));
    }

    @Test
    void keepsAGivenReadPreference() {
        DataSource source = source("mongodb://ro:pw@" + "db1/webapp?readPreference=primary");

        assertEquals("mongodb://ro:pw@127.0.0.1:40001/webapp?readPreference=primary", source.connectionString(40001));
    }

    @Test
    void toStringNeverShowsTheUri() {
        assertFalse(source("mongodb://ro:pw@" + "db1/webapp").toString().contains("pw"));
    }
}
