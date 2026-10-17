// SPDX-License-Identifier: Apache-2.0
package io.github.eduardoalecrim.idempotency.tck;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;

/** A UTC clock that moves only when a test advances it. Thread-safe. */
public final class MutableClock extends Clock {

    private volatile Instant now;

    /** Creates a clock stopped at {@code now}. */
    public MutableClock(Instant now) {
        this.now = now;
    }

    /** Moves the clock forward by {@code duration}. */
    public void advance(Duration duration) {
        now = now.plus(duration);
    }

    @Override
    public ZoneId getZone() {
        return ZoneOffset.UTC;
    }

    /** Returns this clock; the zone does not change instants. */
    @Override
    public Clock withZone(ZoneId zone) {
        return this;
    }

    @Override
    public Instant instant() {
        return now;
    }
}
