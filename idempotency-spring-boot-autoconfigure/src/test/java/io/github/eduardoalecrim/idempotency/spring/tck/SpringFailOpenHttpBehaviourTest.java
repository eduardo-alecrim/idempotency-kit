// SPDX-License-Identifier: Apache-2.0
package io.github.eduardoalecrim.idempotency.spring.tck;

import org.springframework.boot.test.context.SpringBootTest;

/** The HTTP behaviour table against the Spring MVC adapter with {@code store.on-failure=fail-open}. */
@SpringBootTest(
        classes = TckApplication.class,
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
            "idempotency.request.max-body-size=1KB",
            "idempotency.response.max-body-size=1KB",
            "idempotency.store.on-failure=fail-open"
        })
class SpringFailOpenHttpBehaviourTest extends SpringHttpBehaviourTest {

    @Override
    protected boolean failOpen() {
        return true;
    }
}
