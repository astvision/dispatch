# X-1 CI Tells the Truth — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** CI passes on Linux, macOS and Windows with the whole suite, Windows running under a temporary folder with a space and Cyrillic and skipping only the tests that still need a shell fake (X-2 runs those).

**Architecture:** Test bugs are fixed in the tests (own threads, absolute paths, a deterministic flood). Product bugs found on Windows are fixed where every caller passes: the SQLite driver's native library folder (`Database.open` through a new `dispatch.AnsiPaths`), the failed-clone cleanup (`WorkerInitCommand.deleteRecursively`), and a worker's worktree, which becomes text in the `Task` record so the server never re-spells it.

**Tech Stack:** Java 25 (FFM API for `GetShortPathNameW`), JUnit 5, SQLite (sqlite-jdbc), GitHub Actions.

**Spec:** `docs/superpowers/specs/2026-09-28-windows-macos-design.md` (X-1 rows, "Tests on every OS", "Other fixes (X-1)", and "Paths outside the ANSI code page").

## Global Constraints

- Java 25, no new dependencies; `pom.xml` is unchanged.
- Test-first: every product change starts with a test watched failing. A Windows-only bug whose red can only be seen on Windows CI names that run as its red (run 36110020571 or 36385991295).
- A test skipped on Windows says why: `@DisabledOnOs(value = OS.WINDOWS, disabledReason = "...")`. A test that needs a shell fake uses the fake's reason: `"the fake gh CLI is a POSIX shell script"` or the like.
- No test depends on the number of cores or on timing: work that must overlap gets its own threads.
- Secret hygiene (the repo is public): scan the diff before every push with `/usr/bin/grep -E`, one pattern per call; never print `~/.config/dispatch/smoke.env`.
- Commit messages: a plain sentence subject, a body saying why, and the two trailers from the session (`Co-Authored-By: Claude Opus 5.5 (1M context) <noreply@anthropic.com>`, `Claude-Session: …`).
- Work happens in the worktree `scratchpad/x1` on branch `x1-ci`, based on `origin/main` (3f3859a) so that draft PR #11 shows X-1 only. Local `main` (9d02cd8) takes X-1 by a `--no-ff` merge at the end.
- Every push is watched to the end (`gh run watch --exit-status`); red is fixed before X-1 is called done.

## Review Focus

1. **A Windows temporary folder with letters outside the code page** (amended after run 36395743054): SQLite unpacks into the user's own folder under ProgramData, and a link or another user's folder in its place is refused with the reason named, never used. `SqliteFolderTest` pins the folder's checks on every OS; the Windows suite, run under a Cyrillic `TMP`, proves the fallback.
2. **A worker on Windows reporting to a Linux server, and the other way round:** the worktree text comes back byte for byte (backslashes, drive letters, doubled separators). Task 8 pins it with `/home/ann//work/alm-7`, which Linux's `Path.of` would tidy.
3. **The flood test on a slow runner:** a request that reaches the server after the first four finished must not count as admitted concurrently. Task 3 releases the downloads only after the other requests were refused.
4. **A failed clone that leaves read-only git objects (Windows) or a folder without write permission (POSIX):** the cleanup removes them, and a file it still cannot remove is named in the warning. Task 7 pins both, the second with a file held open on Windows.
5. **A worker's worktree that happens to exist on the team machine itself** (a member running a worker on the server): the server's Sweeper must never inspect or remove it. Task 8 pins it at the query (`worker_id IS NULL`).

---

### Task 1: CI runs the whole matrix, Windows under a space and Cyrillic (done)

**Files:**
- Modify: `ui/vite.config.ts` (test block)
- Modify: `.github/workflows/ci.yml` (a Windows step before "Build and test")

- [x] **Step 1: vitest's per-test timeout is 15 s** (commit b56657b). `PeoplePage.test.tsx` took 5.8 s on the runner (run 36383373548), and the failed `ui` job skipped the Java matrix.

```ts
    // The default 5 s is too tight for a CI runner: PeoplePage's rename-and-make-admin test took 5.8 s there.
    testTimeout: 15_000,
```

