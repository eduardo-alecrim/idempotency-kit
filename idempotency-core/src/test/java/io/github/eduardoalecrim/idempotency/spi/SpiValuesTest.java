// SPDX-License-Identifier: Apache-2.0
package io.github.eduardoalecrim.idempotency.spi;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

import io.github.eduardoalecrim.idempotency.internal.Hex;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class SpiValuesTest {

    private static final String HEX_64 = "a".repeat(64);

    @Test
    void scopeKeyAndFingerprintAcceptLowercaseHex() {
        assertThat(new ScopeKey(HEX_64).value()).isEqualTo(HEX_64);
        assertThat(new Fingerprint(HEX_64)).hasToString(HEX_64);
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "abc", "g" + "a", "AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA"})
    void scopeKeyAndFingerprintRejectOtherValues(String value) {
        assertThatIllegalArgumentException().isThrownBy(() -> new ScopeKey(value));
        assertThatIllegalArgumentException().isThrownBy(() -> new Fingerprint(value));
    }

    @Test
    void scopeKeyRejectsNonHexCharacterAtTheRightLength() {
        assertThatIllegalArgumentException().isThrownBy(() -> new ScopeKey("z".repeat(64)));
    }

    @Test
    void ownerTokenIsACanonicalUuid() {
        OwnerToken token = OwnerToken.random();
        assertThat(token.value()).hasSize(OwnerToken.LENGTH);
        assertThat(new OwnerToken(token.value())).isEqualTo(token).hasToString(token.value());
        assertThat(OwnerToken.random()).isNotEqualTo(token);
    }

    @ParameterizedTest
    @ValueSource(
            strings = {"", "not-a-uuid", "4A1C7D9E-5F3B-4E8A-9C21-7B0D3E6F8A12", "4a1c7d9e5f3b4e8a9c217b0d3e6f8a12"})
    void ownerTokenRejectsOtherValues(String value) {
        assertThatIllegalArgumentException().isThrownBy(() -> new OwnerToken(value));
    }

    @Test
    void scopePartsValidateMethodRouteAndKey() {
        ScopeParts parts = new ScopeParts("k", "", "POST", "/orders");
        assertThat(parts.clientId()).isEmpty();

        assertThatIllegalArgumentException().isThrownBy(() -> new ScopeParts("", "c", "POST", "/orders"));
        assertThatIllegalArgumentException().isThrownBy(() -> new ScopeParts("k", "c", "post", "/orders"));
        assertThatIllegalArgumentException().isThrownBy(() -> new ScopeParts("k", "c", "POST", "orders"));
    }

    @Test
    void scopePartsKeepKeyAndClientOutOfToString() {
        String text = new ScopeParts("secret-key", "alice", "POST", "/orders").toString();
        assertThat(text).contains("POST", "/orders").doesNotContain("secret-key", "alice");
    }

    @Test
    void storeHealthFactories() {
        assertThat(StoreHealth.up("ok").up()).isTrue();
        assertThat(StoreHealth.down("timeout")).isEqualTo(new StoreHealth(false, "timeout"));
    }

    @Test
    void storeExceptionKeepsCause() {
        IllegalStateException cause = new IllegalStateException("boom");
        assertThat(new IdempotencyStoreException("failed", cause))
                .hasMessage("failed")
                .hasCause(cause);
        assertThat(new IdempotencyStoreException("failed")).hasNoCause();
    }

    @Test
    void hexEncodesLowercase() {
        assertThat(Hex.encode(new byte[] {0, 15, (byte) 0xAB, (byte) 0xFF})).isEqualTo("000fabff");
        assertThat(Hex.isLowerHex("000fabff", 8)).isTrue();
        assertThat(Hex.isLowerHex("000fabff", 7)).isFalse();
    }
}
