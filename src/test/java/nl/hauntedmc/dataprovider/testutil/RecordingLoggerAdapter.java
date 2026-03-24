package nl.hauntedmc.dataprovider.testutil;

import nl.hauntedmc.dataprovider.platform.common.logger.ILoggerAdapter;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public final class RecordingLoggerAdapter implements ILoggerAdapter {

    private final List<String> infoMessages = Collections.synchronizedList(new ArrayList<>());
    private final List<String> warnMessages = Collections.synchronizedList(new ArrayList<>());
    private final List<String> errorMessages = Collections.synchronizedList(new ArrayList<>());

    @Override
    public void info(String message) {
        infoMessages.add(message);
    }

    @Override
    public void warn(String message) {
        warnMessages.add(message);
    }

    @Override
    public void error(String message) {
        errorMessages.add(message);
    }

    @Override
    public void info(String message, Throwable throwable) {
        infoMessages.add(message + " (" + throwable.getClass().getSimpleName() + ")");
    }

    @Override
    public void warn(String message, Throwable throwable) {
        warnMessages.add(message + " (" + throwable.getClass().getSimpleName() + ")");
    }

    @Override
    public void error(String message, Throwable throwable) {
        errorMessages.add(message + " (" + throwable.getClass().getSimpleName() + ")");
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
