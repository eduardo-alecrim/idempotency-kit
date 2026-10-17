// SPDX-License-Identifier: Apache-2.0
package io.github.eduardoalecrim.idempotency;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Marks a handler as an idempotent operation.
 *
 * <p>On a type, it applies to every handler whose method is in the configured {@code methods}; an annotation on the
 * method overrides it. Durations accept {@code 24h}, {@code 30m}, {@code 90s}, {@code 7d}, {@code 500ms} or ISO-8601
 * ({@code PT24H}).
 */
@Target({ElementType.METHOD, ElementType.TYPE})
@Retention(RetentionPolicy.RUNTIME)
@Documented
public @interface Idempotent {

    /** Whether a request without the header is rejected with 400. */
    boolean required() default true;

    /** How long a completed record is replayed; empty means the global default. */
    String ttl() default "";

    /** Lease of in-progress records; empty means the global default. Must be shorter than the TTL. */
    String lease() default "";

    /** Whether keys are separate per client identity or shared by every client. */
    Scope scope() default Scope.CLIENT;

    /** Request headers added to the fingerprint, on top of the global list. */
    String[] fingerprintHeaders() default {};

    /** Scope of the idempotency keys of an operation. */
    enum Scope {
        /** Keys are separate per client identity. */
        CLIENT,
        /** Keys are shared by every client, for server-to-server keys. */
        GLOBAL
    }
}
