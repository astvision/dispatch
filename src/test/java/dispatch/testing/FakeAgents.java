package dispatch.testing;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;

/** Installs src/test/resources/fake-{cli}.sh as an executable {cli}, e.g. "codex" or "gemini", for agent tests. */
public final class FakeAgents {

    private FakeAgents() {
    }

    public static Path install(Path dir, String cli) throws IOException {
        Path script = dir.resolve(cli);
        try (InputStream in = FakeAgents.class.getResourceAsStream("/fake-" + cli + ".sh")) {
            Files.copy(in, script);
        }
        Files.setPosixFilePermissions(script, PosixFilePermissions.fromString("rwx------"));
        return script;
    }
}
