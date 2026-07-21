package nl.hauntedmc.dataprovider.exception;

/** Transaction stage at which a failure occurred. */
public enum TransactionPhase {
    BEGIN,
    CALLBACK,
    COMMIT,
    ROLLBACK
}