- [x] **Step 2: Windows tests run with `TMP`/`TEMP` under a space and Cyrillic** (commit 851c2a0), **Maven's own temporary files on an ASCII path** (commit d01e70c; surefire failed with "Unable to access jarfile D:\a\_temp\dispatch ????\…"), **the folder on the system drive** (this plan's commit), where short names exist:

```yaml
      - name: Temporary files under a space and Cyrillic (Windows)
        if: runner.os == 'Windows'
        shell: pwsh
        run: |
          # As under C:\Users\Батбаяр: every temporary path from here on has a space and Cyrillic in it. On the system
          # drive, as a member's user folder is: Windows keeps 8.3 short names there, which Dispatch relies on (AnsiPaths).
          $dir = Join-Path $env:TEMP 'dispatch тест'
          New-Item -ItemType Directory -Force -Path $dir | Out-Null
          "TMP=$dir" >> $env:GITHUB_ENV
          "TEMP=$dir" >> $env:GITHUB_ENV
          # Except Maven's own: java.exe reads its arguments in the ANSI code page, which has no Cyrillic here, so
          # surefire's booter jar must sit on an ASCII path ("Unable to access jarfile D:\a\_temp\dispatch ????\...").
          "MAVEN_OPTS=-Djava.io.tmpdir=$env:RUNNER_TEMP" >> $env:GITHUB_ENV
```

- [ ] **Step 3: Commit the spec amendment, this plan and the system-drive move**

```bash
git add docs/superpowers/specs/2026-09-28-windows-macos-design.md docs/superpowers/plans/2026-09-28-windows-macos-x1.md .github/workflows/ci.yml
git commit -F <message file>   # "Plan X-1, and amend the spec with what its first Windows run found"
```

### Task 2: SQLite opens under a temporary folder outside the code page

> **Amended after run 36395743054:** the short name below did not work. The JDK spells a library's path out in full before
> loading it, so `C:\Users\RUNNER~1\…\DISPAT~1` was loaded as `C:\Users\runneradmin\…\dispatch ????`. `Database` now
> unpacks into `%ProgramData%\dispatch-<hash of the user's name>`, checked to be the user's own, and `AnsiPaths` waits for
> X-2/X-3, where java.exe does open a short name as given. The steps below are the first attempt, kept as it was built.

Red on Windows: run 36385991295, every class that opens a database ("Failed to load native library … sqlitejdbc.dll … Can't find dependent libraries").

**Files:**
- Create: `src/main/java/dispatch/AnsiPaths.java`
- Create: `src/test/java/dispatch/AnsiPathsTest.java`
- Modify: `src/main/java/dispatch/store/Database.java` (`open`)

**Interfaces:**
- Produces: `public static Optional<String> AnsiPaths.of(Path path)` — the path as `native.encoding` can spell it (itself or its 8.3 short name), empty when neither; Windows only (it calls kernel32 when the path is not encodable). Package-private `static Optional<String> of(String path, Charset codePage, UnaryOperator<String> shortName)` for tests. X-2 and X-3 use `of(Path)` for the paths they give java.exe.

- [ ] **Step 1: Write the failing tests**

```java
package dispatch;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

import java.io.IOException;
import java.nio.charset.Charset;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;

/** A path as Windows' ANSI code page can spell it, for native code that reads it there. */
class AnsiPathsTest {

    private static final Charset ENGLISH_WINDOWS = Charset.forName("windows-1252");

    @TempDir
    Path dir;

    @Test
    void aPathTheCodePageCanSpellStaysAsItIs() {
        assertEquals(Optional.of("C:\\Users\\José\\AppData\\Local\\Temp\\"),
                AnsiPaths.of("C:\\Users\\José\\AppData\\Local\\Temp\\", ENGLISH_WINDOWS, path -> fail("no short name needed")));
    }

    @Test
    void aPathWithOtherLettersIsGivenByItsShortName() {
        assertEquals(Optional.of("C:\\Users\\5C0E~1\\AppData\\Local\\Temp\\"),
                AnsiPaths.of("C:\\Users\\Өлзий\\AppData\\Local\\Temp\\", ENGLISH_WINDOWS, path -> "C:\\Users\\5C0E~1\\AppData\\Local\\Temp\\"));
    }

    @Test
    void aPathWithoutAShortNameCannotBeSpelled() {
        // Windows answers with the long name when the volume keeps no short names.
        assertEquals(Optional.empty(), AnsiPaths.of("D:\\Өлзий\\Temp\\", ENGLISH_WINDOWS, path -> path));
    }

    @Test
    @EnabledOnOs(value = OS.WINDOWS, disabledReason = "short names are Windows'")
    void windowsSpellsAFolderOutsideItsCodePageByItsShortName() throws IOException {
        // Ө is in no Windows code page but UTF-8, not even Mongolian Windows' 1251.
        Path folder = Files.createDirectory(dir.resolve("Өлзий тест"));

        String spelled = AnsiPaths.of(folder).orElseThrow();

        assertTrue(Charset.forName(System.getProperty("native.encoding")).newEncoder().canEncode(spelled), spelled);
        assertTrue(Files.isSameFile(folder, Path.of(spelled)), spelled);
    }
}
```

