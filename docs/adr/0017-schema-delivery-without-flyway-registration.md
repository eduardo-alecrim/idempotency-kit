# Schema delivery without automatic Flyway registration

The JDBC module ships vendor DDL, a Liquibase changelog to include, and Flyway-ready SQL that applications copy under their own next version number; `initialize-schema` covers embedded databases and quick starts. Liquibase changesets are identified by id and author and include cleanly, while a shipped Flyway `V1__` script would collide with the application's own version sequence.
