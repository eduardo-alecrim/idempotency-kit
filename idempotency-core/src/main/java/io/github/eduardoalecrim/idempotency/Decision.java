// SPDX-License-Identifier: Apache-2.0
package io.github.eduardoalecrim.idempotency;

import io.github.eduardoalecrim.idempotency.problem.Problem;
import io.github.eduardoalecrim.idempotency.spi.OwnerToken;
import io.github.eduardoalecrim.idempotency.spi.ScopeKey;
import io.github.eduardoalecrim.idempotency.spi.StoredResponse;
import java.time.Instant;
import java.util.Map;

/** What the adapter must do with a request, as returned by {@link IdempotencyEngine#begin}. */
public sealed interface Decision {

    /**
     * Run the handler without protection, and do not call {@code finish}.
     *
     * @param reason why
     */
    record Bypass(BypassReason reason) implements Decision {}

    /**
     * Run the handler, then call {@link IdempotencyEngine#finish} with its response or {@link IdempotencyEngine#abort}
     * when it throws.
     *
     * @param key the claimed scope key
     * @param token the owner token of the claim
     * @param policy the operation's policy
     * @param route the operation
     * @param startedAt when {@code begin} started, from the engine's clock
     */
    record Proceed(ScopeKey key, OwnerToken token, IdempotencyPolicy policy, RouteRef route, Instant startedAt)
            implements Decision {}

    /**
     * Send the stored response, with {@code Idempotent-Replayed: true}, without running the handler.
     *
     * @param response the stored status, headers and body
     */
    record Replay(StoredResponse response) implements Decision {}

    /**
     * Send the problem with the given headers, without running the handler.
     *
     * @param problem the problem, sent as {@code application/problem+json}
     * @param headers extra response headers, such as {@code Retry-After}
     */
    record Reject(Problem problem, Map<String, String> headers) implements Decision {

        /** Copies the headers. */
        public Reject {
            headers = Map.copyOf(headers);
        }
    }
}
