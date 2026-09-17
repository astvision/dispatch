package dispatch.cli;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.DisabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;

class ExecutablesTest {

    @TempDir
    Path dir;

    @Test
    void firstMatchOnThePathWins() throws IOException {
        Path first = Files.createDirectories(dir.resolve("first"));
        Path second = Files.createDirectories(dir.resolve("second"));
        String os = System.getProperty("os.name");
        Path claude = executable(second.resolve(os.startsWith("Windows") ? "claude.exe" : "claude"));

        Optional<Path> found = Executables.find("claude", os,
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

    @Test
    @DisabledOnOs(value = OS.WINDOWS, disabledReason = "creating symbolic links needs extra rights on Windows")
    void theServiceRunsJavaByTheNameOnThePathThatSurvivesAJavaUpdate() throws IOException {
        // Homebrew, Fedora: the running binary sits in a versioned directory, and PATH holds a link to it.
        Path running = executable(Files.createDirectories(dir.resolve("Cellar/openjdk/25.0.1/bin")).resolve("java"));
        Path bin = Files.createDirectories(dir.resolve("bin"));
        Path linked = Files.createSymbolicLink(bin.resolve("java"), running);

        assertEquals(linked, Executables.serviceJava(running, "Mac OS X", Map.of("PATH", bin.toString()), dir.resolve("home")));
    }

    @Test
    @DisabledOnOs(value = OS.WINDOWS, disabledReason = "creating symbolic links needs extra rights on Windows")
    void javaHomeComesFirstAsInTheLauncher() throws IOException {
        Path running = executable(Files.createDirectories(dir.resolve("jvm/java-25-openjdk-25.0.1.8/bin")).resolve("java"));
        Path javaHome = Files.createSymbolicLink(dir.resolve("jvm/java-25-openjdk"), running.getParent().getParent());
        Path bin = Files.createDirectories(dir.resolve("bin"));
        Files.createSymbolicLink(bin.resolve("java"), running);

        assertEquals(javaHome.resolve("bin/java"), Executables.serviceJava(running, "Linux",
                Map.of("JAVA_HOME", javaHome.toString(), "PATH", bin.toString()), dir.resolve("home")));
    }

    @Test
    void anotherJavaIsNeverTakenForTheRunningOne() throws IOException {
        String os = System.getProperty("os.name");
        String name = os.startsWith("Windows") ? "java.exe" : "java";
        Path running = executable(Files.createDirectories(dir.resolve("jdk-25/bin")).resolve(name));
        Path bin = Files.createDirectories(dir.resolve("bin"));
        executable(bin.resolve(name));
        Path home = dir.resolve("home");

        assertEquals(running, Executables.serviceJava(running, os, Map.of("PATH", bin.toString()), home), "an older Java on PATH");
        assertEquals(running, Executables.serviceJava(running, os, Map.of("JAVA_HOME", dir.resolve("removed").toString()), home));
        assertEquals(running, Executables.serviceJava(running, os, Map.of("JAVA_HOME", "bad\u0000home"), home), "a malformed JAVA_HOME");
    }

    private static Path executable(Path file) throws IOException {
        Files.createFile(file);
        file.toFile().setExecutable(true);
        return file;
    }
}
