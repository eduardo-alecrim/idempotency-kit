// SPDX-License-Identifier: Apache-2.0
package io.github.eduardoalecrim.idempotency.spi;

/**
 * Result of {@link IdempotencyStore#health()}.
 *
 * @param up whether the store answered
 * @param detail a short description, without secrets
 */
public record StoreHealth(boolean up, String detail) {

    /** Returns a healthy result. */
    public static StoreHealth up(String detail) {
        return new StoreHealth(true, detail);
    }

    /** Returns an unhealthy result. */
    public static StoreHealth down(String detail) {
        return new StoreHealth(false, detail);
    }
}
