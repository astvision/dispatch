package dispatch.cli;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.nio.file.Path;
import java.util.Map;
import org.junit.jupiter.api.Test;

class LocationsTest {

    private static final Path HOME = Path.of("home", "bold");

    @Test
    void windowsUsesRoamingAppDataForConfigAndLocalAppDataForState() {
        Locations locations = Locations.of("Windows 11", Map.of("APPDATA", "roaming", "LOCALAPPDATA", "local"), HOME);

        assertEquals(Path.of("roaming", "Dispatch", "dispatch.yaml"), locations.configFile());
        assertEquals(Path.of("local", "Dispatch"), locations.stateDir());
    }

    @Test
    void macOsAndLinuxUseTheXdgDirectoriesLikeGh() {
        Locations mac = Locations.of("Mac OS X", Map.of(), HOME);
        Locations linux = Locations.of("Linux", Map.of("XDG_CONFIG_HOME", "xdg-config", "XDG_STATE_HOME", " "), HOME);

        assertEquals(HOME.resolve(".config/dispatch/dispatch.yaml"), mac.configFile());
        assertEquals(HOME.resolve(".local/state/dispatch"), mac.stateDir());
        assertEquals(Path.of("xdg-config", "dispatch", "dispatch.yaml"), linux.configFile());
        assertEquals(HOME.resolve(".local/state/dispatch"), linux.stateDir(), "a blank variable counts as unset");
    }
}
