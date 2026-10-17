// SPDX-License-Identifier: Apache-2.0
package io.github.eduardoalecrim.idempotency.spring.web;

import io.github.eduardoalecrim.idempotency.IdempotencyPolicy;

/**
 * An idempotent operation matched for a request.
 *
 * @param routeTemplate the route template, such as {@code /orders/{id}}
 * @param policy the operation's policy
 */
public record IdempotentOperation(String routeTemplate, IdempotencyPolicy policy) {}
