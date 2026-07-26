package nl.hauntedmc.dataprovider.core.resilience;

import java.util.function.BooleanSupplier;
import java.util.function.Supplier;

/** Internal bridge used to reject new logical operations while a circuit is open. */
public interface ResilienceGateAware {
    void setResilienceGate(BooleanSupplier gate, Supplier<ConnectionHealthSnapshot> diagnostics);

    /** Detaches a stopped controller without changing the provider's lifecycle state. */
    void clearResilienceGate();

    /**
     * Detaches a controller only when it still owns the installed gate.  This matters while a
     * configuration reload hands a physical resource from one runtime to another: closing the
     * retired runtime must not clear the replacement runtime's gate.
     */
    default void clearResilienceGateIfMatches(BooleanSupplier gate) {
        clearResilienceGate();
    }
}
