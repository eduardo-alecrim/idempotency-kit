// SPDX-License-Identifier: Apache-2.0
package io.github.eduardoalecrim.idempotency.spring;

/** Invalid idempotency configuration or handler, reported at startup by {@link IdempotencyFailureAnalyzer}. */
public class IdempotencyConfigurationException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    private final String action;

    /**
     * @param description what is wrong
     * @param action how to fix it
     */
    public IdempotencyConfigurationException(String description, String action) {
        super(description);
        this.action = action;
    }

    /** Reports an invalid property value. */
    static IdempotencyConfigurationException property(String property, String reason) {
        return new IdempotencyConfigurationException(
                "Invalid value of '" + property + "': " + reason, "Update '" + property + "' in the configuration.");
    }

    /** Returns how to fix the problem. */
    public String getAction() {
        return action;
    }
}
