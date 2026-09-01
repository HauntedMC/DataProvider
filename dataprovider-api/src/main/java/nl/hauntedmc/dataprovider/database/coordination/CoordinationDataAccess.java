package nl.hauntedmc.dataprovider.database.coordination;

import nl.hauntedmc.dataprovider.database.DataAccess;

import java.time.Duration;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;

/** Atomic distributed-coordination operations backed by one authoritative server clock. */
public interface CoordinationDataAccess extends DataAccess {

    /** Acquires an unowned resource. An existing live owner is never displaced. */
    CompletableFuture<Optional<FencedLease>> acquire(String resource, String owner, Duration ttl);

    /** Claims a resource and fences any former owner by issuing a new monotonically increasing token. */
    CompletableFuture<LeaseClaim> claim(String resource, String owner, Duration ttl);

    /** Renews only the exact owner/token pair. */
    CompletableFuture<Optional<FencedLease>> renew(FencedLease lease, Duration ttl);

    /** Releases only the exact owner/token pair. */
    CompletableFuture<Boolean> release(FencedLease lease);

    /** Writes a TTL value only while the exact lease owner/token is current. */
    CompletableFuture<Boolean> writeFenced(
            FencedLease lease,
            String key,
            String value,
            Duration ttl
    );

    /** Deletes a value only while the exact lease owner/token is current. */
    CompletableFuture<Boolean> deleteFenced(FencedLease lease, String key);

    /**
     * Writes a fenced TTL value and records its key in an explicit coordination index atomically.
     */
    CompletableFuture<Boolean> writeFencedIndexed(
            FencedLease lease,
            String key,
            String value,
            Duration ttl,
            String index,
            String member
    );

    /** Deletes a fenced value and its explicit index member atomically. */
    CompletableFuture<Boolean> deleteFencedIndexed(
            FencedLease lease,
            String key,
            String index,
            String member
    );

    /**
     * Reads every live value referenced by an explicit index and prunes expired members.
     * The returned map is keyed by index member and is never capped by generic key-scan limits.
     */
    CompletableFuture<Map<String, String>> readIndexedValues(String index);

    /** Atomically replaces the expected value and applies a TTL. A null expected value means absent. */
    CompletableFuture<Boolean> compareAndSetWithTtl(
            String key,
            String expectedValue,
            String newValue,
            Duration ttl
    );

    /** Deletes only when the current value equals the expected value. */
    CompletableFuture<Boolean> compareAndDelete(String key, String expectedValue);
}
