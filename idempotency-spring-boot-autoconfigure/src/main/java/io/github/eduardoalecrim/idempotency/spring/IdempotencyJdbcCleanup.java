// SPDX-License-Identifier: Apache-2.0
package io.github.eduardoalecrim.idempotency.spring;

import io.github.eduardoalecrim.idempotency.spi.IdempotencyStore;
import java.lang.System.Logger.Level;
import java.time.Clock;
import java.time.Duration;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import org.jspecify.annotations.Nullable;
import org.springframework.context.SmartLifecycle;

/**
 * Deletes expired records every interval, in batches, on a daemon thread of its own (FR-26). Several instances may
 * purge one table at once. A failed purge is logged and tried again at the next interval.
 */
final class IdempotencyJdbcCleanup implements SmartLifecycle {

    private static final System.Logger LOG = System.getLogger(IdempotencyJdbcCleanup.class.getName());

    private final IdempotencyStore store;
    private final Clock clock;
    private final Duration interval;
    private final int batchSize;
    private @Nullable ScheduledExecutorService executor;
    private @Nullable Future<?> schedule;

    IdempotencyJdbcCleanup(IdempotencyStore store, Clock clock, Duration interval, int batchSize) {
        this.store = store;
        this.clock = clock;
        this.interval = interval;
        this.batchSize = batchSize;
    }

    /** Deletes expired records until a batch deletes fewer rows than the batch size; returns how many. */
    int purge() {
        int total = 0;
        try {
            int deleted;
            do {
                deleted = store.purgeExpired(clock.instant(), batchSize);
                total += deleted;
            } while (deleted == batchSize);
            if (total > 0) {
                LOG.log(Level.DEBUG, "Purged {0} expired idempotency records", total);
            }
        } catch (RuntimeException e) {
            LOG.log(Level.WARNING, "Idempotency record purge failed; retrying in " + interval, e);
        }
        return total;
    }

    @Override
    public synchronized void start() {
        if (executor != null) {
            return;
        }
        ScheduledExecutorService started = Executors.newSingleThreadScheduledExecutor(task -> {
            Thread thread = new Thread(task, "idempotency-cleanup");
            thread.setDaemon(true);
            return thread;
        });
        long millis = interval.toMillis();
        schedule = started.scheduleWithFixedDelay(this::purge, millis, millis, TimeUnit.MILLISECONDS);
        executor = started;
    }

    @Override
    public synchronized void stop() {
        ScheduledExecutorService running = executor;
        Future<?> scheduled = schedule;
        if (scheduled != null) {
            scheduled.cancel(true);
            schedule = null;
        }
        if (running != null) {
            running.shutdownNow();
            executor = null;
        }
    }

    @Override
    public synchronized boolean isRunning() {
        return executor != null;
    }
}
