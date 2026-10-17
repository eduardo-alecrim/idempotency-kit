# Time comes from the application clock, in every store

Stores receive `now`, `leaseUntil` and `expiresAt` from the engine's `Clock` and decide expiry by comparing them, never by a clock of their own. Tests drive every store with one `MutableClock`, and databases with different time functions behave the same.

This includes Redis: claim, completion and release run as Lua scripts that compare the stored lease and TTL against the caller's `now`. The key's `PX` expiry only bounds memory and is set to the remaining time plus a fixed margin. Each script touches one key, so Redis Cluster still works.

## Considered options

- `SET NX PX` with Redis-side expiry: the Redis server clock would decide expiry, a `MutableClock` could not advance it, and the TCK's expiry tests would need real sleeps.

## Consequences

Instance clocks must be synchronised, and the lease must exceed the worst clock skew.
