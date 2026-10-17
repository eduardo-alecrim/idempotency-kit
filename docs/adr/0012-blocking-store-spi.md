# Blocking store SPI

The store SPI is synchronous; reactive adapters offload store calls to a worker pool. JDBC is blocking, virtual threads make blocking cheap, and one SPI keeps stores simple. A reactive SPI can be added later without breaking this one.
