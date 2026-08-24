package nl.hauntedmc.dataprovider.core.api;

import nl.hauntedmc.dataprovider.api.observation.DataProviderObserver;
import nl.hauntedmc.dataprovider.api.observation.DataProviderOperationContext;
import nl.hauntedmc.dataprovider.api.orm.ORMContext;

import java.util.Objects;

/** Keeps ORM transaction observation outside the public ORM contract. */
final class ObservedOrmContext implements ORMContext {

    private final ORMContext delegate;
    private final DataProviderObserver observer;
    private final DataProviderOperationContext operationContext;

    ObservedOrmContext(
            ORMContext delegate,
            DataProviderObserver observer,
            DataProviderOperationContext operationContext
    ) {
        this.delegate = Objects.requireNonNull(delegate, "ORM context cannot be null.");
        this.observer = Objects.requireNonNull(observer, "DataProvider observer cannot be null.");
        this.operationContext = Objects.requireNonNull(operationContext, "Operation context cannot be null.");
    }

    @Override
    public <T> T runInTransaction(TransactionCallback<T> callback) {
        Objects.requireNonNull(callback, "Transaction callback cannot be null.");
        return DataProviderObservations.observe(
                observer,
                operationContext,
                () -> delegate.runInTransaction(callback)
        );
    }

    @Override
    public void shutdown() {
        delegate.shutdown();
    }
}