- [ ] **Step 2: Run them to watch them fail**

Run: `./mvnw -q -o test -Dtest=AnsiPathsTest -Dsurefire.failIfNoSpecifiedTests=false`
Expected: compilation fails, `AnsiPaths` does not exist.

- [ ] **Step 3: Write `AnsiPaths`**

```java
package dispatch;

import java.lang.foreign.Arena;
import java.lang.foreign.FunctionDescriptor;
import java.lang.foreign.Linker;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.SymbolLookup;
import java.lang.foreign.ValueLayout;
import java.lang.invoke.MethodHandle;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.Optional;
import java.util.function.UnaryOperator;

/**
 * Windows hands a path to native code, such as a library being loaded or java.exe reading its own arguments, in its ANSI
 * code page ({@code native.encoding}), where letters outside it arrive as "?": Cyrillic on English Windows, and Ө and Ү
 * even on Mongolian Windows, whose code page is 1251. Such a path can still be given by its 8.3 short name
 * ({@code C:\Users\5C0E~1}), whose letters every code page has; Windows keeps short names on its system drive.
 */
public final class AnsiPaths {

    private AnsiPaths() {
    }

    /** Windows only: {@code path} as this machine's code page can spell it, itself or its short name; empty if neither. */
    public static Optional<String> of(Path path) {
        return of(path.toString(), Charset.forName(System.getProperty("native.encoding")), AnsiPaths::shortName);
    }

    static Optional<String> of(String path, Charset codePage, UnaryOperator<String> shortName) {
        if (codePage.newEncoder().canEncode(path)) {
            return Optional.of(path);
        }
        String shortPath = shortName.apply(path);
        return codePage.newEncoder().canEncode(shortPath) ? Optional.of(shortPath) : Optional.empty();
    }

    /** GetShortPathNameW: the path itself when its volume keeps no short names. */
    private static String shortName(String path) {
        try (Arena arena = Arena.ofConfined()) {
            MethodHandle getShortPathName = Linker.nativeLinker().downcallHandle(
                    SymbolLookup.libraryLookup("kernel32", arena).find("GetShortPathNameW").orElseThrow(),
                    FunctionDescriptor.of(ValueLayout.JAVA_INT, ValueLayout.ADDRESS, ValueLayout.ADDRESS, ValueLayout.JAVA_INT));
            int capacity = 32_767;
            MemorySegment shortPath = arena.allocate(ValueLayout.JAVA_CHAR, capacity);
            int length = (int) getShortPathName.invokeExact(arena.allocateFrom(path, StandardCharsets.UTF_16LE), shortPath,
                    capacity);
            return length > 0 && length < capacity ? shortPath.getString(0, StandardCharsets.UTF_16LE) : path;
        } catch (Throwable e) {
            throw new IllegalStateException("cannot ask Windows for the short name of " + path, e);
        }
    }
}
```

- [ ] **Step 4: Run the tests to see them pass** (the Windows one is skipped here; CI runs it)

Run: `./mvnw -q -o test -Dtest=AnsiPathsTest -Dsurefire.failIfNoSpecifiedTests=false`
Expected: exit 0; `target/surefire-reports/TEST-dispatch.AnsiPathsTest.xml` shows `tests="4" … skipped="1" failures="0"`.

