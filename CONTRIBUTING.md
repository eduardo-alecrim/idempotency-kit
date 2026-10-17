# Contributing

## Build

You need JDK 17 or newer; CI builds on 17, 21 and 25.

```shell
./mvnw verify            # compile, format check, tests, coverage gates
./mvnw spotless:apply    # format the code and add license headers
```

On JDK 25 the build also runs Error Prone and NullAway. Store tests use Testcontainers and need a running Docker daemon.

## Workflow

- Every change starts from an issue. Pick one from the [milestones](https://github.com/eduardo-alecrim/idempotency-kit/milestones) or open one.
- One branch and one pull request per issue. Name the branch `<type>/<milestone>-<topic>`, for example `feat/m2-jdbc-claim`.
- The pull request title follows [Conventional Commits](https://www.conventionalcommits.org/), with the module as scope when there is one: `feat(core): parse Structured Field keys`, `fix(store-jdbc): map SQL Server duplicate-key errors`. Pull requests are squash-merged, so the title becomes the commit message and feeds the changelog.
- Scopes: `core`, `store-jdbc`, `store-redis`, `spring`, `quarkus`, `client`, `tck`, `docs`, `build`.
- A pull request merges when CI is green.

## Code

- Use the terms in [GLOSSARY.md](GLOSSARY.md). Decisions live in [docs/adr](docs/adr); changing one means a new ADR that supersedes it.
- Every Java file starts with `// SPDX-License-Identifier: Apache-2.0`; `spotless:apply` adds it.
- Packages are `@NullMarked`; mark what can be null with `@Nullable`.
- Public types have Javadoc. Comments are short and say why, not what.
- `idempotency-core` has no runtime dependencies ([ADR 0003](docs/adr/0003-zero-runtime-dependencies-in-core.md)).
- Packages named `internal` are not API.

## Tests

- A test that proves a requirement names it in `@DisplayName`, for example `@DisplayName("FR-06: a different payload with the same key answers 422")`, and the pull request references it.
- Parsers and codecs get jqwik property tests.
- A store or adapter is correct when it passes the TCK in `idempotency-test-kit`.

## Reporting

Bugs and feature requests go through the issue templates. Vulnerabilities go through [SECURITY.md](SECURITY.md), never a public issue.
