// SPDX-License-Identifier: Apache-2.0
package io.github.eduardoalecrim.idempotency.problem;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

import io.github.eduardoalecrim.idempotency.internal.json.JsonCanonicalizer;
import java.net.URI;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class ProblemFactoryTest {

    private static final String KEY = "4a1c7d9e-5f3b-4e8a-9c21-7b0d3e6f8a12";

    private final ProblemFactory factory = ProblemFactory.defaults();

    @Test
    @DisplayName("FR-22: a problem has type, title, status, detail, instance and idempotencyKey, and nothing else")
    void serializesKeyReuse() {
        Problem problem = factory.keyReuse("/orders", KEY);

        assertThat(json(problem))
                .isEqualTo("{\"type\":\"https://eduardo-alecrim.github.io/idempotency-kit/problems/key-reuse\","
                        + "\"title\":\"Idempotency key reused with a different request\","
                        + "\"status\":422,"
                        + "\"detail\":\"This key was already used with a different request."
                        + " Send a new key for a new request.\","
                        + "\"instance\":\"/orders\","
                        + "\"idempotencyKey\":\"" + KEY + "\"}");
        assertThat(JsonCanonicalizer.canonicalize(problem.toJson())).isPresent();
    }

    @Test
    @DisplayName("FR-22: every problem type has its status, title and type URI")
    void coversEveryType() {
        List<Problem> problems = List.of(
                factory.missingKey("/a"),
                factory.invalidKey("/a", "The key must not be empty."),
                factory.requestTooLarge("/a", 1024, KEY),
                factory.requestInProgress("/a", KEY),
                factory.keyReuse("/a", KEY),
                factory.storeUnavailable("/a", KEY));

        assertThat(problems).extracting(Problem::status).containsExactly(400, 400, 413, 409, 422, 503);
        List<ProblemType> types = List.of(
                ProblemType.MISSING_KEY,
                ProblemType.INVALID_KEY,
                ProblemType.REQUEST_TOO_LARGE,
                ProblemType.REQUEST_IN_PROGRESS,
                ProblemType.KEY_REUSE,
                ProblemType.STORE_UNAVAILABLE);
        assertThat(types).containsExactlyInAnyOrder(ProblemType.values());
        for (int i = 0; i < problems.size(); i++) {
            ProblemType type = types.get(i);
            assertThat(problems.get(i).type()).isEqualTo(ProblemFactory.DEFAULT_BASE_URI.resolve(type.slug()));
            assertThat(problems.get(i).title()).isEqualTo(type.title());
            assertThat(problems.get(i).status()).isEqualTo(type.status());
            assertThat(problems.get(i).instance()).isEqualTo("/a");
        }
    }

    @Test
    @DisplayName("FR-22: idempotencyKey is present only when the key could be parsed")
    void includesTheKeyOnlyWhenParseable() {
        assertThat(json(factory.missingKey("/a"))).doesNotContain("idempotencyKey");
        assertThat(json(factory.invalidKey("/a", "The key must not be empty."))).doesNotContain("idempotencyKey");
        assertThat(json(factory.storeUnavailable("/a", null))).doesNotContain("idempotencyKey");
        assertThat(json(factory.requestInProgress("/a", KEY))).contains("\"idempotencyKey\":\"" + KEY + "\"");
    }

    @Test
    void namesTheConfiguredHeader() {
        ProblemFactory custom = new ProblemFactory(URI.create("https://example.com/p/"), "X-Request-Key");

        Problem problem = custom.missingKey("/a");

        assertThat(problem.type()).isEqualTo(URI.create("https://example.com/p/missing-key"));
        assertThat(problem.detail()).contains("X-Request-Key");
        assertThat(custom.invalidKey("/a", "Too long.").detail())
                .isEqualTo("The X-Request-Key header is invalid. Too long.");
    }

    @Test
    void escapesStrings() {
        Problem problem = factory.keyReuse("/a\"b", "k\\\"\n");

        assertThat(json(problem)).contains("\"instance\":\"/a\\\"b\"", "\"idempotencyKey\":\"k\\\\\\\"\\n\"");
    }

    @ParameterizedTest
    @ValueSource(strings = {"problems/", "https://example.com/problems"})
    void rejectsRelativeOrUnterminatedBaseUris(String uri) {
        assertThatIllegalArgumentException().isThrownBy(() -> new ProblemFactory(URI.create(uri), "Idempotency-Key"));
    }

    @Test
    void rejectsRelativeTypesAndNonErrorStatuses() {
        assertThatIllegalArgumentException()
                .isThrownBy(() -> new Problem(URI.create("key-reuse"), "t", 422, "d", "/", null));
        assertThatIllegalArgumentException()
                .isThrownBy(() -> new Problem(URI.create("https://e.com/x"), "t", 200, "d", "/", null));
        assertThatIllegalArgumentException()
                .isThrownBy(() -> new Problem(URI.create("https://e.com/x"), "t", 600, "d", "/", null));
    }

    private static String json(Problem problem) {
        return new String(problem.toJson(), UTF_8);
    }
}
