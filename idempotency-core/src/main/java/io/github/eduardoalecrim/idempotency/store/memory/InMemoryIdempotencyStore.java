// SPDX-License-Identifier: Apache-2.0
package io.github.eduardoalecrim.idempotency.store.memory;

import io.github.eduardoalecrim.idempotency.spi.ClaimRequest;
import io.github.eduardoalecrim.idempotency.spi.ClaimResult;
import io.github.eduardoalecrim.idempotency.spi.IdempotencyRecord;
import io.github.eduardoalecrim.idempotency.spi.IdempotencyStore;
import io.github.eduardoalecrim.idempotency.spi.IdempotencyStoreException;
import io.github.eduardoalecrim.idempotency.spi.IdempotencyStoreInspector;
import io.github.eduardoalecrim.idempotency.spi.OwnerToken;
import io.github.eduardoalecrim.idempotency.spi.RecordSummary;
import io.github.eduardoalecrim.idempotency.spi.ScopeKey;
import io.github.eduardoalecrim.idempotency.spi.StoreHealth;
import io.github.eduardoalecrim.idempotency.spi.StoredResponse;
import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.jspecify.annotations.Nullable;

/**
 * Keeps records in a {@link ConcurrentHashMap}, so every operation is atomic per scope key without locks held across
 * calls.
 *
 * <p>Records are not shared between JVMs. When {@code maxEntries} live records exist, a claim for a new scope key first
 * deletes expired records and then fails with {@link IdempotencyStoreException}; live records are never evicted.
 */
public final class InMemoryIdempotencyStore implements IdempotencyStore, IdempotencyStoreInspector {

    /** Default upper bound of records. */
    public static final int DEFAULT_MAX_ENTRIES = 10_000;

    private static final System.Logger LOG = System.getLogger(InMemoryIdempotencyStore.class.getName());

    private final Map<ScopeKey, IdempotencyRecord> records = new ConcurrentHashMap<>();
    private final AtomicInteger size = new AtomicInteger();
    private final int maxEntries;

    /** Creates a store bounded by {@link #DEFAULT_MAX_ENTRIES}. */
    public InMemoryIdempotencyStore() {
        this(DEFAULT_MAX_ENTRIES);
    }

    /** @throws IllegalArgumentException if {@code maxEntries} is not positive */
    public InMemoryIdempotencyStore(int maxEntries) {
        if (maxEntries < 1) {
            throw new IllegalArgumentException("maxEntries must be positive: " + maxEntries);
        }
        this.maxEntries = maxEntries;
        LOG.log(
                System.Logger.Level.WARNING,
                "The in-memory idempotency store is not shared between instances."
                        + " Use the JDBC or Redis store when the application runs more than one instance.");
    }

    @Override
    public ClaimResult claim(ClaimRequest request) {
        ClaimResult result = tryClaim(request);
        if (result != null) {
            return result;
        }
        removeExpired(request.now());
        result = tryClaim(request);
        if (result != null) {
            return result;
        }
        throw new IdempotencyStoreException("The in-memory store holds " + maxEntries + " live records and is full");
    }

    // Returns null when the key is absent and the store is full.
    private @Nullable ClaimResult tryClaim(ClaimRequest request) {
        OwnerToken token = OwnerToken.random();
        AtomicReference<@Nullable ClaimResult> result = new AtomicReference<>();
        records.compute(request.key(), (key, existing) -> {
            if (existing != null && !existing.isExpired(request.now())) {
                result.set(new ClaimResult.Existing(existing));
                return existing;
            }
            if (existing == null && size.incrementAndGet() > maxEntries) {
                size.decrementAndGet();
                return null;
            }
            result.set(new ClaimResult.Acquired(token));
            return request.toRecord(token);
        });
        return result.get();
    }

    @Override
    public boolean complete(
            ScopeKey key, OwnerToken token, StoredResponse response, Instant completedAt, Instant expiresAt) {
        AtomicBoolean completed = new AtomicBoolean();
        records.computeIfPresent(key, (k, record) -> {
            if (!record.isOwnedBy(token)) {
                return record;
            }
            completed.set(true);
            return record.complete(response, completedAt, expiresAt);
        });
        return completed.get();
    }

    @Override
    public void release(ScopeKey key, OwnerToken token) {
        records.computeIfPresent(key, (k, record) -> {
            if (!record.isOwnedBy(token)) {
                return record;
            }
            size.decrementAndGet();
            return null;
        });
    }

    @Override
    public Optional<IdempotencyRecord> find(ScopeKey key, Instant now) {
        IdempotencyRecord record = records.get(key);
        return record == null || record.isExpired(now) ? Optional.empty() : Optional.of(record);
    }

    @Override
    public int purgeExpired(Instant now, int batchSize) {
        AtomicInteger purged = new AtomicInteger();
        records.forEach((key, record) -> {
            if (purged.get() < batchSize && record.expiresAt().isBefore(now) && records.remove(key, record)) {
                size.decrementAndGet();
                purged.incrementAndGet();
            }
        });
        return purged.get();
    }

    // Expired records count as absent, so a full store can drop them even before their TTL ends.
    private void removeExpired(Instant now) {
        records.forEach((key, record) -> {
            if (record.isExpired(now) && records.remove(key, record)) {
                size.decrementAndGet();
            }
        });
    }

    @Override
    public StoreHealth health() {
        return StoreHealth.up("in-memory, " + size.get() + " of " + maxEntries + " records");
    }

    @Override
    public List<RecordSummary> recent(int limit) {
        return records.values().stream()
                .sorted(Comparator.comparing(IdempotencyRecord::createdAt).reversed())
                .limit(limit)
                .map(RecordSummary::of)
                .toList();
    }
}
