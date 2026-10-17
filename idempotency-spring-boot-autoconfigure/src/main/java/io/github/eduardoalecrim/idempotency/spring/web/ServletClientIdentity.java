// SPDX-License-Identifier: Apache-2.0
package io.github.eduardoalecrim.idempotency.spring.web;

import io.github.eduardoalecrim.idempotency.ClientIdentityResolver;
import io.github.eduardoalecrim.idempotency.spring.IdempotencyProperties;
import jakarta.servlet.http.HttpServletRequest;
import java.security.Principal;
import java.util.Objects;
import java.util.Optional;

/** The default client identity of servlet requests, from {@code idempotency.client-identity.*}. */
final class ServletClientIdentity {

    private ServletClientIdentity() {}

    /**
     * The principal source reads {@link HttpServletRequest#getUserPrincipal()}, which Spring Security fills from its
     * {@code SecurityContext} for authenticated, non-anonymous requests.
     */
    static ClientIdentityResolver<HttpServletRequest> from(IdempotencyProperties.ClientIdentity properties) {
        return switch (properties.getSource()) {
            case PRINCIPAL ->
                request -> Optional.ofNullable(request.getUserPrincipal()).map(Principal::getName);
            case HEADER -> {
                String header = Objects.requireNonNull(properties.getHeader());
                yield request -> Optional.ofNullable(request.getHeader(header)).map(String::trim);
            }
            case NONE -> request -> Optional.empty();
        };
    }
}
