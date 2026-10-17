// SPDX-License-Identifier: Apache-2.0
package io.github.eduardoalecrim.idempotency;

/**
 * An idempotent operation, as reported to observers.
 *
 * @param method the HTTP method
 * @param routeTemplate the route template
 */
public record RouteRef(String method, String routeTemplate) {

    @Override
    public String toString() {
        return method + " " + routeTemplate;
    }
}
