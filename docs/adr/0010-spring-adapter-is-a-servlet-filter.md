# The Spring adapter is a servlet filter

`IdempotencyFilter` wraps the request and response and finds the handler through the handler mapping. Only a filter can buffer the request body before argument resolution and capture the final status, headers and bytes after message conversion and exception handling.

## Considered options

- `HandlerInterceptor`: cannot replace the response object.
- `ResponseBodyAdvice`: no status on exception paths and no serialized bytes.
