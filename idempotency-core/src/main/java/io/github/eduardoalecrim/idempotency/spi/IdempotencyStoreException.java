// SPDX-License-Identifier: Apache-2.0
package io.github.eduardoalecrim.idempotency.spi;

/** Thrown by a store that cannot complete an operation. */
public class IdempotencyStoreException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    /** Creates an exception with a message. */
    public IdempotencyStoreException(String message) {
        super(message);
    }

    /** Creates an exception with a message and a cause. */
    public IdempotencyStoreException(String message, Throwable cause) {
        super(message, cause);
    }
}
