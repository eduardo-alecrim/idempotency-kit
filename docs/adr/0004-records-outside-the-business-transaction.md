# Records live outside the business transaction

The JDBC store uses its own auto-commit connections, so a claim is visible to other instances as soon as it is made. Inside the business transaction a claim would stay invisible until commit, so concurrent retries would all pass, and a rollback would erase it.

## Consequences

Completion is not atomic with the handler's writes. A crash between the handler's commit and completion lets a retry run again once the lease ends. A transactional mode is a future idea.
