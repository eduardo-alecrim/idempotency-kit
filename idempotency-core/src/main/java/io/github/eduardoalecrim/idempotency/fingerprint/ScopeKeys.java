// SPDX-License-Identifier: Apache-2.0
package io.github.eduardoalecrim.idempotency.fingerprint;

import io.github.eduardoalecrim.idempotency.Idempotent.Scope;
import io.github.eduardoalecrim.idempotency.internal.LengthPrefixedDigest;
import io.github.eduardoalecrim.idempotency.spi.ScopeKey;
import io.github.eduardoalecrim.idempotency.spi.ScopeParts;

/** Computes scope keys (ADR 0007). */
public final class ScopeKeys {

    private ScopeKeys() {}

    /**
     * Returns the SHA-256 of the length-prefixed scope, client identity, method, route template and idempotency key.
     * Global scope leaves out the client identity; an empty one is the anonymous client.
     */
    public static ScopeKey compute(Scope scope, ScopeParts parts) {
        LengthPrefixedDigest digest = new LengthPrefixedDigest().add(scope.name());
        if (scope == Scope.CLIENT) {
            digest.add(parts.clientId());
        }
        return new ScopeKey(digest.add(parts.method())
                .add(parts.route())
                .add(parts.idempotencyKey())
                .hex());
    }
}
