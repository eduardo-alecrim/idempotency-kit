# Release with JReleaser through the Central Portal

JReleaser signs the artifacts, deploys them to Maven Central through the Central Portal, creates the GitHub release with the changelog and attaches the SBOMs. One tool and one configuration file cover the whole release, and it runs locally in dry-run mode.

## Considered options

- Sonatype's `central-publishing-maven-plugin` plus separate changelog and release tooling.
