// SPDX-License-Identifier: Apache-2.0
/**
 * Store SPI and the values that cross it.
 *
 * <p>Stores receive computed values only: they never parse keys or bodies, and they decide expiry from the {@code now}
 * passed by the engine, not from their own clock.
 */
@NullMarked
package io.github.eduardoalecrim.idempotency.spi;

import org.jspecify.annotations.NullMarked;
