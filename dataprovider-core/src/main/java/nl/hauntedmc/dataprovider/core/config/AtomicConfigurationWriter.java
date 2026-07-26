package nl.hauntedmc.dataprovider.core.config;

import nl.hauntedmc.dataprovider.core.security.FilePermissionHardening;
import nl.hauntedmc.dataprovider.logging.LoggerAdapter;
import org.spongepowered.configurate.CommentedConfigurationNode;
import org.spongepowered.configurate.yaml.NodeStyle;
import org.spongepowered.configurate.yaml.YamlConfigurationLoader;

import java.io.IOException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;

/** Writes YAML through a sibling temporary file so an interrupted update cannot truncate the live file. */
public final class AtomicConfigurationWriter {

    private AtomicConfigurationWriter() {
    }

    public static void save(
            Path destination,
            CommentedConfigurationNode configuration,
            LoggerAdapter logger,
            String description
    ) throws IOException {
        Path directory = destination.getParent();
        if (directory == null) {
            throw new IOException("Configuration file has no parent directory: " + destination);
        }
        Path temporaryFile = Files.createTempFile(directory, destination.getFileName().toString(), ".tmp");
        try {
            // Configurate otherwise preserves/infers compact flow mappings for newly
            // reconciled nodes. Force the conventional, readable block YAML style.
            YamlConfigurationLoader.builder()
                    .path(temporaryFile)
                    .nodeStyle(NodeStyle.BLOCK)
                    .build()
                    .save(configuration);
            FilePermissionHardening.restrictFileToOwner(temporaryFile, logger, description + " temporary file");
            try {
                Files.move(temporaryFile, destination, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } catch (AtomicMoveNotSupportedException ignored) {
                Files.move(temporaryFile, destination, StandardCopyOption.REPLACE_EXISTING);
            }
            FilePermissionHardening.restrictFileToOwner(destination, logger, description);
        } finally {
            try {
                Files.deleteIfExists(temporaryFile);
            } catch (IOException cleanupFailure) {
                // Do not conceal a failed save or move with a less important cleanup failure.
                logger.warn("Failed to remove temporary " + description + " file at " + temporaryFile, cleanupFailure);
            }
        }
    }
}
