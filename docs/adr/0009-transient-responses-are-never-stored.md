# Transient responses are never stored

`5xx` responses, exceptions, `408`, `425` and `429` release the record; every other `2xx` and `4xx` status is a storable status. Replaying a stored `500` would turn a transient failure into a permanent one. A `400` is deterministic and is replayed, so a corrected payload sent with the old key gets `422` instead of a new execution.
