# The scope key is a hash

A record's primary key is the SHA-256 of the length-prefixed client identity, method, route template and idempotency key, as 64 hex characters; the raw parts are stored for diagnostics only. It gives a fixed-size index on every vendor, no index-length limits for long keys or routes, and no delimiter ambiguity.