- [ ] **Step 5: Point the SQLite driver at a folder it can load from.** In `Database.open`, first line inside `try`:

```java
            sqliteFolder();
```

and the method, with `import dispatch.AnsiPaths;`:

```java
    /**
     * The SQLite driver unpacks its native library into the temporary folder and loads it from there, which Windows does
     * in its code page ({@link AnsiPaths}): with other letters in that folder's name no database would open. The driver is
     * given the folder's short name instead.
     */
    private static void sqliteFolder() {
        if (!System.getProperty("os.name").startsWith("Windows") || System.getProperty("org.sqlite.tmpdir") != null) {
            return;
        }
        String temp = System.getProperty("java.io.tmpdir");
        String loadable = AnsiPaths.of(Path.of(temp)).orElseThrow(() -> new DatabaseException("SQLite cannot be loaded from "
                + temp + ": Windows reads that name in its code page, which lacks some of its letters, and keeps no short name"
                + " for it; set TMP to a folder named in plain letters, such as C:\\Temp", null));
        if (!loadable.equals(temp)) {
            System.setProperty("org.sqlite.tmpdir", loadable);
        }
    }
```

- [ ] **Step 6: Run the whole suite** — `./mvnw -q -o test`; expected exit 0 (Linux never enters the new branch).

- [ ] **Step 7: Commit** — "Open the database under a temporary folder whose name Windows' code page cannot spell".

### Task 3: The flood test holds its downloads until the other requests are refused

Investigated as a limiter bug first: `WorkerApi.withInFlightLimit` reserves with one `incrementAndGet` before the action and releases in `finally`, and the server's executor is a virtual thread per request, so nothing queues in front of it. Runs 36110020571 (Windows: `[200, 200, 429, 200, 200, 200]`) and 36385499713 (Linux: six 200s) show requests that reached the server after the test had released the four downloads and they had finished: admitted, correctly. The test assumed every request arrives while the downloads are held.

**Files:**
- Modify: `src/test/java/dispatch/worker/WorkerProtocolTest.java` (`aWorkerFloodingConcurrentRequestsIsRefusedBeyondTheLimit`)

- [ ] **Step 1: Make the late request visible (red).** Temporarily start the last caller 2 s late (`if (index == attempts - 1) Thread.sleep(2000);` before `http.send`), run
`./mvnw -q -o test -Dtest='WorkerProtocolTest#aWorkerFloodingConcurrentRequestsIsRefusedBeyondTheLimit' -Dsurefire.failIfNoSpecifiedTests=false`
and see it fail with `expected: <4> but was: <5>`.

- [ ] **Step 2: Hold the downloads until the refusals are in.** Add a latch counted down by each refused answer, and wait for it before releasing:

```java
        CountDownLatch refused = new CountDownLatch(attempts - cap);
```

in each caller, after `statuses[index] = answer.statusCode();`:

```java
                        if (answer.statusCode() == 429) {
                            refused.countDown();
                        }
```

and between the `insideDownload` assertion and `releaseDownloads.countDown()`:

```java
            // The others must be refused while those four still run: released earlier, a request that merely arrived late
            // would be admitted after one of them finished, correctly, and the count would say nothing about the limit.
            assertTrue(refused.await(10, TimeUnit.SECONDS), "expected " + (attempts - cap) + " refusals while " + cap
                    + " requests ran; statuses so far: " + Arrays.toString(statuses));
```

Update the comment above `insideDownload` so it no longer claims every other request has been refused by then.

- [ ] **Step 3: Run it with the late caller still in (green), then remove the 2 s delay and run again (green).**

- [ ] **Step 4: Commit** — "Hold the flood test's downloads until the other requests are refused".

### Task 4: ConfigFileTest's concurrent edits get threads of their own

Red: run 36110020571 (Linux and Windows: `TimeoutException`); `ForkJoinPool.commonPool` has one thread on a two-core machine, so two `runAsync` tasks meeting at a barrier never overlap.

**Files:**
- Modify: `src/test/java/dispatch/config/ConfigFileTest.java`

