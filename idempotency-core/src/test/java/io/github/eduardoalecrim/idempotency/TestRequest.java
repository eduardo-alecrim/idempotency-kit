// SPDX-License-Identifier: Apache-2.0
package io.github.eduardoalecrim.idempotency;

import static java.nio.charset.StandardCharsets.UTF_8;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import org.jspecify.annotations.Nullable;

/** An {@link IncomingRequest} built in tests. */
final class TestRequest implements IncomingRequest {

    private final String method;
    private final String route;
    private final Map<String, List<String>> headers = new LinkedHashMap<>();
    private byte[] body = new byte[0];
    private @Nullable String clientIdentity;

    private TestRequest(String method, String route) {
        this.method = method;
        this.route = route;
    }

    static TestRequest post(String route) {
        return new TestRequest("POST", route);
    }

    TestRequest key(String key) {
        return header("Idempotency-Key", "\"" + key + "\"");
    }

    TestRequest header(String name, String value) {
        headers.computeIfAbsent(name.toLowerCase(Locale.ROOT), n -> new ArrayList<>())
                .add(value);
        return this;
    }

    TestRequest json(String json) {
        header("Content-Type", "application/json");
        this.body = json.getBytes(UTF_8);
        return this;
    }

    TestRequest client(String clientIdentity) {
        this.clientIdentity = clientIdentity;
        return this;
    }

    @Override
    public String method() {
        return method;
    }

    @Override
    public String path() {
        return route;
    }

    @Override
    public String routeTemplate() {
        return route;
    }

    @Override
    public List<String> headerValues(String name) {
        return headers.getOrDefault(name.toLowerCase(Locale.ROOT), List.of());
    }

    @Override
    public @Nullable String contentType() {
        List<String> values = headerValues("Content-Type");
        return values.isEmpty() ? null : values.get(0);
    }

    @Override
    public Optional<String> clientIdentity() {
        return Optional.ofNullable(clientIdentity);
    }

    @Override
    public byte[] body(int maxBytes) {
        return Arrays.copyOf(body, Math.min(body.length, maxBytes + 1));
    }
}
