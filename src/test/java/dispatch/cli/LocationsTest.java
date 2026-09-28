package dispatch.cli;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.nio.file.Path;
import java.util.List;
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

    @Test
    void aNamedInstanceLivesBesideTheDefaultOne() {
        Locations linux = Locations.of("Linux", Map.of(), HOME).forInstance("team");
        Locations windows = Locations.of("Windows 11", Map.of("APPDATA", "roaming", "LOCALAPPDATA", "local"), HOME).forInstance("team");

        assertEquals(HOME.resolve(".config/dispatch/team.yaml"), linux.configFile());
        assertEquals(HOME.resolve(".local/state/dispatch-team"), linux.stateDir());
        assertEquals(Path.of("roaming", "Dispatch", "team.yaml"), windows.configFile());
        assertEquals(Path.of("local", "Dispatch-team"), windows.stateDir());
    }

    @Test
    void noNameIsTheDefaultInstance() {
        Locations defaults = Locations.of("Linux", Map.of(), HOME);

        assertSame(defaults, defaults.forInstance(null));
        assertNull(defaults.instanceOf(defaults.configFile()));
        assertEquals("team", defaults.instanceOf(HOME.resolve(".config/dispatch/team.yaml")));
        assertNull(defaults.instanceOf(Path.of("/etc/dispatch/backend.yaml")), "the server layout is not an instance here");
        assertNull(defaults.instanceOf(HOME.resolve(".config/dispatch/worker.yaml")));
    }

    @Test
    void namesAreShortLowercaseAndNotReserved() {
        assertEquals("team-2", Locations.validName("team-2"));
        for (String bad : List.of("", "Team", "2team", "team_x", "dispatch", "worker", "a".repeat(33), "../x")) {
            assertThrows(CliException.class, () -> Locations.validName(bad), bad);
        }
    }
}