- [ ] **Step 1: See it fail here.** Run with a one-thread common pool:
`JAVA_TOOL_OPTIONS=-Djava.util.concurrent.ForkJoinPool.common.parallelism=1 ./mvnw -q -o test -Dtest=ConfigFileTest -Dsurefire.failIfNoSpecifiedTests=false`
Expected: `twoConcurrentEditsThroughEditBothLand` fails with `TimeoutException`.

- [ ] **Step 2: Replace both tests' `CompletableFuture.runAsync` pairs with one helper:**

```java
    /** Runs both at once, each on a thread of its own: the common pool has one thread on a two-core machine. */
    private static void atOnce(Runnable first, Runnable second) throws Exception {
        CyclicBarrier barrier = new CyclicBarrier(2);
        try (ExecutorService threads = Executors.newFixedThreadPool(2)) {
            Future<?> one = threads.submit(() -> {
                await(barrier);
                first.run();
            });
            Future<?> other = threads.submit(() -> {
                await(barrier);
                second.run();
            });
            one.get(10, TimeUnit.SECONDS);
            other.get(10, TimeUnit.SECONDS);
        }
    }
```

```java
        atOnce(() -> ConfigFile.edit(file, ENV, text -> ConfigEdit.append(text, ConfigEdit.At.of("telegram", "admins"), "201")),
                () -> ConfigFile.edit(file, ENV, text -> ConfigEdit.append(text, ConfigEdit.At.of("telegram", "admins"), "202")));
```

(and the same with `real` and `viaLinkedDir` in the symlink test). Imports: `ExecutorService`, `Executors`, `Future`; drop `CompletableFuture`.

- [ ] **Step 3: Run Step 1's command again (green) and without `JAVA_TOOL_OPTIONS` (green).**

- [ ] **Step 4: Commit** — "Give ConfigFileTest's concurrent edits threads of their own".

### Task 5: WorkerConfigLoaderTest uses absolute paths of this OS

Red: run 36110020571 (Windows: `worker.yaml is invalid`, since `/home/ann/work/crm` is not absolute there).

**Files:**
- Modify: `src/test/java/dispatch/worker/WorkerConfigLoaderTest.java` (`aWorkerConfigMapsProjectsToLocalClones`)

- [ ] **Step 1: Use a path under the test's own folder, quoted for YAML:**

```java
        String crm = dir.resolve("work").resolve("crm").toString();
        WorkerConfig config = WorkerConfigLoader.load(write("""
                team: https://team.example.com
                name: ann-laptop
                maxConcurrentRuns: 2
                claudeCommand: /usr/local/bin/claude
                projects:
                  crm:
                    path: '%s'
                    model: opus
                """.formatted(crm.replace("'", "''"))));
        ...
        assertEquals(crm, config.projects().get("crm").path());
```

- [ ] **Step 2: Run** `./mvnw -q -o test -Dtest=WorkerConfigLoaderTest -Dsurefire.failIfNoSpecifiedTests=false` (green here; Windows CI is its proof).

- [ ] **Step 3: Commit** — "Give WorkerConfigLoaderTest a path that is absolute on every OS".

### Task 6: ChecksTest's Codex stand-in waits for X-2 on Windows

Red: run 36385991295 (Windows: `ChecksTest` 1 error; `setPosixFilePermissions` on a `#!/bin/sh` stand-in).

**Files:**
- Modify: `src/test/java/dispatch/cli/ChecksTest.java` (`aCodexThatIsNotLoggedInIsAWarning`)

- [ ] **Step 1: Skip it on Windows with the fake's reason** (X-2's `FakeCli` replaces the stand-in):

```java
    @Test
    @DisabledOnOs(value = OS.WINDOWS, disabledReason = "the stand-in codex is a POSIX shell script")
    void aCodexThatIsNotLoggedInIsAWarning() throws IOException {
```

- [ ] **Step 2: Run** `./mvnw -q -o test -Dtest=ChecksTest -Dsurefire.failIfNoSpecifiedTests=false` (green).

- [ ] **Step 3: Commit** — "Skip ChecksTest's shell stand-in for Codex on Windows until X-2".

### Task 7: A failed clone's read-only files are removed too, and leftovers named

