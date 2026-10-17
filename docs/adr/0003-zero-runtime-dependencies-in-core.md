# Zero runtime dependencies in core

`idempotency-core` depends only on the JDK: logging goes through `System.Logger` and JSON canonicalization uses an internal parser. Spring Boot 4 defaults to Jackson 3 while Quarkus 3 uses Jackson 2, so depending on either would force a version on half the users. A `maven-enforcer` rule bans new dependencies.

## Considered options

- Jackson: version conflicts between the two frameworks.
- SLF4J API: an extra dependency for a handful of log statements.

## Consequences

`System.Logger` reaches SLF4J through `slf4j-jdk-platform-logging`, declared by the Spring starter, and Quarkus logging through its JUL bridge.
