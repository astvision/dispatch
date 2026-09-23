package dispatch;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.AclEntry;
import java.nio.file.attribute.AclFileAttributeView;
import java.nio.file.attribute.PosixFilePermissions;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.DisabledOnOs;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;

class OwnerOnlyTest {

    @TempDir
    Path dir;

    @Test
    @DisabledOnOs(OS.WINDOWS)
    void withPosixPermissionsOnlyTheOwnerGetsAnyAccess() throws IOException {
        Path runs = dir.resolve("state/runs");

        OwnerOnly.createDirectories(runs);
        Path secrets = OwnerOnly.createFile(dir.resolve("state/dispatch.env"));

        assertEquals("rwx------", permissions(dir.resolve("state")));
        assertEquals("rwx------", permissions(runs));
        assertEquals("rw-------", permissions(secrets));
        assertEquals(Optional.empty(), OwnerOnly.groupOrOthersAccess(secrets));
        Files.setPosixFilePermissions(secrets, PosixFilePermissions.fromString("rw-r-----"));
        assertEquals(Optional.of("rw-r-----"), OwnerOnly.groupOrOthersAccess(secrets));
        assertEquals(Optional.empty(), OwnerOnly.othersAccess(secrets), "group access alone is not others' access");
    }

    @Test
    @EnabledOnOs(OS.WINDOWS)
    void onWindowsTheOnlyAccessControlEntryIsTheCurrentUsers() throws IOException {
        Path state = dir.resolve("state");

        OwnerOnly.createDirectories(state);
        Path secrets = OwnerOnly.createFile(state.resolve("dispatch.env"));

        for (Path path : List.of(state, secrets)) {
            List<AclEntry> acl = Files.getFileAttributeView(path, AclFileAttributeView.class).getAcl();
            assertEquals(1, acl.size(), acl.toString());
            assertEquals(System.getProperty("user.name").toLowerCase(), acl.getFirst().principal().getName().toLowerCase()
                    .replaceFirst("^.*\\\\", ""));
        }
        Files.writeString(secrets, "TELEGRAM_BOT_TOKEN=x\n");
        assertEquals("TELEGRAM_BOT_TOKEN=x\n", Files.readString(secrets), "the owner can still use it");
    }

    /**
     * Two worker requests fetching attachments concurrently both call {@code createDirectories} on the same
     * {@code outgoing} directory: the scan-then-create inside it is not atomic, so a naive implementation throws
     * {@code FileAlreadyExistsException} for whichever caller loses the race, even though the directory ends up
     * exactly as every caller wanted it.
     */
    @Test
    @DisabledOnOs(OS.WINDOWS)
    void twoConcurrentCallersCreatingTheSameDirectoryDoNotRace() throws Exception {
        Path shared = dir.resolve("state/outgoing");
        int racers = 8;
        CountDownLatch ready = new CountDownLatch(racers);
        CountDownLatch go = new CountDownLatch(1);
        List<Thread> threads = new ArrayList<>();
        List<Exception> failures = Collections.synchronizedList(new ArrayList<>());
        for (int i = 0; i < racers; i++) {
            threads.add(Thread.ofVirtual().start(() -> {
                ready.countDown();
                try {
                    go.await();
                    OwnerOnly.createDirectories(shared);
                } catch (Exception e) {
                    failures.add(e);
                }
            }));
        }

        assertTrue(ready.await(10, TimeUnit.SECONDS), "every racer should have reached the starting line");
        go.countDown();
        for (Thread thread : threads) {
            assertTrue(thread.join(Duration.ofSeconds(10)), "every racer should have finished");
        }

        assertTrue(failures.isEmpty(), failures.toString());
        assertEquals("rwx------", permissions(shared));
    }

    private static String permissions(Path path) throws IOException {
        return PosixFilePermissions.toString(Files.getPosixFilePermissions(path));
    }
}
