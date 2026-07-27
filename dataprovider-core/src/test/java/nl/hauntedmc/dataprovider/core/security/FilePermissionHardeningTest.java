package nl.hauntedmc.dataprovider.core.security;

import nl.hauntedmc.dataprovider.core.testutil.RecordingLoggerAdapter;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFileAttributeView;
import java.nio.file.attribute.PosixFilePermission;
import java.util.EnumSet;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

class FilePermissionHardeningTest {

    @TempDir
    Path tempDirectory;

    @Test
    void restrictsFilesToOwnerReadAndWriteOnPosixFileSystems() throws Exception {
        Path file = Files.writeString(tempDirectory.resolve("credentials.yml"), "password: secret");
        assumePosix(file);
        Files.setPosixFilePermissions(file, EnumSet.allOf(PosixFilePermission.class));

        RecordingLoggerAdapter logger = new RecordingLoggerAdapter();
        FilePermissionHardening.restrictFileToOwner(file, logger, "credentials");

        assertEquals(Set.of(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE),
                Files.getPosixFilePermissions(file));
        assertTrue(logger.warnMessages().isEmpty());
    }

    @Test
    void restrictsDirectoriesToOwnerReadWriteAndExecuteOnPosixFileSystems() throws Exception {
        Path directory = Files.createDirectory(tempDirectory.resolve("private"));
        assumePosix(directory);
        Files.setPosixFilePermissions(directory, EnumSet.allOf(PosixFilePermission.class));

        RecordingLoggerAdapter logger = new RecordingLoggerAdapter();
        FilePermissionHardening.restrictDirectoryToOwner(directory, logger, "private directory");

        assertEquals(Set.of(
                        PosixFilePermission.OWNER_READ,
                        PosixFilePermission.OWNER_WRITE,
                        PosixFilePermission.OWNER_EXECUTE
                ),
                Files.getPosixFilePermissions(directory));
        assertTrue(logger.warnMessages().isEmpty());
    }

    @Test
    void nullAndMissingInputsAreSafeNoOps() {
        RecordingLoggerAdapter logger = new RecordingLoggerAdapter();
        Path missing = tempDirectory.resolve("missing.yml");

        FilePermissionHardening.restrictFileToOwner(null, logger, "file");
        FilePermissionHardening.restrictFileToOwner(missing, logger, "file");
        FilePermissionHardening.restrictFileToOwner(missing, null, "file");
        FilePermissionHardening.restrictFileToOwner(missing, logger, null);
        FilePermissionHardening.restrictDirectoryToOwner(null, logger, "directory");
        FilePermissionHardening.restrictDirectoryToOwner(missing, logger, "directory");

        assertTrue(logger.infoMessages().isEmpty());
        assertTrue(logger.warnMessages().isEmpty());
        assertTrue(logger.errorMessages().isEmpty());
    }

    private static void assumePosix(Path path) {
        assumeTrue(Files.getFileAttributeView(path, PosixFileAttributeView.class) != null);
    }
}
