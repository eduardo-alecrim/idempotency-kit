// SPDX-License-Identifier: Apache-2.0
package io.github.eduardoalecrim.idempotency.spi;

import java.time.Instant;
import org.jspecify.annotations.Nullable;

/**
 * A record without its response body or owner token, for diagnostics.
 *
 * @param key the scope key
 * @param parts the values the scope key was computed from
 * @param state the state
 * @param status the stored status, once completed
 * @param createdAt when the record was claimed
 * @param expiresAt when the record expires
 */
public record RecordSummary(
        ScopeKey key,
        ScopeParts parts,
        RecordState state,
        @Nullable Integer status,
        Instant createdAt,
        Instant expiresAt) {

    /** Summarizes {@code record}. */
    public static RecordSummary of(IdempotencyRecord record) {
        StoredResponse response = record.response();
        return new RecordSummary(
                record.key(),
                record.parts(),
                record.state(),
                response == null ? null : response.status(),
                record.createdAt(),
                record.expiresAt());
    }
}
