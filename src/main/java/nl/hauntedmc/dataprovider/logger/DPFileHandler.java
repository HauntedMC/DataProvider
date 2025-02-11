package nl.hauntedmc.dataprovider.logger;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.logging.*;

class DPFileHandler extends Handler {

    private final File logsFolder;
    private final String baseFileName; // e.g. "DataProvider.log"
    private final int fileLimit;
    private final int fileCount;
    private final Formatter formatter;
    private OutputStream outputStream;
    private File currentFile;

    /**
     * Constructs a new DPFileHandler.
     *
     * @param logsFolder   The folder where log files are stored.
     * @param baseFileName The active log file name (without any generation suffix).
     * @param fileLimit    Maximum size in bytes of the active log file.
     * @param fileCount    Maximum number of rolled files to keep.
     * @param append       Whether to append to an existing file.
     * @throws IOException if an I/O error occurs.
     * @throws SecurityException if a security manager exists and the caller does not have LoggingPermission("control").
     */
    protected DPFileHandler(File logsFolder, String baseFileName, int fileLimit, int fileCount, boolean append)
            throws IOException, SecurityException {
        this.logsFolder = logsFolder;
        this.baseFileName = baseFileName;
        this.fileLimit = fileLimit;
        this.fileCount = fileCount;
        // Use a custom formatter if desired (or use SimpleFormatter)
        this.formatter = new DPLogFormatter();
        currentFile = new File(logsFolder, baseFileName);
        // If appending and an active file exists and is non-empty, perform rollover to start fresh.
        if (append && currentFile.exists() && currentFile.length() > 0) {
            rollover();
        }
        // Open the output stream for the active log file.
        openOutputStream(false);
    }

    private void openOutputStream(boolean append) throws IOException {
        currentFile = new File(logsFolder, baseFileName);
        outputStream = new BufferedOutputStream(new FileOutputStream(currentFile, append));
    }

    @Override
    public synchronized void publish(LogRecord record) {
        if (!isLoggable(record)) {
            return;
        }
        try {
            String message = formatter.format(record);
            byte[] bytes = message.getBytes(StandardCharsets.UTF_8);
            // If for some reason outputStream is null, open it.
            if (outputStream == null) {
                openOutputStream(false);
            }
            outputStream.write(bytes);
            outputStream.flush();
            if (currentFile.length() >= fileLimit) {
                rollover();
            }
        } catch (IOException ex) {
            reportError(null, ex, ErrorManager.WRITE_FAILURE);
        }
    }

    private void rollover() throws IOException {
        // Safely close the current output stream if it is not null.
        if (outputStream != null) {
            outputStream.close();
            outputStream = null;
        }

        // Shift older log files upward.
        // For example, if fileCount is 30:
        // If DataProvider.log.29 exists, delete it.
        // Then rename DataProvider.log.28 to DataProvider.log.29, etc.
        for (int i = fileCount - 1; i >= 1; i--) {
            File source = new File(logsFolder, baseFileName + "." + i);
            File target = new File(logsFolder, baseFileName + "." + (i + 1));
            if (source.exists()) {
                if (i == fileCount - 1) {
                    source.delete();
                } else {
                    Files.move(source.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING);
                }
            }
        }

        // Rename the current active log file (DataProvider.log) to DataProvider.log.1.
        File rolledFile = new File(logsFolder, baseFileName + ".1");
        Files.move(currentFile.toPath(), rolledFile.toPath(), StandardCopyOption.REPLACE_EXISTING);

        // Open a new active log file (with the same base file name, no suffix).
        openOutputStream(false);
    }

    @Override
    public synchronized void flush() {
        try {
            if (outputStream != null) {
                outputStream.flush();
            }
        } catch (IOException ex) {
            reportError(null, ex, ErrorManager.FLUSH_FAILURE);
        }
    }

    @Override
    public synchronized void close() throws SecurityException {
        try {
            flush();
            if (outputStream != null) {
                outputStream.close();
            }
        } catch (IOException ex) {
            reportError(null, ex, ErrorManager.CLOSE_FAILURE);
        }
    }
}
