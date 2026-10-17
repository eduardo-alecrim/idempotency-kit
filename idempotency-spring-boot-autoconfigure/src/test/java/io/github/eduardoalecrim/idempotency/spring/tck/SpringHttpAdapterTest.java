// SPDX-License-Identifier: Apache-2.0
package io.github.eduardoalecrim.idempotency.spring.tck;

import io.github.eduardoalecrim.idempotency.tck.HttpAdapterContract;
import io.github.eduardoalecrim.idempotency.tck.MutableClock;
import java.net.URI;
import java.time.Duration;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;

/** The HTTP adapter contract against the Spring MVC adapter, with the default fail-closed store policy. */
@SpringBootTest(
        classes = TckApplication.class,
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {"idempotency.request.max-body-size=1KB", "idempotency.response.max-body-size=1KB"})
class SpringHttpAdapterTest extends HttpAdapterContract {

    @LocalServerPort
    int port;

    @Autowired
    MutableClock clock;

    @Autowired
    TckApplication.ControllableStore store;

    @Autowired
    TckApplication.TckController controller;

    @Override
    protected URI baseUri() {
        return URI.create("http://localhost:" + port);
    }

    @Override
    protected void advanceClock(Duration duration) {
        clock.advance(duration);
    }

    @Override
    protected void storeFailure(StoreFailure failure) {
        store.failure = failure;
    }

    @Override
    protected boolean failOpen() {
        return false;
    }

    @Override
    protected int blockedInSlow() {
        return controller.blocked.get();
    }

    @Override
    protected void releaseSlow() {
        controller.releaseSlow();
    }
}
