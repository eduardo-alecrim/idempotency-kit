// SPDX-License-Identifier: Apache-2.0
package io.github.eduardoalecrim.idempotency;

import java.util.BitSet;
import java.util.List;
import java.util.Locale;
import org.jspecify.annotations.Nullable;

/**
 * Decides which response statuses are stored. A status is storable when it matches the stored list and not the
 * released list; every other status releases the record (ADR 0009).
 */
public final class StatusPolicy {

    /** Stores {@code 2xx} and {@code 4xx}, except {@code 408}, {@code 425} and {@code 429}. */
    public static final StatusPolicy DEFAULT = of(List.of("2xx", "4xx"), List.of("408", "425", "429"));

    private final BitSet storable;
    private final List<String> storeStatuses;
    private final List<String> releaseStatuses;

    private StatusPolicy(BitSet storable, List<String> storeStatuses, List<String> releaseStatuses) {
        this.storable = storable;
        this.storeStatuses = storeStatuses;
        this.releaseStatuses = releaseStatuses;
    }

    /**
     * Builds a policy from status classes ({@code 2xx}) and codes ({@code 201}).
     *
     * @throws IllegalArgumentException if an entry is malformed, or the stored list includes a {@code 5xx} status
     */
    public static StatusPolicy of(List<String> storeStatuses, List<String> releaseStatuses) {
        BitSet storable = new BitSet(600);
        for (String entry : storeStatuses) {
            int[] range = range(entry);
            if (range[1] >= 500) {
                throw new IllegalArgumentException("5xx statuses are transient and cannot be stored: " + entry);
            }
            storable.set(range[0], range[1] + 1);
        }
        for (String entry : releaseStatuses) {
            int[] range = range(entry);
            storable.clear(range[0], range[1] + 1);
        }
        return new StatusPolicy(storable, List.copyOf(storeStatuses), List.copyOf(releaseStatuses));
    }

    /** Whether a response with {@code status} is stored. */
    public boolean isStorable(int status) {
        return status >= 0 && status < 600 && storable.get(status);
    }

    /** Returns the stored statuses as configured. */
    public List<String> storeStatuses() {
        return storeStatuses;
    }

    /** Returns the released statuses as configured. */
    public List<String> releaseStatuses() {
        return releaseStatuses;
    }

    private static int[] range(String entry) {
        String value = entry.trim().toLowerCase(Locale.ROOT);
        if (value.matches("[1-5]xx")) {
            int base = (value.charAt(0) - '0') * 100;
            return new int[] {base, base + 99};
        }
        if (value.matches("[1-5][0-9][0-9]")) {
            int code = Integer.parseInt(value);
            return new int[] {code, code};
        }
        throw new IllegalArgumentException(
                "Invalid status '" + entry + "': use a class such as 2xx or a code such as 201");
    }

    @Override
    public boolean equals(@Nullable Object o) {
        return o instanceof StatusPolicy other && storable.equals(other.storable);
    }

    @Override
    public int hashCode() {
        return storable.hashCode();
    }

    @Override
    public String toString() {
        return "store " + String.join(",", storeStatuses) + ", release " + String.join(",", releaseStatuses);
    }
}
