// SPDX-License-Identifier: Apache-2.0
package io.github.eduardoalecrim.idempotency;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/** Records what the engine reports. */
final class RecordingObserver implements IdempotencyObserver {

    final List<Outcome> outcomes = new CopyOnWriteArrayList<>();
    final List<String> storeCalls = new CopyOnWriteArrayList<>();
    final List<RouteRef> leasesLost = new CopyOnWriteArrayList<>();
    final List<RouteRef> oversized = new CopyOnWriteArrayList<>();

    @Override
    public void onOutcome(Outcome outcome, RouteRef route, Duration elapsed) {
        outcomes.add(outcome);
    }

    @Override
    public void onStoreCall(StoreOperation operation, Duration elapsed, boolean success) {
        storeCalls.add(operation + (success ? "" : " failed"));
    }

    @Override
    public void onLeaseLost(RouteRef route) {
        leasesLost.add(route);
    }

    @Override
    public void onResponseOversized(RouteRef route) {
        oversized.add(route);
    }
}
