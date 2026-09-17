package dispatch.testing;

import java.io.IOException;
import java.io.InputStream;
import java.net.URISyntaxException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.HashMap;
import java.util.Map;

/** Installs src/test/resources/fake-claude.sh as an executable "claude" for agent and end-to-end tests. */
public final class FakeClaude {

    private FakeClaude() {
    }

    public static Path install(Path dir) throws IOException {
        Path script = dir.resolve("claude");
        try (InputStream in = FakeClaude.class.getResourceAsStream("/fake-claude.sh")) {
            Files.copy(in, script);
        }
        Files.setPosixFilePermissions(script, PosixFilePermissions.fromString("rwx------"));
        return script;
    }

    /** A minimal agent environment: enough to run the script, plus secrets that must not reach the agent. */
    public static Map<String, String> environment() {
        Map<String, String> env = new HashMap<>();
        env.put("PATH", System.getenv("PATH"));
        env.put("HOME", System.getProperty("java.io.tmpdir"));
        env.put("FAKE_CLAUDE_FIXTURES", fixtures().toString());
        env.put("ANTHROPIC_API_KEY", "sk-ant-test");
        env.put("TELEGRAM_BOT_TOKEN", "123:telegram-secret");
        env.put("GH_TOKEN", "github_pat_secret");
        return env;
    }

    private static Path fixtures() {
        try {
            return Path.of(FakeClaude.class.getResource("/fixtures/claude").toURI());
        } catch (URISyntaxException e) {
            throw new IllegalStateException(e);
        }
    }
}
