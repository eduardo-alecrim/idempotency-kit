// SPDX-License-Identifier: Apache-2.0
package io.github.eduardoalecrim.idempotency;

/** What happens to a storable response that is larger than the response limit. */
public enum OversizedPolicy {
    /** Store the status and headers without the body; replays send an empty body. */
    STORE_WITHOUT_BODY,
    /** Release the record, so a retry runs the handler again. */
    RELEASE
}
