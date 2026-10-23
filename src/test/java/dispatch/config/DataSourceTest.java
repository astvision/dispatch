package dispatch.config;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

import java.util.List;
import org.junit.jupiter.api.Test;

class DataSourceTest {

    private static DataSource source(String uri) {
        return new DataSource("webapp-prod", "127.0.0.1:27017", "webapp", uri);
    }

    @Test
    void rewritesHostsAndKeepsCredentialsAndQuery() {
        DataSource source = source("mongodb://ro:" + "p%40ss@db1:27017,db2:27017/webapp?authSource=admin");

        assertEquals("mongodb://ro:" + "p%40ss@127.0.0.1:40001/webapp?authSource=admin&directConnection=true"
                + "&readPreference=secondaryPreferred", source.connectionString(40001));
    }

    @Test
    void addsTheDatabaseWhenTheUriHasNoPath() {
        DataSource source = source("mongodb://ro:" + "pw@db1");

        assertEquals("mongodb://ro:" + "pw@127.0.0.1:40001/webapp?directConnection=true&readPreference=secondaryPreferred",
                source.connectionString(40001));
    }

    @Test
    void keepsAGivenReadPreference() {
        DataSource source = source("mongodb://ro:" + "pw@db1/webapp?readPreference=primary");

        assertEquals("mongodb://ro:" + "pw@127.0.0.1:40001/webapp?readPreference=primary&directConnection=true",
                source.connectionString(40001));
    }

    /** One forwarded port reaches one member: discovering the set would dial hosts the tunnel cannot reach. */
    @Test
    void dropsTheReplicaSetAndConnectsDirectly() {
        DataSource source = source("mongodb://ro:" + "pw@db1:27017,db2:27017/webapp?replicaSet=rs0&authSource=admin");

        assertEquals("mongodb://ro:" + "pw@127.0.0.1:40001/webapp?authSource=admin&directConnection=true"
                + "&readPreference=secondaryPreferred", source.connectionString(40001));
    }

    @Test
    void alwaysConnectsDirectly() {
        DataSource source = source("mongodb://ro:" + "pw@db1/webapp?directConnection=false");

        assertEquals("mongodb://ro:" + "pw@127.0.0.1:40001/webapp?directConnection=true&readPreference=secondaryPreferred",
                source.connectionString(40001));
    }

    @Test
    void masksTheUriAndItsPasswordAsWrittenAndDecoded() {
        DataSource source = source("mongodb://ro:" + "p%40ss@db1/webapp");

        assertEquals(List.of("mongodb://ro:" + "p%40ss@db1/webapp", "p%40ss", "p@ss"), source.secrets());
    }

    /** A raw '%' is an invalid URI, yet startup reads the secrets before dispatch check can say so. */
    @Test
    void aPasswordThatDoesNotDecodeIsMaskedAsWritten() {
        DataSource source = source("mongodb://ro:" + "50%off@db1/webapp");

        assertEquals(List.of("mongodb://ro:" + "50%off@db1/webapp", "50%off"), source.secrets());
    }

    @Test
    void toStringNeverShowsTheUri() {
        assertFalse(source("mongodb://ro:" + "pw@db1/webapp").toString().contains("pw"));
    }
}
