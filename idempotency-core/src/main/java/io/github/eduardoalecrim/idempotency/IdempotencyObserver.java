// SPDX-License-Identifier: Apache-2.0
package io.github.eduardoalecrim.idempotency;

import java.time.Duration;

/** Receives what the engine does, for metrics and tracing. Implementations must be thread-safe and fast. */
public interface IdempotencyObserver {

    /** An observer that ignores everything. */
    IdempotencyObserver NOOP = new IdempotencyObserver() {};

    /** A request was handled; {@code elapsed} runs from {@code begin} to the final decision or {@code finish}. */
    default void onOutcome(Outcome outcome, RouteRef route, Duration elapsed) {}

    /** A store call returned or threw. */
    default void onStoreCall(StoreOperation operation, Duration elapsed, boolean success) {}

    /** A completion was refused because another request took over the record. */
    default void onLeaseLost(RouteRef route) {}

    /** A storable response was larger than the response limit. */
    default void onResponseOversized(RouteRef route) {}
}
