package nl.hauntedmc.dataprovider.logger;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.logging.Formatter;
import java.util.logging.LogRecord;

class DPLogFormatter extends Formatter {

    private static final String DATE_FORMAT = "yyyy-MM-dd HH:mm:ss.SSS";
    private final SimpleDateFormat sdf = new SimpleDateFormat(DATE_FORMAT);

    @Override
    public String format(LogRecord record) {
        StringBuilder sb = new StringBuilder();
        // Timestamp
        sb.append(sdf.format(new Date(record.getMillis()))).append(" ");
        // Log level
        sb.append("[").append(record.getLevel().getName()).append("] ");
        // Plugin identifier (hard-coded as "DataProvider" or retrieved dynamically)
        sb.append("[DataProvider] - ");
        // The actual log message
        sb.append(formatMessage(record));
        sb.append(System.lineSeparator());
        return sb.toString();
    }
}
