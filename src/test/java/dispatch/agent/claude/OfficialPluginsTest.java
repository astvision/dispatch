package dispatch.agent.claude;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dispatch.testing.OwnerPluginsFixture;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class OfficialPluginsTest {

    @TempDir
    Path home;

    private Path marketplace;

    @BeforeEach
    void catalog() throws IOException {
        marketplace = Files.createDirectories(OfficialPlugins.marketplace(home));
        OwnerPluginsFixture.plugin(marketplace.resolve("plugins/frontend-design"), null);
        OwnerPluginsFixture.plugin(marketplace.resolve("external_plugins/playwright"), "{\"playwright\": {\"command\": \"npx\"}}");
        Files.createDirectories(marketplace.resolve(".claude-plugin"));
        Files.writeString(marketplace.resolve(".claude-plugin/marketplace.json"), """
                {"plugins": [
                  {"name": "frontend-design", "source": "./plugins/frontend-design"},
                  {"name": "playwright", "source": "./external_plugins/playwright"},
                  {"name": "context7", "source": {"source": "url", "url": "https://example.com/context7.git"}},
                  {"name": "escape", "source": "../../outside"},
                  {"name": "absolute", "source": "/etc"},
                  {"name": "gone", "source": "./plugins/gone"},
                  {"name": "unpathable", "source": "./plugins/a\\u0000b"}
                ]}""");
    }

    @Test
    void picksHostedInTheMarketplaceResolveToTheirDirectoriesInOrder() {
        Map<String, Path> found = OfficialPlugins.find(home, List.of("playwright", "frontend-design"));

        assertEquals(List.of("playwright", "frontend-design"), List.copyOf(found.keySet()));
        assertEquals(marketplace.resolve("external_plugins/playwright").toAbsolutePath().normalize(), found.get("playwright"));
    }

    @Test
    void aPickThisMachineCannotLoadAsADirectoryInTheMarketplaceIsSkipped() {
        Map<String, Path> found = OfficialPlugins.find(home,
                List.of("context7", "escape", "absolute", "gone", "unpathable", "unknown", "frontend-design"));

        assertEquals(List.of("frontend-design"), List.copyOf(found.keySet()), "a fetched source, an escape, a missing dir");
    }

    @Test
    void noMarketplaceCopyOrNoHomeSkipsEveryPick() throws IOException {
        Files.delete(marketplace.resolve(".claude-plugin/marketplace.json"));

        assertTrue(OfficialPlugins.find(home, List.of("frontend-design")).isEmpty());
        assertTrue(OfficialPlugins.find(null, List.of("frontend-design")).isEmpty());
    }
}
