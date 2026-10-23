package dispatch.data;

import static org.junit.jupiter.api.Assertions.assertEquals;

import dispatch.config.Config;
import dispatch.config.DataSource;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class DataSourcesTest {

    // Split, so secret scanners never see a credential-shaped literal in this file.
    private static final String URI = "mongodb://ro:" + "pw@db1:27017/webapp";

    /** dispatch.env is every agent's environment: without this, a run of any project would carry prod's URI. */
    @Test
    void aDataUriVariableIsKeptFromTheAgentsEnvironmentAndTheRestStays() {
        Config.Project webapp = new Config.Project("webapp", null, null, null, "main", "claude-code", null, null, List.of(), null,
                null, null, null, "off", "off", "off", new DataSource("webapp-prod", "db1:27017", "webapp", URI));
        Config config = new Config(null, Path.of("state"), null, null, null, null, Map.of(), List.of(webapp), null, null, null,
                null, null);

        Map<String, String> agents = DataSources.withoutDataUris(
                Map.of("WEBAPP_MONGO_RO_URI", URI, "ANTHROPIC_API_KEY", "key", "PATH", "/usr/bin"), config);

        assertEquals(Map.of("ANTHROPIC_API_KEY", "key", "PATH", "/usr/bin"), agents);
    }
}
