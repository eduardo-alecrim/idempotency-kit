// SPDX-License-Identifier: Apache-2.0
package io.github.eduardoalecrim.idempotency.spi;

/** State of a record. */
public enum RecordState {
    /** A request holds the record under a lease and has not finished. */
    IN_PROGRESS,
    /** The request finished and its response is stored. */
    COMPLETED
}
