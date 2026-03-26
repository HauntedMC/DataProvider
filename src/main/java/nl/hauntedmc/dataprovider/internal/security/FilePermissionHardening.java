package nl.hauntedmc.dataprovider.internal.security;

import nl.hauntedmc.dataprovider.platform.common.logger.ILoggerAdapter;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFileAttributeView;
import java.nio.file.attribute.PosixFilePermission;
import java.util.Set;

/**
 * Best-effort hardening for config files that may contain credentials.
 */
public final class FilePermissionHardening {

    private static final Set<PosixFilePermission> OWNER_DIRECTORY_PERMISSIONS = Set.of(
            PosixFilePermission.OWNER_READ,
            PosixFilePermission.OWNER_WRITE,
            PosixFilePermission.OWNER_EXECUTE
    );
    private static final Set<PosixFilePermission> OWNER_FILE_PERMISSIONS = Set.of(
            PosixFilePermission.OWNER_READ,
            PosixFilePermission.OWNER_WRITE
    );

    private FilePermissionHardening() {
    }

    public static void restrictDirectoryToOwner(Path directory, ILoggerAdapter logger, String description) {
        restrictToOwner(directory, OWNER_DIRECTORY_PERMISSIONS, logger, description);
    }

    public static void restrictFileToOwner(Path file, ILoggerAdapter logger, String description) {
        restrictToOwner(file, OWNER_FILE_PERMISSIONS, logger, description);
    }

    private static void restrictToOwner(
            Path path,
            Set<PosixFilePermission> permissions,
            ILoggerAdapter logger,
            String description
    ) {
        if (path == null || logger == null || description == null || !Files.exists(path)) {
            return;
        }

        PosixFileAttributeView attributeView = Files.getFileAttributeView(path, PosixFileAttributeView.class);
        if (attributeView == null) {
            return;
        }

        try {
            Files.setPosixFilePermissions(path, permissions);
        } catch (IOException e) {
            logger.warn("Failed to harden file permissions for " + description + " at " + path, e);
        }
    }
}
