package dispatch.core;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

/**
 * The assistant's own directory, {@code <stateDir>/assistant} (A-1): its CLAUDE.md and taskmanager skill, copied from
 * Dispatch's resources at every start so an upgrade brings the new ones, and one {@code dispatch} command per member.
 *
 * <p>That command sets the member's scope itself before it runs {@code dispatch ask}, so a scope the model puts in front
 * of the command is overwritten rather than trusted; the one Bash rule the assistant has is a second guard, not the only one.
 * ponytail: a POSIX shell script; on Windows the assistant answers without task data until someone needs it there.
 */
public final class AssistantHome {

    /** Who {@code dispatch ask} answers for, which projects they see, and the state file; set only by the member's command. */
    public static final String MEMBER_VARIABLE = "DISPATCH_ASK_MEMBER";
    public static final String PROJECTS_VARIABLE = "DISPATCH_ASK_PROJECTS";
    public static final String DATABASE_VARIABLE = "DISPATCH_ASK_DB";
    /** The resources that make up the home, relative to {@code /assistant/} on the classpath. */
    private static final String FILE_LIST = "files.txt";

    private final Path dir;
    private final Path database;
    private final String javaCommand;
    private final String classPath;
    private final String basePath;

    /**
     * @param javaCommand the JVM this process runs on, which the {@code dispatch} command reuses
     * @param classPath   this process's class path: the jar, or the build's classes in tests
     * @param basePath    the agent's PATH, which the member's own {@code dispatch} goes in front of
     */
    public AssistantHome(Path dir, Path database, String javaCommand, String classPath, String basePath) {
        this.dir = dir;
        this.database = database;
        this.javaCommand = javaCommand;
        this.classPath = classPath;
        this.basePath = basePath;
    }

    /** The running JVM's own home: this process's java and class path, and {@code environment}'s PATH. */
    public static AssistantHome of(Path stateDir, Map<String, String> environment) {
        String javaCommand = ProcessHandle.current().info().command().orElse("java");
        // Absolute, since the member's command runs from the home: a relative jar path (java -jar dispatch.jar) would not resolve.
        String classPath = java.util.Arrays.stream(System.getProperty("java.class.path").split(java.io.File.pathSeparator))
                .map(entry -> Path.of(entry).toAbsolutePath().toString()).collect(java.util.stream.Collectors.joining(java.io.File.pathSeparator));
        return new AssistantHome(stateDir.resolve("assistant"), stateDir.resolve("dispatch.db"), javaCommand, classPath,
                environment.getOrDefault("PATH", "/usr/bin:/bin"));
    }

    public Path dir() {
        return dir;
    }

    /**
     * Where each turn's raw output goes: beside the home, not in it, because every member's session may read its home
     * without asking, and these logs hold other members' conversations. ponytail: never pruned; a sweep if they grow.
     */
    public Path logsDir() {
        return dir.resolveSibling("assistant-logs");
    }

    /** Writes CLAUDE.md and the skill over whatever an older Dispatch left there. */
    public void install() {
        for (String file : resource(FILE_LIST).lines().map(String::strip).filter(line -> !line.isEmpty()).toList()) {
            write(dir.resolve(file), resource(file));
        }
    }

    /**
     * The variables for one turn of {@code memberRef}'s conversation: a PATH whose {@code dispatch} answers for them alone.
     * Written on each turn, so the projects they see follow the config.
     */
    public Map<String, String> environmentFor(String memberRef, Set<String> visibleProjects) {
        // Outside the home too, for the same reason: each member's command names the projects they see.
        Path bin = dir.resolveSibling("assistant-bin").resolve(memberRef.replaceAll("[^A-Za-z0-9-]", "-"));
        Path command = bin.resolve("dispatch");
        write(command, """
                #!/bin/sh
                # Written by Dispatch for its assistant: dispatch ask, for one member only (A-1).
                [ "$1" = ask ] || { echo 'only dispatch ask is available here' >&2; exit 2; }
                export %s=%s
                export %s=%s
                export %s=%s
                exec %s -XX:TieredStopAtLevel=1 -cp %s dispatch.Main "$@"
                """.formatted(
                MEMBER_VARIABLE, quote(memberRef),
                PROJECTS_VARIABLE, quote(String.join(",", new TreeSet<>(visibleProjects))),
                DATABASE_VARIABLE, quote(database.toString()),
                quote(javaCommand), quote(classPath)));
        try {
            Files.setPosixFilePermissions(command, PosixFilePermissions.fromString("rwx------"));
        } catch (UnsupportedOperationException | IOException e) {
            // Not a POSIX file system: see the class comment.
        }
        return Map.of("PATH", bin + java.io.File.pathSeparator + basePath);
    }

    /** One argument for sh, whatever it holds. */
    private static String quote(String value) {
        return "'" + value.replace("'", "'\\''") + "'";
    }

    private static void write(Path file, String content) {
        try {
            Files.createDirectories(file.getParent());
            Files.writeString(file, content, StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException("cannot write the assistant's " + file, e);
        }
    }

    private static String resource(String name) {
        try (InputStream in = AssistantHome.class.getResourceAsStream("/assistant/" + name)) {
            if (in == null) {
                throw new IllegalStateException("resource missing: /assistant/" + name);
            }
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
