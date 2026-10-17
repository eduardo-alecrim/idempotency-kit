# Framework-free core with thin adapters

All protocol logic lives in `idempotency-core`; adapters only translate their request and response types and act on the engine's `Decision`. One implementation of the semantics means one TCK, and a new adapter costs little.

## Considered options

- A Spring-only library: smaller, but the Quarkus behaviour would drift.
- AOP around controller methods: cannot see the serialized bytes or the response headers.
