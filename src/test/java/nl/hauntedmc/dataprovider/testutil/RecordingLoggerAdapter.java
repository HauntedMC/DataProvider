package nl.hauntedmc.dataprovider.testutil;

import nl.hauntedmc.dataprovider.logging.LogLevel;
import nl.hauntedmc.dataprovider.logging.LoggerAdapter;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public final class RecordingLoggerAdapter implements LoggerAdapter {

    private final List<String> infoMessages = Collections.synchronizedList(new ArrayList<>());
    private final List<String> warnMessages = Collections.synchronizedList(new ArrayList<>());
    private final List<String> errorMessages = Collections.synchronizedList(new ArrayList<>());

    @Override
    public void log(LogLevel level, String message, Throwable throwable) {
        String rendered = throwable == null
                ? message
                : message + " (" + throwable.getClass().getSimpleName() + ")";
        switch (level) {
            case INFO -> infoMessages.add(rendered);
            case WARN -> warnMessages.add(rendered);
            case ERROR -> errorMessages.add(rendered);
        }
    }

    public List<String> infoMessages() {
        return List.copyOf(infoMessages);
    }

    public List<String> warnMessages() {
        return List.copyOf(warnMessages);
    }

    public List<String> errorMessages() {
        return List.copyOf(errorMessages);
    }
}
