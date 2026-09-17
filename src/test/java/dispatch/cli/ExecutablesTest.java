package dispatch.cli;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ExecutablesTest {

    @TempDir
    Path dir;

    @Test
    void firstMatchOnThePathWins() throws IOException {
        Path first = Files.createDirectories(dir.resolve("first"));
        Path second = Files.createDirectories(dir.resolve("second"));
        Path claude = executable(second.resolve("claude"));

        Optional<Path> found = Executables.find("claude", System.getProperty("os.name"),
                Map.of("PATH", first + java.io.File.pathSeparator + second), dir.resolve("home"));

        assertEquals(Optional.of(claude), found);
    }

    @Test
    void windowsTriesThePathExtensionsInOrder() throws IOException {
        Path bin = Files.createDirectories(dir.resolve("bin"));
        Files.createFile(bin.resolve("claude.cmd"));
        Path exe = Files.createFile(bin.resolve("claude.exe"));

        Optional<Path> found = Executables.find("claude", "Windows 11", Map.of("Path", bin.toString(), "PATHEXT", ".EXE;.CMD"),
                dir.resolve("home"));

        assertEquals(Optional.of(exe), found, "Windows names the variable Path, and .exe comes first here");
    }

    @Test
    void claudesOwnInstallDirectoryIsFoundWhenNotOnThePath() throws IOException {
        Path home = dir.resolve("home");
        Path claude = executable(Files.createDirectories(home.resolve(".local/bin")).resolve("claude"));

        assertEquals(Optional.of(claude), Executables.find("claude", "Mac OS X", Map.of("PATH", dir.resolve("empty").toString()), home));
        assertEquals(Optional.empty(), Executables.find("gh", "Mac OS X", Map.of(), home));
    }

    private static Path executable(Path file) throws IOException {
        Files.createFile(file);
        file.toFile().setExecutable(true);
        return file;
    }
}
