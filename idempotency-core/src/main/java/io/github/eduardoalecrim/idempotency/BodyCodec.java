// SPDX-License-Identifier: Apache-2.0
package io.github.eduardoalecrim.idempotency;

/** Transforms response bodies on their way into and out of the store. */
public interface BodyCodec {

    /** Stores bodies unchanged. */
    BodyCodec IDENTITY = new BodyCodec() {
        @Override
        public byte[] encode(byte[] body) {
            return body;
        }

        @Override
        public byte[] decode(byte[] stored) {
            return stored;
        }
    };

    /** Returns the bytes to store for {@code body}. */
    byte[] encode(byte[] body);

    /** Returns the body for bytes produced by {@link #encode(byte[])}. */
    byte[] decode(byte[] stored);
}
