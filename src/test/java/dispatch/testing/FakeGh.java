package dispatch.testing;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;

/** Installs src/test/resources/fake-gh.sh as an executable "gh"; it records its call in fake-gh.args in its workdir. */
public final class FakeGh {

    public static final String PR_URL = "https://github.com/acme/autoland-management/pull/7";

    private FakeGh() {
    }

    public static Path install(Path dir) throws IOException {
        Path script = Files.createDirectories(dir).resolve("gh");
        try (InputStream in = FakeGh.class.getResourceAsStream("/fake-gh.sh")) {
            Files.copy(in, script);
        }
        Files.setPosixFilePermissions(script, PosixFilePermissions.fromString("rwx------"));
        return script;
    }
}
