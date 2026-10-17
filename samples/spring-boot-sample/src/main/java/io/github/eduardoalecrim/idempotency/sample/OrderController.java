// SPDX-License-Identifier: Apache-2.0
package io.github.eduardoalecrim.idempotency.sample;

import io.github.eduardoalecrim.idempotency.Idempotent;
import java.net.URI;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicLong;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/** Places orders. Each call that reaches the handler creates a new order. */
@RestController
class OrderController {

    private final AtomicLong ids = new AtomicLong();

    /** A retry with the same key and body gets the first order back instead of a second one. */
    @Idempotent
    @PostMapping("/orders")
    ResponseEntity<Order> place(@RequestBody OrderRequest request) {
        Order order = new Order(ids.incrementAndGet(), request.item(), request.quantity());
        return ResponseEntity.created(URI.create("/orders/" + order.id())).body(order);
    }

    /** Takes ten seconds, so that a retry sent meanwhile gets 409 request-in-progress. */
    @Idempotent(lease = "30s")
    @PostMapping("/orders/slow")
    ResponseEntity<Order> placeSlowly(@RequestBody OrderRequest request) throws InterruptedException {
        Thread.sleep(Duration.ofSeconds(10).toMillis());
        return place(request);
    }

    /**
     * The body of {@code POST /orders}.
     *
     * @param item what to order
     * @param quantity how many
     */
    record OrderRequest(String item, int quantity) {}

    /**
     * A placed order.
     *
     * @param id the order number
     * @param item what was ordered
     * @param quantity how many
     */
    record Order(long id, String item, int quantity) {}
}
