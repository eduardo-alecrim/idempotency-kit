// SPDX-License-Identifier: Apache-2.0
package io.github.eduardoalecrim.idempotency.sample;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class SampleApplicationTest {

    private final HttpClient client = HttpClient.newHttpClient();

    @LocalServerPort
    int port;

    @Test
    void replaysOrdersAndRejectsKeyReuse() throws Exception {
        String key = UUID.randomUUID().toString();

        HttpResponse<String> first = post(key, "{\"item\":\"book\",\"quantity\":1}");
        HttpResponse<String> replay = post(key, "{ \"quantity\": 1, \"item\": \"book\" }");
        HttpResponse<String> reuse = post(key, "{\"item\":\"pen\",\"quantity\":2}");

        assertThat(first.statusCode()).isEqualTo(201);
        assertThat(replay.statusCode()).isEqualTo(201);
        assertThat(replay.body()).isEqualTo(first.body());
        assertThat(replay.headers().firstValue("Idempotent-Replayed")).hasValue("true");
        assertThat(reuse.statusCode()).isEqualTo(422);
        assertThat(reuse.headers().firstValue("Content-Type")).hasValue("application/problem+json");
    }

    private HttpResponse<String> post(String key, String body) throws IOException, InterruptedException {
        HttpRequest request = HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/orders"))
                .header("Content-Type", "application/json")
                .header("Idempotency-Key", "\"" + key + "\"")
                .POST(HttpRequest.BodyPublishers.ofString(body))
                .build();
        return client.send(request, HttpResponse.BodyHandlers.ofString());
    }
}
