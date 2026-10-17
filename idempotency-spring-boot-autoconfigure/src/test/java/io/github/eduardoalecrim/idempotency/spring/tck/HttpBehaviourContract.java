// SPDX-License-Identifier: Apache-2.0
package io.github.eduardoalecrim.idempotency.spring.tck;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * One test per row of the HTTP behaviour table (SPEC §6.5), against an application that exposes the {@code /tck/*}
 * endpoints of SPEC §9 with a request and response limit of 1 KB and a 30 s lease. Written to become
 * {@code HttpAdapterContract} in the test kit.
 */
abstract class HttpBehaviourContract {

    private static final String ORDER = "{\"item\":\"book\",\"quantity\":1}";

    private final HttpClient client = HttpClient.newHttpClient();

    /** Returns the base URI of the application. */
    protected abstract URI baseUri();

    /** Moves the application clock forward. */
    protected abstract void advanceClock(Duration duration);

    /** Makes the store fail from now on, as described by {@code failure}. */
    protected abstract void storeFailure(StoreFailure failure);

    /** Whether the application runs with {@code store.on-failure=fail-open}. */
    protected abstract boolean failOpen();

    /** Returns how many requests are blocked in {@code /tck/slow}. */
    protected abstract int blockedInSlow();

    /** Lets every request blocked in {@code /tck/slow} finish. */
    protected abstract void releaseSlow();

    /** How the store fails. */
    enum StoreFailure {
        /** It works. */
        NONE,
        /** Every call throws. */
        EVERY_CALL,
        /** {@code complete} and {@code release} throw; {@code claim} works. */
        AFTER_CLAIM
    }

    @AfterEach
    void reset() {
        storeFailure(StoreFailure.NONE);
        releaseSlow();
    }

    @Test
    @DisplayName("§6.5 not an idempotent operation: the handler's response, no record")
    void notAnIdempotentOperation() throws Exception {
        String key = key();
        HttpResponse<String> first = send("GET", "/tck/executions", key, null);
        HttpResponse<String> second = send("GET", "/tck/executions", key, null);

        assertThat(first.statusCode()).isEqualTo(200);
        assertThat(second.headers().firstValue("Idempotent-Replayed")).isEmpty();
    }

    @Test
    @DisplayName("§6.5 optional operation, no key: the handler runs every time, no record")
    void optionalOperationWithoutKey() throws Exception {
        int before = executions();

        HttpResponse<String> first = post("/tck/optional", null, ORDER);
        HttpResponse<String> second = post("/tck/optional", null, ORDER);

        assertThat(first.statusCode()).isEqualTo(200);
        assertThat(second.statusCode()).isEqualTo(200);
        assertThat(second.headers().firstValue("Idempotent-Replayed")).isEmpty();
        assertThat(executions()).isEqualTo(before + 2);
    }

    @Test
    @DisplayName("§6.5 required operation, no key: 400 missing-key, the handler does not run")
    void requiredOperationWithoutKey() throws Exception {
        int before = executions();

        HttpResponse<String> response = post("/tck/orders", null, ORDER);

        assertProblem(response, 400, "missing-key");
        assertThat(executions()).isEqualTo(before);
    }

    @Test
    @DisplayName("§6.5 malformed or too long key: 400 invalid-key, the handler does not run")
    void malformedOrTooLongKey() throws Exception {
        int before = executions();

        HttpResponse<String> bare = send("POST", "/tck/orders", null, ORDER, "Idempotency-Key", key());
        HttpResponse<String> tooLong = post("/tck/orders", "k".repeat(256), ORDER);

        assertProblem(bare, 400, "invalid-key");
        assertProblem(tooLong, 400, "invalid-key");
        assertThat(executions()).isEqualTo(before);
    }

    @Test
    @DisplayName("§6.5 body over request.max-body-size: 413 request-too-large, the handler does not run")
    void bodyOverTheLimit() throws Exception {
        int before = executions();

        HttpResponse<String> response = post("/tck/orders", key(), "{\"x\":\"" + "a".repeat(2000) + "\"}");

        assertProblem(response, 413, "request-too-large");
        assertThat(executions()).isEqualTo(before);
    }

    @Test
    @DisplayName("§6.5 new key, storable status: the handler's response, the record completes")
    void newKeyWithStorableStatus() throws Exception {
        String key = key();
        int before = executions();

        HttpResponse<String> created = post("/tck/orders", key, ORDER);
        HttpResponse<String> noContent = post("/tck/no-content", key(), "");
        HttpResponse<String> rejected = post("/tck/rejected", key(), "");

        assertThat(created.statusCode()).isEqualTo(201);
        assertThat(created.headers().firstValue("Location")).isPresent();
        assertThat(noContent.statusCode()).isEqualTo(204);
        assertThat(rejected.statusCode()).isEqualTo(400);
        assertThat(executions()).isEqualTo(before + 3);
        assertThat(post("/tck/orders", key, ORDER).headers().firstValue("Idempotent-Replayed"))
                .hasValue("true");
    }

    @Test
    @DisplayName("§6.5 new key, 5xx or exception: the handler's response, no record")
    void newKeyWithTransientOutcome() throws Exception {
        String unavailable = key();
        String boom = key();
        int before = executions();

        assertThat(post("/tck/unavailable", unavailable, "").statusCode()).isEqualTo(503);
        assertThat(post("/tck/boom", boom, "").statusCode()).isEqualTo(500);
        assertThat(post("/tck/unavailable", unavailable, "").statusCode()).isEqualTo(503);
        assertThat(post("/tck/boom", boom, "").statusCode()).isEqualTo(500);

        assertThat(executions()).isEqualTo(before + 4);
    }

    @Test
    @DisplayName("§6.5 same key, same fingerprint, completed: the stored response, Idempotent-Replayed: true")
    void sameKeyCompleted() throws Exception {
        String key = key();
        HttpResponse<String> first = post("/tck/orders", key, ORDER);
        HttpResponse<String> emptyFirst = post("/tck/no-content", key, "");
        HttpResponse<String> bigFirst = post("/tck/big", key, "");
        int before = executions();

        HttpResponse<String> replay = post("/tck/orders", key, "{ \"quantity\": 1, \"item\": \"book\" }");
        HttpResponse<String> emptyReplay = post("/tck/no-content", key, "");
        HttpResponse<String> bigReplay = post("/tck/big", key, "");

        assertThat(replay.statusCode()).isEqualTo(201);
        assertThat(replay.body()).isEqualTo(first.body());
        assertThat(replay.headers().firstValue("Location"))
                .isEqualTo(first.headers().firstValue("Location"));
        assertThat(replay.headers().firstValue("Idempotent-Replayed")).hasValue("true");
        assertThat(emptyFirst.statusCode()).isEqualTo(204);
        assertThat(emptyReplay.statusCode()).isEqualTo(204);
        assertThat(bigFirst.body()).hasSizeGreaterThan(1024);
        assertThat(bigReplay.statusCode()).isEqualTo(200);
        assertThat(bigReplay.body()).isEmpty();
        assertThat(executions()).isEqualTo(before);
    }

    @Test
    @DisplayName("§6.5 same key, different fingerprint: 422 key-reuse, the record is unchanged")
    void sameKeyDifferentFingerprint() throws Exception {
        String key = key();
        HttpResponse<String> first = post("/tck/orders", key, ORDER);
        int before = executions();

        HttpResponse<String> reuse = post("/tck/orders", key, "{\"item\":\"pen\",\"quantity\":2}");

        assertProblem(reuse, 422, "key-reuse");
        assertThat(executions()).isEqualTo(before);
        assertThat(post("/tck/orders", key, ORDER).body()).isEqualTo(first.body());
    }

    @Test
    @DisplayName("§6.5 same key, same fingerprint, in progress: 409 with Retry-After, the record is unchanged")
    void sameKeyInProgress() throws Exception {
        String key = key();
        CompletableFuture<HttpResponse<String>> first = postAsync("/tck/slow", key, ORDER);
        awaitBlocked(1);

        HttpResponse<String> retry = post("/tck/slow", key, ORDER);
        releaseSlow();

        assertProblem(retry, 409, "request-in-progress");
        assertThat(retry.headers().firstValue("Retry-After"))
                .hasValueSatisfying(
                        seconds -> assertThat(Integer.parseInt(seconds)).isBetween(1, 30));
        assertThat(first.get(10, TimeUnit.SECONDS).statusCode()).isEqualTo(201);
        assertThat(post("/tck/slow", key, ORDER).headers().firstValue("Idempotent-Replayed"))
                .hasValue("true");
    }

    @Test
    @DisplayName("§6.5 same key after the lease expired: the handler runs, a new claim")
    void sameKeyAfterTheLease() throws Exception {
        String key = key();
        int before = executions();
        CompletableFuture<HttpResponse<String>> first = postAsync("/tck/slow", key, ORDER);
        awaitBlocked(1);
        advanceClock(Duration.ofSeconds(31));

        CompletableFuture<HttpResponse<String>> takeover = postAsync("/tck/slow", key, "{\"item\":\"pen\"}");
        awaitBlocked(2);
        releaseSlow();

        assertThat(first.get(10, TimeUnit.SECONDS).statusCode()).isEqualTo(201);
        assertThat(takeover.get(10, TimeUnit.SECONDS).statusCode()).isEqualTo(201);
        assertThat(executions()).isEqualTo(before + 2);
        assertThat(post("/tck/slow", key, "{\"item\":\"pen\"}").body())
                .isEqualTo(takeover.get().body());
    }

    @Test
    @DisplayName("§6.5 same key after the TTL: the handler runs, a new record")
    void sameKeyAfterTheTtl() throws Exception {
        String key = key();
        post("/tck/orders", key, ORDER);
        advanceClock(Duration.ofHours(24).plusSeconds(1));
        int before = executions();

        HttpResponse<String> again = post("/tck/orders", key, "{\"item\":\"pen\"}");

        assertThat(again.statusCode()).isEqualTo(201);
        assertThat(again.headers().firstValue("Idempotent-Replayed")).isEmpty();
        assertThat(executions()).isEqualTo(before + 1);
    }

    @Test
    @DisplayName("§6.5 store unavailable, fail-closed: 503 store-unavailable with Retry-After: 1")
    void storeUnavailableFailClosed() throws Exception {
        Assumptions.assumeFalse(failOpen());
        storeFailure(StoreFailure.EVERY_CALL);
        int before = executions();

        HttpResponse<String> response = post("/tck/orders", key(), ORDER);

        assertProblem(response, 503, "store-unavailable");
        assertThat(response.headers().firstValue("Retry-After")).hasValue("1");
        assertThat(executions()).isEqualTo(before);
    }

    @Test
    @DisplayName("§6.5 store unavailable, fail-open: the handler runs, no record")
    void storeUnavailableFailOpen() throws Exception {
        Assumptions.assumeTrue(failOpen());
        storeFailure(StoreFailure.EVERY_CALL);
        String key = key();
        int before = executions();

        HttpResponse<String> first = post("/tck/orders", key, ORDER);
        HttpResponse<String> second = post("/tck/orders", key, ORDER);

        assertThat(first.statusCode()).isEqualTo(201);
        assertThat(second.headers().firstValue("Idempotent-Replayed")).isEmpty();
        assertThat(executions()).isEqualTo(before + 2);
    }

    @Test
    @DisplayName("§6.5 store fails after the handler ran: the handler's response, in progress until the lease ends")
    void storeFailsAfterTheHandler() throws Exception {
        String key = key();
        storeFailure(StoreFailure.AFTER_CLAIM);

        HttpResponse<String> first = post("/tck/orders", key, ORDER);
        storeFailure(StoreFailure.NONE);
        HttpResponse<String> retry = post("/tck/orders", key, ORDER);
        advanceClock(Duration.ofSeconds(31));
        HttpResponse<String> afterLease = post("/tck/orders", key, ORDER);

        assertThat(first.statusCode()).isEqualTo(201);
        assertProblem(retry, 409, "request-in-progress");
        assertThat(afterLease.statusCode()).isEqualTo(201);
        assertThat(afterLease.headers().firstValue("Idempotent-Replayed")).isEmpty();
    }

    private void awaitBlocked(int count) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        while (blockedInSlow() < count) {
            if (System.nanoTime() > deadline) {
                throw new AssertionError("expected " + count + " requests blocked in /tck/slow");
            }
            Thread.sleep(10);
        }
    }

    private int executions() throws IOException, InterruptedException {
        return Integer.parseInt(
                send("GET", "/tck/executions", null, null).body().trim());
    }

    private static void assertProblem(HttpResponse<String> response, int status, String type) {
        assertThat(response.statusCode()).isEqualTo(status);
        assertThat(response.headers().firstValue("Content-Type")).hasValue("application/problem+json");
        assertThat(response.body()).contains("/problems/" + type + "\"");
    }

    private HttpResponse<String> post(String path, @Nullable String key, String body)
            throws IOException, InterruptedException {
        return send("POST", path, key, body);
    }

    private CompletableFuture<HttpResponse<String>> postAsync(String path, String key, String body) {
        return client.sendAsync(request("POST", path, key, body).build(), HttpResponse.BodyHandlers.ofString());
    }

    private HttpResponse<String> send(
            String method, String path, @Nullable String key, @Nullable String body, String... headers)
            throws IOException, InterruptedException {
        HttpRequest.Builder request = request(method, path, key, body);
        if (headers.length > 0) {
            request.headers(headers);
        }
        return client.send(request.build(), HttpResponse.BodyHandlers.ofString());
    }

    private HttpRequest.Builder request(String method, String path, @Nullable String key, @Nullable String body) {
        HttpRequest.Builder request = HttpRequest.newBuilder(baseUri().resolve(path))
                .timeout(Duration.ofSeconds(10))
                .method(
                        method,
                        body == null ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofString(body));
        if (body != null) {
            request.header("Content-Type", "application/json");
        }
        if (key != null) {
            request.header("Idempotency-Key", "\"" + key + "\"");
        }
        return request;
    }

    private static String key() {
        return UUID.randomUUID().toString();
    }
}
