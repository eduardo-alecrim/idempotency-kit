// SPDX-License-Identifier: Apache-2.0
package io.github.eduardoalecrim.idempotency.key;

import static org.assertj.core.api.Assertions.assertThat;

import net.jqwik.api.ForAll;
import net.jqwik.api.Label;
import net.jqwik.api.Property;
import net.jqwik.api.constraints.CharRange;
import net.jqwik.api.constraints.StringLength;

class IdempotencyKeyRoundTripTest {

    private final IdempotencyKeyParser parser = IdempotencyKeyParser.defaults();

    @Property
    @Label("FR-01: serializing then parsing a key returns it unchanged")
    void serializeThenParseRoundTrips(
            @ForAll @CharRange(from = ' ', to = '~') @StringLength(min = 1, max = 255) String key) {
        assertThat(parser.parse(IdempotencyKeyParser.serialize(key))).isEqualTo(new KeyParseResult.Valid(key));
    }
}
