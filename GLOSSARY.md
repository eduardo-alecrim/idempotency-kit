# idempotency-kit

Server-side support for the `Idempotency-Key` HTTP header: the first request with a key runs, and retries with the same key get its stored response instead of running again.

## Language

### Requests

**Idempotency key**:
The value a client sends in the `Idempotency-Key` header to mark one logical operation across its retries.
_Avoid_: request id, key (on its own)

**Idempotent operation**:
A method and route template pair that the library protects.
_Avoid_: idempotent route, idempotent endpoint

**Route template**:
The path pattern under which the application declares an operation, such as `/orders/{id}`.
_Avoid_: path, URL

**Handler**:
The application code that an idempotent operation runs.
_Avoid_: endpoint, controller method

**Client identity**:
Who sent a request, as resolved from the authenticated principal or a configured header.
_Avoid_: client id, user, principal

**Anonymous client**:
The single client identity shared by every request that carries no identity.

**Scope**:
Whether the idempotency keys of an operation are separate per client identity (client scope) or shared by every client (global scope).

**Scope key**:
The identity of a record, derived from the client identity (in client scope), the method, the route template and the idempotency key.
_Avoid_: record id

**Fingerprint**:
A digest of the parts of a request that decide whether two requests are the same.
_Avoid_: payload hash, checksum

### Records

**Record**:
What a store keeps for one scope key: its state, its fingerprint and, once completed, the stored response.
_Avoid_: entry, cache entry

**Claim**:
The atomic attempt to create an in-progress record, which either acquires the scope key or reports the record that already holds it.
_Avoid_: lock, reservation

**Owner token**:
The proof, issued by a successful claim, that a request holds an in-progress record.
_Avoid_: lock id

**Lease**:
How long an in-progress record protects a running request; once it ends, the record counts as absent.
_Avoid_: lock timeout

**Takeover**:
A claim that acquires a scope key whose in-progress record has an expired lease.

**Lease lost**:
The state of a request whose record was taken over before it finished; its completion is refused.

**Completion**:
Writing the stored response of a finished request into its record.

**Release**:
Deleting an in-progress record so that a retry runs the handler again.
_Avoid_: unlock, rollback

**TTL**:
How long a completed record is kept and replayed, counted from its completion.
_Avoid_: retention

**Storable status**:
A response status that the status policy keeps; any other status releases the record.
_Avoid_: storable outcome

**Stored response**:
The status, allowlisted headers and body kept in a completed record.
_Avoid_: cached response

### Handling a request

**Outcome**:
How the library handled one request, such as executed, replayed or key reuse.
_Avoid_: result

**Replay**:
Answering a retry with the stored response instead of running the handler.
_Avoid_: cache hit

**Key reuse**:
An idempotency key sent again with a different fingerprint.
_Avoid_: payload mismatch, mismatch

**Request in progress**:
A retry that arrives while an earlier request with the same scope key still holds its lease.
_Avoid_: conflict

**Bypass**:
Running the handler without protection, either because an optional operation received no key or because the store failed under fail-open.
_Avoid_: skip

### Components

**Store**:
Where records live: memory, a relational database or Redis.
_Avoid_: repository, cache

**Adapter**:
The integration of the library with one web framework.
_Avoid_: plugin, integration

**TCK**:
The test suite that every store and adapter must pass.
_Avoid_: conformance suite
