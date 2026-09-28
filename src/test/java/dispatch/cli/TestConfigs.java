package dispatch.cli;

import dispatch.testing.GitFixture;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

/**
 * The smallest valid personal config, as {@code dispatch init} writes one, plus its secrets: for tests that only need
 * Dispatch to load an instance, not to run one (M: several instances on one computer).
 */
final class TestConfigs {

    private TestConfigs() {
    }

    /** Writes {@code yaml} and a {@code .env} beside it with {@code TELEGRAM_BOT_TOKEN=token}, and one {@code project}. */
    static void write(Path yaml, String token, String project) throws IOException {
        String base = yaml.getFileName().toString().replaceFirst("\\.yaml$", "");
        Path stateDir = yaml.getParent().resolve("state-" + base);
        Path clone = yaml.getParent().resolve(project);
        if (!Files.isDirectory(clone.resolve(".git"))) {
            Files.createDirectories(clone);
            GitFixture.sh(clone, "git", "init", "--quiet", "-b", "main");
        }
        String yamlText = new String(TestConfigs.class.getResourceAsStream("/personal.yaml").readAllBytes())
                .replace("STATE_DIR", quoted(stateDir))
                .replace("CLONE", quoted(clone))
                .replace("alm", project);
        Files.createDirectories(yaml.getParent());
        Files.writeString(yaml, yamlText);
        SecretsFile.write(SecretsFile.beside(yaml), Map.of("TELEGRAM_BOT_TOKEN", token));
    }

    private static String quoted(Path path) {
        return path.toString().replace("'", "''");
    }
}
