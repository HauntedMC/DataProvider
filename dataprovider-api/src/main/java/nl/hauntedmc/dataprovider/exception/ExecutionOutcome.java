package nl.hauntedmc.dataprovider.exception;

/** What is known about whether a failed operation reached the backend. */
public enum ExecutionOutcome {
    NOT_STARTED,
    NOT_APPLIED,
    MAY_HAVE_APPLIED,
    UNKNOWN
}
