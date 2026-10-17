// SPDX-License-Identifier: Apache-2.0
package io.github.eduardoalecrim.idempotency.fingerprint;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.eduardoalecrim.idempotency.Idempotent.Scope;
import io.github.eduardoalecrim.idempotency.spi.ScopeKey;
import io.github.eduardoalecrim.idempotency.spi.ScopeParts;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class ScopeKeysTest {

    @Test
    void isStableHex() {
        ScopeKey key = ScopeKeys.compute(Scope.CLIENT, parts("alice", "POST", "/orders", "k1"));

        assertThat(key).isEqualTo(ScopeKeys.compute(Scope.CLIENT, parts("alice", "POST", "/orders", "k1")));
        assertThat(key.value()).hasSize(ScopeKey.LENGTH);
    }

    @Test
    @DisplayName("FR-12: the same key from two client identities gives two scope keys")
    void separatesClients() {
        assertThat(ScopeKeys.compute(Scope.CLIENT, parts("alice", "POST", "/orders", "k1")))
                .isNotEqualTo(ScopeKeys.compute(Scope.CLIENT, parts("bob", "POST", "/orders", "k1")));
    }

    @Test
    @DisplayName("FR-12: requests without an identity share the anonymous client")
    void sharesTheAnonymousClient() {
        assertThat(ScopeKeys.compute(Scope.CLIENT, parts("", "POST", "/orders", "k1")))
                .isEqualTo(ScopeKeys.compute(Scope.CLIENT, parts("", "POST", "/orders", "k1")))
                .isNotEqualTo(ScopeKeys.compute(Scope.CLIENT, parts("alice", "POST", "/orders", "k1")));
    }

    @Test
    @DisplayName("FR-12: global scope drops the client identity")
    void globalScopeIgnoresTheClient() {
        assertThat(ScopeKeys.compute(Scope.GLOBAL, parts("alice", "POST", "/orders", "k1")))
                .isEqualTo(ScopeKeys.compute(Scope.GLOBAL, parts("bob", "POST", "/orders", "k1")))
                .isNotEqualTo(ScopeKeys.compute(Scope.CLIENT, parts("", "POST", "/orders", "k1")));
    }

    @Test
    @DisplayName("FR-13: the same key on another route template or method gives another scope key")
    void separatesRoutesAndMethods() {
        ScopeKey orders = ScopeKeys.compute(Scope.CLIENT, parts("alice", "POST", "/orders", "k1"));

        assertThat(orders)
                .isNotEqualTo(ScopeKeys.compute(Scope.CLIENT, parts("alice", "POST", "/payments", "k1")))
                .isNotEqualTo(ScopeKeys.compute(Scope.CLIENT, parts("alice", "PATCH", "/orders", "k1")))
                .isNotEqualTo(ScopeKeys.compute(Scope.CLIENT, parts("alice", "POST", "/orders", "k2")));
    }

    @Test
    void lengthPrefixesKeepFieldBoundaries() {
        assertThat(ScopeKeys.compute(Scope.CLIENT, parts("ab", "POST", "/c", "d")))
                .isNotEqualTo(ScopeKeys.compute(Scope.CLIENT, parts("a", "POST", "/c", "bd")));
    }

    private static ScopeParts parts(String client, String method, String route, String key) {
        return new ScopeParts(key, client, method, route);
    }
}
