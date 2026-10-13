# idempotency-kit

**`Idempotency-Key` header support for Java HTTP APIs: a Spring Boot starter and a Quarkus extension, with JDBC and Redis stores.**

[![Status](https://img.shields.io/badge/status-in%20development-ff8a1f?style=flat-square&labelColor=0d0e10)](docs/SPEC.md)
[![License](https://img.shields.io/badge/license-Apache--2.0-ff8a1f?style=flat-square&labelColor=0d0e10)](LICENSE)
[![Java](https://img.shields.io/badge/Java-17%2B-ff8a1f?style=flat-square&labelColor=0d0e10)](docs/SPEC.md#71-technology)

> **Status:** in development. Nothing is released yet; the code below shows the target API from the [specification](docs/SPEC.md).

## What it is

Clients retry. A timeout after `POST /payments` leaves the client unsure whether the payment happened, so it sends the request again, and a non-idempotent endpoint charges twice.

idempotency-kit implements the IETF draft [*The Idempotency-Key HTTP Header Field*](https://datatracker.ietf.org/doc/draft-ietf-httpapi-idempotency-key-header/). The first request with a key runs and its response is stored. A retry with the same key gets the stored response back, without running the operation again. A retry that arrives while the first request is still running gets `409 Conflict`. The same key with a different payload gets `422 Unprocessable Content`.

The protocol lives in a framework-free core. A Spring Boot starter and a Quarkus extension sit on top of it, with stores for PostgreSQL, MySQL, MariaDB, Oracle, SQL Server and Redis. One test compatibility kit (TCK) checks that every store and every framework behaves the same way.

## Target API

Spring Boot:

```java
@RestController
class OrderController {

    @Idempotent(ttl = "24h")
    @PostMapping("/orders")
    ResponseEntity<Order> create(@RequestBody NewOrder order) {
        Order created = orders.place(order);
        return ResponseEntity.created(URI.create("/orders/" + created.id())).body(created);
    }
}
```

```yaml
idempotency:
  store:
    type: jdbc        # auto | jdbc | redis | in-memory
  lease: 30s
```

Quarkus:

```java
@Path("/orders")
public class OrderResource {

    @POST
    @Idempotent(ttl = "24h")
    public Response create(NewOrder order) {
        Order created = orders.place(order);
        return Response.created(URI.create("/orders/" + created.id())).entity(created).build();
    }
}
```

On the wire:

```http
POST /orders HTTP/1.1
Content-Type: application/json
Idempotency-Key: "4a1c7d9e-5f3b-4e8a-9c21-7b0d3e6f8a12"

{"item": "SKU-1001", "quantity": 2}
```

A retry with the same key and body returns the original `201 Created`, the same body and `Idempotent-Replayed: true`, and the handler does not run.

## Highlights

- **Follows the draft.** Structured Field key syntax, `400` for a missing key, `409` while the first request runs, `422` when a key is reused with a different payload, all as [RFC 9457](https://www.rfc-editor.org/rfc/rfc9457) Problem Details.
- **Correct under concurrency.** An atomic claim with a lease and an owner token, so 64 simultaneous retries across instances run the handler exactly once.
- **Recovers from crashes.** A request that dies mid-flight frees its key when the lease expires.
- **Safe defaults.** Keys are scoped by client, method and route; `5xx` responses are never stored; `Set-Cookie` and auth headers are never replayed; stored bodies can be encrypted with AES-GCM.
- **Payload-aware.** JSON bodies are canonicalized ([RFC 8785](https://www.rfc-editor.org/rfc/rfc8785)) before fingerprinting, so key order and whitespace don't count as a different request.
- **Idiomatic on both frameworks.** Spring Boot auto-configuration, Actuator and Micrometer; a Quarkus extension with build-time validation, Dev UI and native-image support.
- **Small footprint.** The core depends on nothing but the JDK.

## Modules

| Module | Purpose |
|---|---|
| `idempotency-bom` | Aligns the versions of every module |
| `idempotency-core` | Engine, key parser, fingerprinting, store SPI, in-memory store; no dependencies |
| `idempotency-store-jdbc` | PostgreSQL, MySQL, MariaDB, Oracle and SQL Server, with DDL and a Liquibase changelog |
| `idempotency-store-redis` | Redis and Valkey through Lettuce |
| `idempotency-spring-boot-starter` | Spring MVC adapter, auto-configuration, Actuator, metrics |
| `idempotency-quarkus` | Quarkus REST adapter, JDBC and Redis stores, Dev UI, native support |
| `idempotency-client` | Generates a key once and reuses it across retries; `RestClient` and MicroProfile REST Client support |
| `idempotency-test-kit` | The TCK: abstract tests that every store and adapter must pass |

## Roadmap

| Milestone | Outcome | Status |
|---|---|---|
| M0 Foundation | Multi-module build, CI on Java 17, 21 and 25, ADRs | planned |
| M1 Core and Spring MVC | One annotation gives a Spring Boot app correct idempotency | planned |
| M2 JDBC store and TCK | Correct across instances on five databases | planned |
| M3 Redis store | Redis and Valkey, no purge job | planned |
| M4 Quarkus extension | Same behaviour on Quarkus, JVM and native | planned |
| M5 Clients and observability | Retry helpers, metrics, tracing, health, OpenAPI, encryption | planned |
| M6 Docs and 0.1.0 | Documentation site and first release on Maven Central | planned |
| M7 1.0 | WebFlux, API compatibility checks, published benchmarks | planned |

The [specification](docs/SPEC.md) has the semantics, requirements, architecture, decisions and the task list for each milestone.

## Related projects

- [careflow](https://github.com/eduardo-alecrim/careflow): event-driven prior authorization for health plans; its intake API is the first consumer of this library.
- [legacy-modernization](https://github.com/eduardo-alecrim/legacy-modernization): a SOAP and Oracle monolith migrated to Quarkus with the strangler fig pattern.
- [trilho](https://github.com/eduardo-alecrim/trilho): a distributed commit log built from scratch in Java.

## License

[Apache License 2.0](LICENSE).

---

Built by [Eduardo Alecrim](https://eduardo-alecrim.github.io).
