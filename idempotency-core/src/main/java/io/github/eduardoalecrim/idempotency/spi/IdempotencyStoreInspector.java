// SPDX-License-Identifier: Apache-2.0
package io.github.eduardoalecrim.idempotency.spi;

import java.util.List;

/** Optional store capability that lists recent records for development tools. */
public interface IdempotencyStoreInspector {

    /** Returns up to {@code limit} records, most recently created first. */
    List<RecordSummary> recent(int limit);
}
