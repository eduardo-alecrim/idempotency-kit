// SPDX-License-Identifier: Apache-2.0
package io.github.eduardoalecrim.idempotency.tck;

import io.github.eduardoalecrim.idempotency.spi.IdempotencyStore;
import io.github.eduardoalecrim.idempotency.store.memory.InMemoryIdempotencyStore;
import java.time.Clock;

class InMemoryStoreContractTest extends IdempotencyStoreContract {

    @Override
    protected IdempotencyStore createStore(Clock clock) {
        return new InMemoryIdempotencyStore();
    }
}
