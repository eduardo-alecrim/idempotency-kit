# Quarkus uses its own Redis client

The Quarkus extension implements the Redis store on the Quarkus Redis client; `idempotency-store-redis`, built on Lettuce, serves plain Java and Spring. The Quarkus client shares Vert.x with the application, needs no extra native-image configuration and is configured through `quarkus.redis.*`. Both implementations pass the same TCK.
