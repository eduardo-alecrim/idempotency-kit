// SPDX-License-Identifier: Apache-2.0
package io.github.eduardoalecrim.idempotency.spring.web;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;

@SpringBootTest(
        classes = WebTestApplication.class,
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
            "idempotency.request.max-body-size=1KB",
            "idempotency.response.max-body-size=1KB",
            "idempotency.routes[0].methods=POST",
            "idempotency.routes[0].path=/legacy"
        })
class IdempotencyFilterIntegrationTest {

    private final HttpClient client = HttpClient.newHttpClient();

    @LocalServerPort
    int port;

    @Autowired
    WebTestApplication.Orders orders;

    @Test
    @DisplayName("FR-05: a replay restores status, body and allowlisted headers, with Idempotent-Replayed: true")
    void replaysTheFirstResponse() throws Exception {
        String key = key();

        HttpResponse<String> first = post("/orders", key, "{\"item\":\"book\"}", null);
        int executions = orders.executions.get();
        HttpResponse<String> replay = post("/orders", key, "{ \"item\" : \"book\" }", null);

        assertThat(first.statusCode()).isEqualTo(201);
        assertThat(first.headers().firstValue("Idempotent-Replayed")).isEmpty();
        assertThat(replay.statusCode()).isEqualTo(201);
        assertThat(replay.body()).isEqualTo(first.body());
        assertThat(replay.headers().firstValue("Idempotent-Replayed")).hasValue("true");
        assertThat(replay.headers().firstValue("Location"))
                .isEqualTo(first.headers().firstValue("Location"));
        assertThat(replay.headers().firstValue("Content-Type")).hasValue("application/json");
        assertThat(first.headers().firstValue("X-Trace")).isPresent();
        assertThat(replay.headers().firstValue("X-Trace")).isEmpty();
        assertThat(replay.headers().firstValue("Set-Cookie")).isEmpty();
        assertThat(orders.executions.get()).isEqualTo(executions);
    }

    @Test
    @DisplayName("FR-30: the Spring Security principal is part of the scope key")
    void scopesKeysByPrincipal() throws Exception {
        String key = key();

        HttpResponse<String> alice = post("/orders", key, "{}", "alice");
        HttpResponse<String> bob = post("/orders", key, "{}", "bob");
        HttpResponse<String> aliceAgain = post("/orders", key, "{}", "alice");
        HttpResponse<String> anonymous = post("/orders", key, "{}", null);

        assertThat(alice.body()).contains("\"owner\":\"alice\"");
        assertThat(bob.body()).contains("\"owner\":\"bob\"").isNotEqualTo(alice.body());
        assertThat(aliceAgain.body()).isEqualTo(alice.body());
        assertThat(aliceAgain.headers().firstValue("Idempotent-Replayed")).hasValue("true");
        assertThat(anonymous.body()).contains("\"owner\":\"\"");
    }

    @Test
    @DisplayName("FR-17: a route under idempotency.routes is idempotent without an annotation")
    void configuredRoutesAreIdempotent() throws Exception {
        String key = key();

        HttpResponse<String> first = post("/legacy", key, "x", null);
        HttpResponse<String> replay = post("/legacy", key, "x", null);

        assertThat(first.statusCode()).isEqualTo(200);
        assertThat(replay.body()).isEqualTo(first.body());
        assertThat(replay.headers().firstValue("Idempotent-Replayed")).hasValue("true");
    }

    @Test
    void leavesOtherHandlersAlone() throws Exception {
        HttpResponse<String> first = post("/plain", null, "x", null);
        HttpResponse<String> second = post("/plain", null, "x", null);

        assertThat(first.statusCode()).isEqualTo(200);
        assertThat(second.body()).isNotEqualTo(first.body());
    }

    @Test
    void answersProblemsWithoutCallingTheHandler() throws Exception {
        int executions = orders.executions.get();

        HttpResponse<String> missing = post("/orders", null, "{}", null);

        assertThat(missing.statusCode()).isEqualTo(400);
        assertThat(missing.headers().firstValue("Content-Type")).hasValue("application/problem+json");
        assertThat(missing.body()).contains("/problems/missing-key", "\"instance\":\"/orders\"");
        assertThat(orders.executions.get()).isEqualTo(executions);
    }

    @Test
    @DisplayName("FR-18: a body over request.max-body-size answers 413 before the handler runs")
    void rejectsLargeBodies() throws Exception {
        int executions = orders.executions.get();

        HttpResponse<String> response = post("/orders", key(), "{\"x\":\"" + "a".repeat(2000) + "\"}", null);

        assertThat(response.statusCode()).isEqualTo(413);
        assertThat(response.body()).contains("/problems/request-too-large");
        assertThat(orders.executions.get()).isEqualTo(executions);
    }

    @Test
    @DisplayName("FR-19: an oversized response reaches the client whole and replays without its body")
    void storesOversizedResponsesWithoutBody() throws Exception {
        String key = key();

        HttpResponse<String> first = post("/big", key, "", null);
        HttpResponse<String> replay = post("/big", key, "", null);

        assertThat(first.body()).hasSize(4096);
        assertThat(replay.statusCode()).isEqualTo(200);
        assertThat(replay.body()).isEmpty();
        assertThat(replay.headers().firstValue("Idempotent-Replayed")).hasValue("true");
    }

    @Test
    void handlersStillReadFormParameters() throws Exception {
        String key = key();
        HttpRequest request = HttpRequest.newBuilder(uri("/form"))
                .header("Idempotency-Key", "\"" + key + "\"")
                .header("Content-Type", "application/x-www-form-urlencoded")
                .POST(HttpRequest.BodyPublishers.ofString("name=Ana+Maria"))
                .build();

        HttpResponse<String> first = client.send(request, HttpResponse.BodyHandlers.ofString());
        HttpResponse<String> replay = client.send(request, HttpResponse.BodyHandlers.ofString());

        assertThat(first.body()).startsWith("hello Ana Maria ");
        assertThat(replay.body()).isEqualTo(first.body());
    }

    @Test
    void releasesResponsesSentThroughSendError() throws Exception {
        String key = key();
        int executions = orders.executions.get();

        HttpResponse<String> first = post("/rejected", key, "", null);
        HttpResponse<String> second = post("/rejected", key, "", null);

        assertThat(first.statusCode()).isEqualTo(400);
        assertThat(second.statusCode()).isEqualTo(400);
        assertThat(second.headers().firstValue("Idempotent-Replayed")).isEmpty();
        assertThat(orders.executions.get()).isEqualTo(executions + 2);
    }

    private HttpResponse<String> post(String path, @Nullable String key, String body, @Nullable String user)
            throws IOException, InterruptedException {
        HttpRequest.Builder request = HttpRequest.newBuilder(uri(path))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body));
        if (key != null) {
            request.header("Idempotency-Key", "\"" + key + "\"");
        }
        if (user != null) {
            String credentials =
                    Base64.getEncoder().encodeToString((user + ":secret").getBytes(StandardCharsets.UTF_8));
            request.header("Authorization", "Basic " + credentials);
        }
        return client.send(request.build(), HttpResponse.BodyHandlers.ofString());
    }

    private URI uri(String path) {
        return URI.create("http://localhost:" + port + path);
    }

    private static String key() {
        return UUID.randomUUID().toString();
    }
}
