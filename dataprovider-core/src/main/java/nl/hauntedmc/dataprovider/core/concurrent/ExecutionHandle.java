package nl.hauntedmc.dataprovider.core.concurrent;

import java.util.concurrent.Executor;

/** Connection-scoped execution handle backed by the shared runtime. */
public interface ExecutionHandle extends Executor, AutoCloseable {

    ExecutionMetricsSnapshot metrics();

    boolean isClosed();

    default boolean tryAcquireSubscription() {
        return true;
    }

    default void releaseSubscription() {
    }

    default void recordDroppedMessages(long count) {
    }

    @Override
    void close();

    static ExecutionHandle direct() {
        return DirectExecutionHandle.INSTANCE;
    }

    final class DirectExecutionHandle implements ExecutionHandle {
        private static final DirectExecutionHandle INSTANCE = new DirectExecutionHandle();

        private DirectExecutionHandle() {
        }

        @Override
        public void execute(Runnable command) {
            command.run();
        }

        @Override
        public ExecutionMetricsSnapshot metrics() {
            return new ExecutionMetricsSnapshot(0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0);
        }

        @Override
        public boolean isClosed() {
            return false;
        }

        @Override
        public void close() {
        }
    }
}
