package nl.hauntedmc.dataprovider.exception;

import java.util.Objects;

/** Transaction failure retaining the phase and primary cause. */
public final class DataTransactionException extends DataProviderException {

    private final TransactionPhase phase;

    public DataTransactionException(String message, TransactionPhase phase,
                                    DataProviderFailureContext context, Throwable cause) {
        super(DataProviderErrorCode.TRANSACTION_FAILED, message, context, cause);
        this.phase = Objects.requireNonNull(phase, "Transaction phase cannot be null.");
    }

    public TransactionPhase phase() {
        return phase;
    }
}
