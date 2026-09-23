package dispatch;

import java.io.IOException;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.AclEntry;
import java.nio.file.attribute.AclEntryFlag;
import java.nio.file.attribute.AclEntryPermission;
import java.nio.file.attribute.AclEntryType;
import java.nio.file.attribute.AclFileAttributeView;
import java.nio.file.attribute.PosixFilePermission;
import java.nio.file.attribute.PosixFilePermissions;
import java.nio.file.attribute.UserPrincipal;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * Files and directories only their owner can use, for state and secrets on any OS. Where the file system has POSIX
 * permissions (Linux, macOS) they get mode 700 or 600 when created. Where it has ACLs instead (Windows), the ACL is replaced
 * with one entry for the current user right after creation, before anything is written.
 */
public final class OwnerOnly {

    private static final Set<String> VIEWS = FileSystems.getDefault().supportedFileAttributeViews();

    private OwnerOnly() {
    }

    /**
     * Creates {@code dir} and any missing parents, each owner-only; existing directories are left as they are.
     *
     * <p>Safe for two concurrent callers racing to create the same directory (e.g. two worker requests fetching
     * attachments into the same {@code outgoing} directory at once): the scan above and the create below are not
     * atomic together, so a {@link FileAlreadyExistsException} here means another caller won the race, not that
     * this call failed — the directory exists either way, which is all a caller of this method actually wants.
     */
    public static void createDirectories(Path dir) throws IOException {
        List<Path> missing = new ArrayList<>();
        for (Path current = dir.toAbsolutePath(); current != null && !Files.exists(current); current = current.getParent()) {
            missing.addFirst(current);
        }
        for (Path created : missing) {
            try {
                if (VIEWS.contains("posix")) {
                    Files.createDirectory(created, PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rwx------")));
                } else {
                    Files.createDirectory(created);
                    restrictAcl(created, true);
                }
            } catch (FileAlreadyExistsException e) {
                if (!Files.isDirectory(created)) {
                    throw e;
                }
            }
        }
    }

    /** Creates an empty owner-only file; fails if it exists. */
    public static Path createFile(Path file) throws IOException {
        if (VIEWS.contains("posix")) {
            return Files.createFile(file, PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rw-------")));
        }
        Files.createFile(file);
        restrictAcl(file, false);
        return file;
    }

    /**
     * The permissions, e.g. "rw-r-----", when the group or others have any access; empty when only the owner has. Always
     * empty without POSIX permissions.
     * ponytail: Windows ACLs are not inspected; the ACL is only set when Dispatch creates a file (add a check if files are
     * often created by hand there).
     */
    public static Optional<String> groupOrOthersAccess(Path path) throws IOException {
        return access(path, EnumSet.of(PosixFilePermission.GROUP_READ, PosixFilePermission.GROUP_WRITE,
                PosixFilePermission.GROUP_EXECUTE, PosixFilePermission.OTHERS_READ, PosixFilePermission.OTHERS_WRITE,
                PosixFilePermission.OTHERS_EXECUTE));
    }

    /** Like {@link #groupOrOthersAccess}, for directories where group access is intended (systemd's StateDirectoryMode=0750). */
    public static Optional<String> othersAccess(Path path) throws IOException {
        return access(path, EnumSet.of(PosixFilePermission.OTHERS_READ, PosixFilePermission.OTHERS_WRITE,
                PosixFilePermission.OTHERS_EXECUTE));
    }

    private static Optional<String> access(Path path, Set<PosixFilePermission> tooOpen) throws IOException {
        if (!VIEWS.contains("posix")) {
            return Optional.empty();
        }
        Set<PosixFilePermission> permissions = Files.getPosixFilePermissions(path);
        return permissions.stream().anyMatch(tooOpen::contains)
                ? Optional.of(PosixFilePermissions.toString(permissions))
                : Optional.empty();
    }

    /**
     * Replaces the ACL with full access for the current user only. The user is looked up by name rather than taken from
     * the file's owner: for an elevated administrator, Windows makes the Administrators group the owner of new files.
     */
    private static void restrictAcl(Path path, boolean directory) throws IOException {
        AclFileAttributeView view = Files.getFileAttributeView(path, AclFileAttributeView.class);
        if (view == null) {
            throw new IOException("cannot make " + path + " owner-only: the file system has neither POSIX permissions nor ACLs");
        }
        UserPrincipal user = path.getFileSystem().getUserPrincipalLookupService().lookupPrincipalByName(System.getProperty("user.name"));
        Set<AclEntryFlag> flags = directory
                ? EnumSet.of(AclEntryFlag.FILE_INHERIT, AclEntryFlag.DIRECTORY_INHERIT)
                : EnumSet.noneOf(AclEntryFlag.class);
        view.setAcl(List.of(AclEntry.newBuilder().setType(AclEntryType.ALLOW).setPrincipal(user)
                .setPermissions(EnumSet.allOf(AclEntryPermission.class)).setFlags(flags).build()));
    }
}
