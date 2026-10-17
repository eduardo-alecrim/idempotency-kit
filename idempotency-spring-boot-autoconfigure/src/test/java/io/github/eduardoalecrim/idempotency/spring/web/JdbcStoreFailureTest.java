// SPDX-License-Identifier: Apache-2.0
package io.github.eduardoalecrim.idempotency.spring.web;

import static org.assertj.core.api.Assertions.assertThat;

import eu.rekawek.toxiproxy.Proxy;
import eu.rekawek.toxiproxy.ToxiproxyClient;
import eu.rekawek.toxiproxy.model.Toxic;
import eu.rekawek.toxiproxy.model.ToxicDirection;
import io.github.eduardoalecrim.idempotency.Idempotent;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import javax.sql.DataSource;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.postgresql.ds.PGSimpleDataSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.http.ResponseEntity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;
import org.testcontainers.containers.Network;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.toxiproxy.ToxiproxyContainer;

/**
 * FR-21, FR-40 and NFR-10 on a real database whose network hangs: PostgreSQL behind Toxiproxy, with a {@code timeout}
 * toxic that holds every byte and never closes the connection.
 */
@SpringBootTest(
        classes = JdbcStoreFailureTest.App.class,
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {"idempotency.store.timeout=1s", "idempotency.store.jdbc.initialize-schema=always"})
class JdbcStoreFailureTest {

    private static final Duration TIMEOUT = Duration.ofSeconds(1);
    private static final Network NETWORK = Network.newNetwork();
    private static final PostgreSQLContainer POSTGRES =
            new PostgreSQLContainer("postgres:18-alpine").withNetwork(NETWORK).withNetworkAliases("postgres");
    private static final ToxiproxyContainer TOXIPROXY =
            new ToxiproxyContainer("ghcr.io/shopify/toxiproxy:2.12.0").withNetwork(NETWORK);
    private static final Proxy PROXY;

    static {
        POSTGRES.start();
        TOXIPROXY.start();
        try {
            PROXY = new ToxiproxyClient(TOXIPROXY.getHost(), TOXIPROXY.getControlPort())
                    .createProxy("postgres", "0.0.0.0:8666", "postgres:5432");
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private final HttpClient http = HttpClient.newHttpClient();

    @LocalServerPort
    int port;

    @Autowired
    App.Orders orders;

    @AfterEach
    void healNetwork() throws IOException {
        for (Toxic toxic : PROXY.toxics().getAll()) {
            toxic.remove();
        }
        PROXY.enable();
    }

    @Test
    @DisplayName("NFR-10: with the database hanging, fail-closed answers 503 within store.timeout + 50 ms")
    void hangingDatabaseAnswers503InTime() throws Exception {
        // Warm up the server, the client and the store threads, so the measure is the outage alone.
        assertThat(post(UUID.randomUUID().toString()).statusCode()).isEqualTo(201);
        hangNetwork();
        int executions = orders.executions.get();

        long start = System.nanoTime();
        HttpResponse<String> response = post(UUID.randomUUID().toString());
        Duration elapsed = Duration.ofNanos(System.nanoTime() - start);

        assertThat(response.statusCode()).isEqualTo(503);
        assertThat(response.headers().firstValue("Retry-After")).hasValue("1");
        assertThat(elapsed).isBetween(TIMEOUT, TIMEOUT.plusMillis(50));
        assertThat(orders.executions.get()).isEqualTo(executions);
    }

    @Test
    @DisplayName("FR-21: a database that refuses connections gets 503 at once")
    void refusingDatabaseAnswers503() throws Exception {
        PROXY.disable();

        long start = System.nanoTime();
        HttpResponse<String> response = post(UUID.randomUUID().toString());

        assertThat(response.statusCode()).isEqualTo(503);
        assertThat(Duration.ofNanos(System.nanoTime() - start)).isLessThan(TIMEOUT);
    }

    @Test
    @DisplayName("FR-40: when complete times out, the client gets the handler's response and a retry gets 409")
    void completeTimeoutKeepsTheResponse() throws Exception {
        String key = UUID.randomUUID().toString();
        orders.hangAfterHandling = true;
        int executions = orders.executions.get();

        long start = System.nanoTime();
        HttpResponse<String> first = post(key);
        Duration elapsed = Duration.ofNanos(System.nanoTime() - start);
        healNetwork();
        HttpResponse<String> retry = post(key);

        assertThat(first.statusCode()).isEqualTo(201);
        assertThat(first.body()).isEqualTo("{\"id\":" + (executions + 1) + "}");
        assertThat(elapsed).isBetween(TIMEOUT, TIMEOUT.plusMillis(500));
        assertThat(retry.statusCode()).isEqualTo(409);
        assertThat(orders.executions.get()).isEqualTo(executions + 1);
    }

    private HttpResponse<String> post(String key) throws IOException, InterruptedException {
        HttpRequest request = HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/orders"))
                .header("Idempotency-Key", "\"" + key + "\"")
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString("{\"item\":\"book\"}"))
                .build();
        return http.send(request, HttpResponse.BodyHandlers.ofString());
    }

    private static void hangNetwork() {
        try {
            PROXY.toxics().timeout("hang-down", ToxicDirection.DOWNSTREAM, 0);
            PROXY.toxics().timeout("hang-up", ToxicDirection.UPSTREAM, 0);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    @SpringBootConfiguration
    @EnableAutoConfiguration
    @Import(App.Orders.class)
    static class App {

        @Bean
        DataSource dataSource() {
            PGSimpleDataSource dataSource = new PGSimpleDataSource();
            dataSource.setUrl("jdbc:postgresql://" + TOXIPROXY.getHost() + ":" + TOXIPROXY.getMappedPort(8666) + "/"
                    + POSTGRES.getDatabaseName());
            dataSource.setUser(POSTGRES.getUsername());
            dataSource.setPassword(POSTGRES.getPassword());
            return dataSource;
        }

        @Bean
        SecurityFilterChain security(HttpSecurity http) throws Exception {
            return http.csrf(csrf -> csrf.disable())
                    .authorizeHttpRequests(requests -> requests.anyRequest().permitAll())
                    .build();
        }

        @RestController
        static class Orders {

            final AtomicInteger executions = new AtomicInteger();
            volatile boolean hangAfterHandling;

            @Idempotent
            @PostMapping("/orders")
            ResponseEntity<String> create() {
                int id = executions.incrementAndGet();
                if (hangAfterHandling) {
                    hangAfterHandling = false;
                    hangNetwork();
                }
                return ResponseEntity.status(201)
                        .header("Content-Type", "application/json")
                        .body("{\"id\":" + id + "}");
            }
        }
    }
}
