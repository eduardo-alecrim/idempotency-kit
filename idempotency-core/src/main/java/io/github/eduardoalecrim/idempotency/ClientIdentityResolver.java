// SPDX-License-Identifier: Apache-2.0
package io.github.eduardoalecrim.idempotency;

import java.util.Optional;

/**
 * Resolves who sent a request. An application bean of this type replaces the adapter's default resolution.
 *
 * @param <R> the framework's request type
 */
@FunctionalInterface
public interface ClientIdentityResolver<R> {

    /** Returns the client identity, or empty for the anonymous client. */
    Optional<String> resolve(R request);
}
