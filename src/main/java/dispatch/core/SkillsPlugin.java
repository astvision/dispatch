package dispatch.core;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Stream;

/**
 * The dispatch plugin of vetted skills that Claude Code's runs load (spec: agent skills): shipped in the jar, written to
 * {@code <stateDir>/plugins/dispatch} when a machine that runs task agents starts, and bound read-only into each sandbox.
 */
public final class SkillsPlugin {

    static final String RESOURCES = "/skills/dispatch";

    private SkillsPlugin() {
    }

    /** Replaces whatever an older Dispatch left in {@code dir}, so a skill it no longer ships does not linger. */
    public static void install(Path dir) {
        deleteTree(dir);
        BundledFiles.copy(RESOURCES, dir);
    }

    /** The plugin's files, relative to its dir. */
    static List<String> files() {
        return BundledFiles.list(RESOURCES);
    }

    /** Agents never see the state dir, so nothing in it is a link they planted; Files.walk does not follow links anyway. */
    private static void deleteTree(Path dir) {
        if (!Files.exists(dir, LinkOption.NOFOLLOW_LINKS)) {
            return;
        }
        try (Stream<Path> paths = Files.walk(dir)) {
            for (Path path : paths.sorted(Comparator.reverseOrder()).toList()) {
                Files.delete(path);
            }
        } catch (IOException e) {
            throw new UncheckedIOException("cannot replace " + dir + ": " + e, e);
        }
    }
}
