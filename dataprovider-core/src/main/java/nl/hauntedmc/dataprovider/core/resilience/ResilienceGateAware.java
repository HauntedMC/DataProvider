package nl.hauntedmc.dataprovider.core.resilience;

import nl.hauntedmc.dataprovider.core.ConnectionHealthSnapshot;
import java.util.function.BooleanSupplier;
import java.util.function.Supplier;

/** Internal bridge used to reject new logical operations while a circuit is open. */
public interface ResilienceGateAware {
    void setResilienceGate(BooleanSupplier gate, Supplier<ConnectionHealthSnapshot> diagnostics);

    /** Detaches a stopped controller without changing the provider's lifecycle state. */
    void clearResilienceGate();
}
