// SPDX-License-Identifier: Apache-2.0
package io.github.eduardoalecrim.idempotency;

/** What happens to a request when the store fails before the handler runs. */
public enum StoreFailurePolicy {
    /** Answer 503 and do not run the handler. */
    FAIL_CLOSED,
    /** Run the handler without protection. */
    FAIL_OPEN
}
