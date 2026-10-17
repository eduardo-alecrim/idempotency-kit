// SPDX-License-Identifier: Apache-2.0
package io.github.eduardoalecrim.idempotency;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

import java.time.Duration;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class IdempotencyPolicyTest {

    @Test
    void hasTheDocumentedDefaults() {
        IdempotencyPolicy policy = IdempotencyPolicy.builder().build();

        assertThat(policy.required()).isTrue();
        assertThat(policy.ttl()).isEqualTo(Duration.ofHours(24));
        assertThat(policy.lease()).isEqualTo(Duration.ofSeconds(30));
        assertThat(policy.scope()).isEqualTo(Idempotent.Scope.CLIENT);
        assertThat(policy.replayHeaders()).isEqualTo(IdempotencyPolicy.DEFAULT_REPLAY_HEADERS);
        assertThat(policy.maxRequestBytes()).isEqualTo(1024 * 1024);
        assertThat(policy.oversized()).isEqualTo(OversizedPolicy.STORE_WITHOUT_BODY);
        assertThat(policy.toBuilder().build()).isEqualTo(policy);
    }

    @Test
    @DisplayName("a policy with lease >= ttl is rejected")
    void rejectsLeaseNotShorterThanTtl() {
        assertThatIllegalArgumentException()
                .isThrownBy(() ->
                        IdempotencyPolicy.builder().ttl(Duration.ofSeconds(30)).build())
                .withMessageContaining("must be shorter than ttl");
        assertThatIllegalArgumentException()
                .isThrownBy(() ->
                        IdempotencyPolicy.builder().ttl(Duration.ofSeconds(10)).build());
    }

    @Test
    void rejectsNonPositiveDurationsAndLimitsOutOfRange() {
        assertThatIllegalArgumentException()
                .isThrownBy(
                        () -> IdempotencyPolicy.builder().lease(Duration.ZERO).build());
        assertThatIllegalArgumentException()
                .isThrownBy(() ->
                        IdempotencyPolicy.builder().ttl(Duration.ofSeconds(-1)).build());
        assertThatIllegalArgumentException()
                .isThrownBy(
                        () -> IdempotencyPolicy.builder().maxRequestBytes(-1).build());
        assertThatIllegalArgumentException()
                .isThrownBy(() -> IdempotencyPolicy.builder()
                        .maxResponseBytes(Integer.MAX_VALUE)
                        .build());
    }

    @ParameterizedTest
    @ValueSource(strings = {"Set-Cookie", "authorization", "WWW-Authenticate", "Transfer-Encoding", "Content-Length"})
    @DisplayName("FR-37: denied headers cannot be configured for replay")
    void rejectsDeniedReplayHeaders(String header) {
        assertThatIllegalArgumentException()
                .isThrownBy(() -> IdempotencyPolicy.builder()
                        .replayHeaders(Set.of(header))
                        .build())
                .withMessageContaining("must never be replayed");
    }

    @Test
    void lowercasesAndDeduplicatesHeaderNames() {
        IdempotencyPolicy policy = IdempotencyPolicy.builder()
                .fingerprintHeaders(List.of("X-Tenant", " x-tenant ", "X-Region"))
                .replayHeaders(Set.of("ETag"))
                .build();

        assertThat(policy.fingerprintHeaders()).containsExactly("x-tenant", "x-region");
        assertThat(policy.replayHeaders()).containsExactly("etag");
    }

    @Test
    void statusPolicyStoresTwoAndFourHundredsExceptTransientOnes() {
        StatusPolicy policy = StatusPolicy.DEFAULT;

        assertThat(policy.isStorable(200)).isTrue();
        assertThat(policy.isStorable(201)).isTrue();
        assertThat(policy.isStorable(422)).isTrue();
        assertThat(policy.isStorable(408)).isFalse();
        assertThat(policy.isStorable(425)).isFalse();
        assertThat(policy.isStorable(429)).isFalse();
        assertThat(policy.isStorable(302)).isFalse();
        assertThat(policy.isStorable(500)).isFalse();
        assertThat(policy.isStorable(-1)).isFalse();
        assertThat(policy.isStorable(600)).isFalse();
        assertThat(policy).hasToString("store 2xx,4xx, release 408,425,429");
    }

    @Test
    void statusPolicyAcceptsCodesAndRejectsTransientOrMalformedEntries() {
        StatusPolicy policy = StatusPolicy.of(List.of("201", "3xx"), List.of("304"));

        assertThat(policy.isStorable(201)).isTrue();
        assertThat(policy.isStorable(200)).isFalse();
        assertThat(policy.isStorable(303)).isTrue();
        assertThat(policy.isStorable(304)).isFalse();
        assertThat(policy.storeStatuses()).containsExactly("201", "3xx");
        assertThat(policy.releaseStatuses()).containsExactly("304");
        assertThat(policy).isEqualTo(StatusPolicy.of(List.of("201", "3XX"), List.of("304")));
        assertThat(policy).hasSameHashCodeAs(StatusPolicy.of(List.of("201", "3XX"), List.of("304")));
        assertThat(policy).isNotEqualTo(StatusPolicy.DEFAULT);

        assertThatIllegalArgumentException().isThrownBy(() -> StatusPolicy.of(List.of("5xx"), List.of()));
        assertThatIllegalArgumentException().isThrownBy(() -> StatusPolicy.of(List.of("503"), List.of()));
        assertThatIllegalArgumentException()
                .isThrownBy(() -> StatusPolicy.of(List.of("20x"), List.of()))
                .withMessageStartingWith("Invalid status '20x'");
        assertThatIllegalArgumentException().isThrownBy(() -> StatusPolicy.of(List.of("2xx"), List.of("600")));
    }
}
