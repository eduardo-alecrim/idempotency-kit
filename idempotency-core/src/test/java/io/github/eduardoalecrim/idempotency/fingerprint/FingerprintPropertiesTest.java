// SPDX-License-Identifier: Apache-2.0
package io.github.eduardoalecrim.idempotency.fingerprint;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.assertj.core.api.Assertions.assertThat;

import io.github.eduardoalecrim.idempotency.spi.Fingerprint;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.stream.Collectors;
import net.jqwik.api.ForAll;
import net.jqwik.api.Label;
import net.jqwik.api.Property;
import net.jqwik.api.constraints.AlphaChars;
import net.jqwik.api.constraints.Size;
import net.jqwik.api.constraints.StringLength;

class FingerprintPropertiesTest {

    private final FingerprintStrategy strategy = new FingerprintStrategy.Default();

    @Property
    @Label("FR-14: reordered JSON keys give the same fingerprint")
    void reorderedKeysGiveTheSameFingerprint(
            @ForAll @Size(max = 8) Map<@AlphaChars @StringLength(min = 1, max = 6) String, Long> members,
            @ForAll long seed) {
        List<Map.Entry<String, Long>> shuffled = new ArrayList<>(members.entrySet());
        Collections.shuffle(shuffled, new Random(seed));

        assertThat(fingerprint(json(new ArrayList<>(members.entrySet()), "")))
                .isEqualTo(fingerprint(json(shuffled, " \n")));
    }

    private Fingerprint fingerprint(String body) {
        return strategy.fingerprint(
                new FingerprintInput("POST", "/orders", "application/json", List.of(), body.getBytes(UTF_8)));
    }

    private static String json(List<Map.Entry<String, Long>> members, String whitespace) {
        return members.stream()
                .map(member -> "\"" + member.getKey() + "\"" + whitespace + ":" + whitespace + member.getValue())
                .collect(Collectors.joining("," + whitespace, "{" + whitespace, whitespace + "}"));
    }
}
