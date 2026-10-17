# Stored headers use HTTP field lines

Stored response headers are `Name: value` lines separated by CRLF. They stay readable in SQL clients and need no JSON library ([ADR 0003](0003-zero-runtime-dependencies-in-core.md)). HTTP guarantees that header values contain no line breaks, so the format needs no escaping.