Red on Windows: run 36110020571 (`aGenuinelyInterruptedCloneCleansUpSoALaterForcedRunCanRetryIt`: git makes its object files read-only, and Windows refuses to delete a read-only file). Red here: a folder without write permission refuses the same way on POSIX.

**Files:**
- Modify: `src/main/java/dispatch/worker/WorkerInitCommand.java` (`clone`, `deleteRecursively`)
- Modify: `src/test/java/dispatch/worker/WorkerInitCommandTest.java`

**Interfaces:**
- Produces: package-private `static List<Path> WorkerInitCommand.deleteRecursively(Path root)` — what it could not remove, deepest first; empty when all went.

- [ ] **Step 1: Make `deleteRecursively` report what it left** (same behaviour otherwise), so a test can see it:

```java
    /** Removes what this run's own failed clone created; never touches a pre-existing path. Returns what is left. */
    static List<Path> deleteRecursively(Path root) {
        if (!Files.exists(root, LinkOption.NOFOLLOW_LINKS)) {
            return List.of();
        }
        List<Path> paths;
        try (Stream<Path> walk = Files.walk(root)) {
            paths = walk.sorted(Comparator.reverseOrder()).toList();
        } catch (IOException | UncheckedIOException e) {
            return List.of(root);
        }
        List<Path> left = new ArrayList<>();
        for (Path path : paths) {
            try {
                Files.deleteIfExists(path);
            } catch (IOException e) {
                left.add(path);
            }
        }
        return left;
    }
```

- [ ] **Step 2: Write the failing tests**

```java
    @Test
    void aFailedClonesReadOnlyFilesAreRemovedToo() throws Exception {
        // Git makes its object files read-only, and Windows will not delete a read-only file; a folder without write
        // permission refuses the same way on macOS and Linux.
        Path root = dir.resolve("partial");
        Path pack = Files.createDirectories(root.resolve(".git/objects/pack"));
        Path object = Files.writeString(pack.resolve("pack-1.pack"), "PACK");
        assertTrue(object.toFile().setWritable(false));
        assertTrue(pack.toFile().setWritable(false));

        assertEquals(List.of(), WorkerInitCommand.deleteRecursively(root));
        assertFalse(Files.exists(root));
    }

    @Test
    @EnabledOnOs(value = OS.WINDOWS, disabledReason = "only Windows refuses to delete a file that is open")
    void whatAFailedCloneCannotRemoveIsNamed() throws Exception {
        Path root = Files.createDirectories(dir.resolve("partial"));
        Path held = Files.writeString(root.resolve("held.txt"), "open");
        try (InputStream open = new FileInputStream(held.toFile())) {
            assertEquals(List.of(held, root), WorkerInitCommand.deleteRecursively(root));
        }
    }
```

Run: `./mvnw -q -o test -Dtest='WorkerInitCommandTest#aFailedClonesReadOnlyFilesAreRemovedToo' -Dsurefire.failIfNoSpecifiedTests=false`
Expected: FAIL, `expected: <[]> but was: <[…pack-1.pack, …pack, …objects, ….git, …partial]>`.

- [ ] **Step 3: Retry a refused delete once, after making the file and its folder writable:**

```java
        for (Path path : paths) {
            if (!delete(path)) {
                left.add(path);
            }
        }
```

```java
    /**
     * A refused delete is tried once more after making the file and its folder writable: Windows refuses a read-only
     * file, which git makes of its objects, and POSIX a file in a folder without write permission.
     */
    private static boolean delete(Path path) {
        try {
            Files.deleteIfExists(path);
            return true;
        } catch (IOException refused) {
            path.toFile().setWritable(true);
            Path folder = path.getParent();
            if (folder != null) {
                folder.toFile().setWritable(true);
            }
            try {
                Files.deleteIfExists(path);
                return true;
            } catch (IOException stillRefused) {
                return false;
            }
        }
    }
```

- [ ] **Step 4: Name the leftovers in the clone's warning** (in `clone`, replacing the bare call):

```java
            if (!existedBefore) {
                List<Path> left = deleteRecursively(target);
                if (!left.isEmpty()) {
                    terminal.warn("cannot remove " + left.size() + " files of the failed clone, such as " + left.getFirst()
                            + "; delete " + target + " before trying again");
                }
            }
```

