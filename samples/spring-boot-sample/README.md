# Spring Boot sample

A Spring MVC application with one idempotent operation, `POST /orders`, on the in-memory store.

## Run

```bash
./mvnw -pl samples/spring-boot-sample -am install -DskipTests
./mvnw -pl samples/spring-boot-sample spring-boot:run
```

The application listens on port 8080. At startup it logs the operation and its policy:

```text
Idempotent operation POST /orders (ttl 1d, lease 30s, key required, client scope)
```

It also warns that the in-memory store is not shared between instances.

## Try it

[`orders.http`](orders.http) has each case as a request, for the HTTP client of IntelliJ IDEA or VS Code. With curl:

```bash
# First call: 201, order 1
curl -i localhost:8080/orders -H 'Content-Type: application/json' \
  -H 'Idempotency-Key: "8e03978e-40d5-43e8-bc93-6894a57f9324"' -d '{"item":"book","quantity":1}'

# Same key and body: the same 201 and order 1, with Idempotent-Replayed: true
curl -i localhost:8080/orders -H 'Content-Type: application/json' \
  -H 'Idempotency-Key: "8e03978e-40d5-43e8-bc93-6894a57f9324"' -d '{"quantity":1,"item":"book"}'

# Same key, different body: 422 key-reuse
curl -i localhost:8080/orders -H 'Content-Type: application/json' \
  -H 'Idempotency-Key: "8e03978e-40d5-43e8-bc93-6894a57f9324"' -d '{"item":"pen","quantity":2}'
```

`POST /orders/slow` takes ten seconds. A second request with its key, sent before the first one finishes, gets `409 request-in-progress` with `Retry-After`.

The key is a quoted string, as RFC 9651 defines it. To accept bare UUIDs as well, set `idempotency.key.parsing=lenient`.
