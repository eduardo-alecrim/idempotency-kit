// SPDX-License-Identifier: Apache-2.0
package io.github.eduardoalecrim.idempotency.spi;

import java.util.regex.Pattern;

/**
 * The raw values a scope key was computed from, kept for diagnostics only.
 *
 * @param idempotencyKey the idempotency key after unescaping
 * @param clientId the client identity; empty for the anonymous client and for global scope
 * @param method the HTTP method, in uppercase
 * @param route the route template
 */
public record ScopeParts(String idempotencyKey, String clientId, String method, String route) {

    private static final Pattern METHOD = Pattern.compile("[A-Z]+");

    /** @throws IllegalArgumentException if the key or route is empty, or the method is not uppercase letters */
    public ScopeParts {
        if (idempotencyKey.isEmpty()) {
            throw new IllegalArgumentException("idempotency key must not be empty");
        }
        if (!METHOD.matcher(method).matches()) {
            throw new IllegalArgumentException("method must be uppercase letters: " + method);
        }
        if (!route.startsWith("/")) {
            throw new IllegalArgumentException("route must start with '/': " + route);
        }
    }

    // The key and client identity stay out of toString so that they do not end up in logs.
    @Override
    public String toString() {
        return "ScopeParts[method=" + method + ", route=" + route + "]";
    }
}
