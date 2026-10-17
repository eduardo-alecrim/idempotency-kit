# The Quarkus extension targets the 3.x LTS until a 4.x LTS exists

The Quarkus extension builds against Quarkus 3.40 LTS, and CI blocks on it; a nightly job against the latest 4.x reports breakage without blocking. Quarkus 4.0, planned for late November 2026, moves to Java 21, Jackson 3 and Vert.x 5, so one extension artifact cannot serve both lines. Quarkus 3.40 is supported until September 2027, while the first 4.x LTS is only proposed for March 2027. When a 4.x LTS ships, a new ADR decides between a second artifact and a migration.

## Considered options

- Quarkus 4.x only: excludes every 3.x user and forces Java 21 on the extension.
- Both lines from the start: two extension artifacts before the first release.
