# An expired record is an absent record

In every store, an in-progress record whose lease has ended and any record past its TTL behave as if they did not exist, whatever their fingerprint. Redis drops expired keys while the JDBC store keeps rows until the purge job runs; treating both as absent gives every store the same behaviour, so one TCK covers them all.
