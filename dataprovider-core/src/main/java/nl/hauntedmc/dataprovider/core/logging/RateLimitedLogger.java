package nl.hauntedmc.dataprovider.core.logging;

import nl.hauntedmc.dataprovider.logging.LoggerAdapter;

import java.time.Duration;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.LongSupplier;

/** Thread-safe, monotonic rate limiter for repeated backend outage messages. */
public final class RateLimitedLogger {

    private final long intervalNanos;
    private final LongSupplier nanoTime;
    private final AtomicLong nextPermittedNanos = new AtomicLong(Long.MIN_VALUE);

    public RateLimitedLogger(Duration interval) {
        this(interval, System::nanoTime);
    }

    RateLimitedLogger(Duration interval, LongSupplier nanoTime) {
        Objects.requireNonNull(interval, "interval");
        this.nanoTime = Objects.requireNonNull(nanoTime, "nanoTime");
        if (interval.isNegative() || interval.isZero()) {
            throw new IllegalArgumentException("Log interval must be positive.");
        }
        intervalNanos = toNanosSaturated(interval);
    }

    /** Logs at most once per interval without retaining exceptions or sensitive connection data. */
    public void error(LoggerAdapter logger, String message) {
        Objects.requireNonNull(logger, "logger");
        Objects.requireNonNull(message, "message");
        long now = nanoTime.getAsLong();
        while (true) {
            long permitted = nextPermittedNanos.get();
            if (now < permitted) {
                return;
            }
            long next = saturatedAdd(now, intervalNanos);
            if (nextPermittedNanos.compareAndSet(permitted, next)) {
                logger.error(message);
                return;
            }
        }
    }

    private static long saturatedAdd(long left, long right) {
        if (right > 0 && left > Long.MAX_VALUE - right) {
            return Long.MAX_VALUE;
        }
        return left + right;
    }

    private static long toNanosSaturated(Duration duration) {
        try {
            return duration.toNanos();
        } catch (ArithmeticException ignored) {
            return Long.MAX_VALUE;
        }
    }
}