- [ ] **Step 5: Run** `./mvnw -q -o test -Dtest=WorkerInitCommandTest -Dsurefire.failIfNoSpecifiedTests=false` (green; the Windows-only test is skipped here).

- [ ] **Step 6: Commit** — "Remove a failed clone's read-only files too, and name what cannot be removed".

### Task 8: A worker's worktree is text on the server

Red on Windows: run 36110020571 (`CoordinatorTest.whatTheWorkerReportsWhileItRunsIsRecordedAtOnce:110` and `RemoteWorkersTest.progressRenewsTheLeaseRecordsTheWorktreeAndFeedsStatus:179`: `\var\lib\dispatch\worktrees\1`). Red here: Linux's `Path.of` tidies `//`.

**Files:**
- Modify: `src/main/java/dispatch/domain/Task.java` (`Path worktree` → `String worktree`)
- Modify: `src/main/java/dispatch/store/Tasks.java` (`recordWorktree`, `map`, `finishedIdleWithWorktree`)
- Modify: `src/main/java/dispatch/store/Row.java` (remove `path`, now unused)
- Modify: `src/main/java/dispatch/core/RunTransitions.java` (`recordWorktree`)
- Modify: `src/main/java/dispatch/core/Coordinator.java` (`worktreeCreated`, `newJob`)
- Modify: `src/main/java/dispatch/core/Sweeper.java` (`sweep`, `sweep(Task)`)
- Test: `src/test/java/dispatch/core/CoordinatorTest.java`, `src/test/java/dispatch/store/TasksTest.java`

**Interfaces:**
- Produces: `Task.worktree()` is a `String`, exactly as the machine that made the worktree spelled it. `Tasks.recordWorktree(Tx tx, long id, String worktree, String baseSha, Instant now)`; `RunTransitions.recordWorktree(long taskId, String worktree, String baseSha)`. `Tasks.finishedIdleWithWorktree` returns only tasks with no worker pin.

- [ ] **Step 1: Write the failing test** in `CoordinatorTest`:

```java
    @Test
    void aWorkersWorktreeIsHandedBackExactlyAsItReportedIt() {
        // Each computer spells its worktree its own way; the team machine keeps the text. Read as a path here, a Windows
        // server would hand /home/ann/… back as \home\ann\…, and this one tidies the "//".
        long id = queue("Fix the login timeout");
        String reported = "/home/ann//work/alm-7";
        Worker planner = (job, events, control) -> {
            events.worktreeCreated(reported, "abc123");
            return JobResult.succeeded(agentResult(PLAN_JSON));
        };
        coordinator(projects(List.of(ALM)), planner).execute(claim());
        db.transaction(tx -> tasks.approve(tx, BOLD, id, 1));

        coordinator(projects(List.of(ALM)), remember(JobResult.failed(FailureReason.SETUP, "stops here", null))).execute(claim());

        assertEquals(reported, given.get().worktree());
    }
```

Run: `./mvnw -q -o test -Dtest='CoordinatorTest#aWorkersWorktreeIsHandedBackExactlyAsItReportedIt' -Dsurefire.failIfNoSpecifiedTests=false`
Expected: FAIL, `expected: </home/ann//work/alm-7> but was: </home/ann/work/alm-7>`.

- [ ] **Step 2: Make it text.**
  - `Task`: `String worktree,` with a line in the record's Javadoc: "@param worktree as the computer that made it spells it; only that computer reads it as a path".
  - `Tasks.map`: `row.string("worktree"),`; `Tasks.recordWorktree(Tx tx, long id, String worktree, String baseSha, Instant now)`.
  - `Row`: delete `path(String)` and its now unused import.
  - `RunTransitions.recordWorktree(long taskId, String worktree, String baseSha)`.
  - `Coordinator`: `transitions.recordWorktree(claimed.taskId(), worktree, baseSha);` and in `newJob` pass `task.worktree()` as it is.
  - `Sweeper.sweep()`: `Path worktree = Path.of(task.worktree());` used for `Files.isDirectory` and passed on as `sweep(task, worktree)`, which uses it for `workspaces.state(worktree, task.id(), task.baseSha())`; its log lines keep `task.worktree()`.

