// SPDX-License-Identifier: Apache-2.0
package io.github.eduardoalecrim.idempotency.fingerprint;

import io.github.eduardoalecrim.idempotency.internal.LengthPrefixedDigest;
import io.github.eduardoalecrim.idempotency.internal.json.JsonCanonicalizer;
import io.github.eduardoalecrim.idempotency.spi.Fingerprint;

/** Decides which parts of a request make two requests the same request. Implementations must be thread-safe. */
public interface FingerprintStrategy {

    /** Returns the fingerprint of {@code input}. */
    Fingerprint fingerprint(FingerprintInput input);

    /**
     * The default strategy: SHA-256 over the method, the route template, the media type, the selected headers and the
     * body, each length-prefixed. JSON bodies ({@code application/json} and {@code +json}) are canonicalized with RFC
     * 8785 first; a JSON body that does not parse, and every other body, is hashed as raw bytes.
     */
    final class Default implements FingerprintStrategy {

        private final boolean canonicalizeJson;

        /** Creates the strategy with JSON canonicalization on. */
        public Default() {
            this(true);
        }

        /** Creates the strategy, with or without JSON canonicalization. */
        public Default(boolean canonicalizeJson) {
            this.canonicalizeJson = canonicalizeJson;
        }

        @Override
        public Fingerprint fingerprint(FingerprintInput input) {
            String mediaType = input.mediaType();
            LengthPrefixedDigest digest = new LengthPrefixedDigest()
                    .add(input.method())
                    .add(input.routeTemplate())
                    .add(mediaType)
                    .add(input.headers().size());
            for (FingerprintInput.Header header : input.headers()) {
                digest.add(header.name()).add(header.values().size());
                header.values().forEach(digest::add);
            }
            byte[] body = input.body();
            if (canonicalizeJson && isJson(mediaType)) {
                body = JsonCanonicalizer.canonicalize(body).orElse(body);
            }
            return new Fingerprint(digest.add(body).hex());
        }

        private static boolean isJson(String mediaType) {
            return mediaType.equals("application/json") || (mediaType.contains("/") && mediaType.endsWith("+json"));
        }
    }
}
