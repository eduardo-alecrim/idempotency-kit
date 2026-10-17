// SPDX-License-Identifier: Apache-2.0
package io.github.eduardoalecrim.idempotency.spring;

import org.springframework.boot.diagnostics.AbstractFailureAnalyzer;
import org.springframework.boot.diagnostics.FailureAnalysis;

/** Turns {@link IdempotencyConfigurationException} into a startup report that names the problem and its fix. */
class IdempotencyFailureAnalyzer extends AbstractFailureAnalyzer<IdempotencyConfigurationException> {

    @Override
    protected FailureAnalysis analyze(Throwable rootFailure, IdempotencyConfigurationException cause) {
        return new FailureAnalysis(String.valueOf(cause.getMessage()), cause.getAction(), cause);
    }
}