Run Step 1's command: PASS. Then `./mvnw -q -o test`: exit 0.

- [ ] **Step 3: Commit** — "Keep a worker's worktree as the text it reported".

- [ ] **Step 4: Write the failing test** in `TasksTest` (imports `dispatch.domain.Task`, `java.util.List`):

```java
    @Test
    void aWorktreeOnAMembersComputerIsNeverThisMachinesToSweep() {
        long here = task();
        long there = task();
        long workerId = db.transactionReturning(tx -> Workers.insert(tx, "telegram:1", "laptop", "a".repeat(64), T0));
        db.transaction(tx -> {
            Tasks.recordWorktree(tx, here, "/var/lib/dispatch/worktrees/" + here, "abc", T0);
            Tasks.recordWorktree(tx, there, "C:\\Users\\Ann\\dispatch\\worktrees\\" + there, "abc", T0);
            Tasks.recordWorker(tx, there, workerId, T0);
            Tasks.changePhase(tx, here, Phase.PLANNING, Phase.COMPLETED, T0);
            Tasks.changePhase(tx, there, Phase.PLANNING, Phase.COMPLETED, T0);
        });

        List<Task> idle = db.transactionReturning(tx -> Tasks.finishedIdleWithWorktree(tx, T0.plusSeconds(60)));

        assertEquals(List.of(here), idle.stream().map(Task::id).toList(), "a member's computer sweeps its own (WorkerSweeper)");
    }
```

Run: `./mvnw -q -o test -Dtest='TasksTest' -Dsurefire.failIfNoSpecifiedTests=false`
Expected: FAIL, `expected: <[1]> but was: <[1, 2]>`.

- [ ] **Step 5: Filter at the query:**

```java
    /**
     * Finished tasks with a worktree on this machine, unchanged since before {@code idleSince}, oldest first. One made on a
     * member's computer is that computer's to sweep (WorkerSweeper), and its text is never a path here.
     */
    public static List<Task> finishedIdleWithWorktree(Tx tx, Instant idleSince) {
        return tx.list("SELECT " + COLUMNS + " FROM task WHERE phase IN (?, ?, ?, ?) AND worktree IS NOT NULL"
                        + " AND worker_id IS NULL AND updated_at < ? ORDER BY updated_at, id", Tasks::map,
                Phase.COMPLETED, Phase.FAILED, Phase.REJECTED, Phase.CANCELLED, idleSince);
    }
```

Run: `./mvnw -q -o test`: exit 0.

- [ ] **Step 6: Commit** — "Never sweep a worktree that is on a member's computer".

### Task 9: Push, watch the three OSes, fix what they show, merge

- [ ] **Step 1: Scan the branch's diff for secrets** (one pattern per call):

```bash
git diff origin/main > ../x1.diff
for p in '[0-9]{8,10}:AA' 'gh[pousr]_[A-Za-z0-9]{20,}' 'sk-ant-' 'AKIA[0-9A-Z]{16}' "$HOME" 'BEGIN [A-Z ]*PRIVATE'; do echo "$p: $(/usr/bin/grep -cE -- "$p" ../x1.diff)"; done
```

Expected: every count 0.

- [ ] **Step 2: Push and watch:** `git push origin x1-ci`, then `gh run watch <run id> --exit-status`.

- [ ] **Step 3: For each failure, in this order:** a test bug or a small product bug is fixed here, test-first, as its own commit; a test that needs a shell fake gets `@DisabledOnOs(value = OS.WINDOWS, disabledReason = "<the fake> is a POSIX shell script")`; a path that java.exe must read outside the code page is X-2's or X-3's (spec, "Paths outside the ANSI code page") and is skipped on Windows with that reason. Push, watch, repeat until the three OSes pass.

- [ ] **Step 4: Review.** Dispatch a reviewer on `origin/main..x1-ci` with the spec and this plan (superpowers:requesting-code-review); fix Critical and Important findings test-first; push; watch.

- [ ] **Step 5: Merge into local main:** `git -C /opt/tools/dispatch merge --no-ff -F <message file> x1-ci`; run the whole suite on the merge; mark PR #11 ready only when the owner pushes `main` (the PR then shows as merged).
