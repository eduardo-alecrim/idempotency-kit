# Leases with owner tokens instead of distributed locks

A claim is a conditional insert or update that carries a lease and a random owner token; completion and release require the token. The store's own atomic primitives are enough, and the token fences a slow first owner after a takeover. A lock service would add a dependency and a second failure mode.

## Considered options

- Redisson or ShedLock style locks.
- `SELECT … FOR UPDATE` held for the whole request, which ties a connection to every running request.
