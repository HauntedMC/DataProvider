package nl.hauntedmc.dataprovider.internal.concurrent;

import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Creates bounded executors to prevent unbounded task queue growth.
 */
public final class BoundedExecutorFactory {

    private BoundedExecutorFactory() {
    }

    public static ExecutorService create(String threadPrefix, int poolSize, int queueCapacity) {
        if (threadPrefix == null || threadPrefix.isBlank()) {
            throw new IllegalArgumentException("Thread prefix cannot be null or blank.");
        }
        if (poolSize < 1) {
            throw new IllegalArgumentException("Pool size must be at least 1.");
        }
        if (queueCapacity < 1) {
            throw new IllegalArgumentException("Queue capacity must be at least 1.");
        }

        BlockingQueue<Runnable> queue = new LinkedBlockingQueue<>(queueCapacity);
        ThreadFactory threadFactory = new NamedDaemonThreadFactory(threadPrefix);

        return new ThreadPoolExecutor(
                poolSize,
                poolSize,
                30L,
                TimeUnit.SECONDS,
                queue,
                threadFactory,
                (task, rejectedFromExecutor) -> {
                    throw new RejectedExecutionException("Task queue is full for executor '" + threadPrefix + "'.");
                }
        );
    }

    private static final class NamedDaemonThreadFactory implements ThreadFactory {
        private final String threadPrefix;
        private final AtomicInteger sequence = new AtomicInteger(1);

        private NamedDaemonThreadFactory(String threadPrefix) {
            this.threadPrefix = threadPrefix;
        }

        @Override
        public Thread newThread(Runnable task) {
            Thread thread = new Thread(task, threadPrefix + "-" + sequence.getAndIncrement());
            thread.setDaemon(true);
            return thread;
        }
    }
}
