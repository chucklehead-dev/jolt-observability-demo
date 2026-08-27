# Embedded observability demo contract

This is the frozen implementation contract for the first demo. It is a Jolt
program using `jolt-http`'s Ring-shaped handler, `jolt-lang/http-client`,
`jolt-otel`, `jolt-chdb`, and `jolt-otel-clickhouse`.

## Ownership and lifecycle

- Open one application-owned `jdbc.core` connection to `chdb::memory:` by
  default; allow `DEMO_CHDB_SPEC` to select a persistent `chdb:` path.
- Build `otel.exporter.chdb/exporter` with `{:connection conn}` and initialize
  `otel.sdk` with service `jolt-observability-demo`, logs enabled, and batching.
- Start `jolt.http.server/run-server`; `DEMO_PORT` defaults to 8080.
- Shutdown order is HTTP server, OTel SDK (flush), then database connection.
- All SQL result data sent to clients is JSON encoded. No endpoint accepts raw
  SQL, filesystem paths, or arbitrary outbound URLs.

## Routes

- `GET /` returns a dependency-free HTML/CSS/JS UI with summary cards, recent
  traces, and recent logs. Its JavaScript polls `/api/summary`, `/api/traces`,
  and `/api/logs` every two seconds and escapes all displayed text.
- `GET /api/summary` returns `{traceCount, spanCount, logCount, errorCount}`.
- `GET /api/traces` returns at most 100 newest trace summary objects containing
  `traceId`, `startedAt`, `durationNs`, `service`, `rootSpan`, `spanCount`, and
  `status`.
- `GET /api/traces/<32 lowercase hex chars>` returns its ordered spans and
  correlated logs. Other identifiers return 400 without querying.
- `GET /api/logs` returns at most 100 newest logs with timestamp, severity,
  service, body, traceId, and spanId.
- `GET /work` performs one fixed outbound request to this same server's
  `/upstream`, using `jolt.http-client` with explicit connect/read timeouts. It
  creates a server span, a nested client span, and correlated INFO logs; errors
  are recorded and return 502.
- `GET /upstream` returns a fixed JSON payload and records one server span/log.
- Any other route returns 404. Non-GET methods return 405.

## Telemetry and queries

- Every request is wrapped in a server span named `HTTP <METHOD> <route>` with
  `http.request.method`, `http.route`, and `url.path`; response status and
  exceptions are recorded.
- `/work` demonstrates propagated nesting inside a single process. Network
  trace-header injection is optional in this first demo because both spans are
  created explicitly, but no fabricated IDs are allowed.
- Query the ClickStack-aligned columns frozen by `jolt-otel-clickhouse`:
  `Timestamp`, `TraceId`, `SpanId`, `ParentSpanId`, `ServiceName`, `SpanName`,
  `SpanKind`, `Duration`, `StatusCode`, `StatusMessage`, `Body`,
  `SeverityText`, and attribute maps.
- The UI must still render an empty state before any `/work` request.

## Verification

- Pure handler tests cover 200/400/404/405 behavior and JSON shapes.
- An integration test starts the server on a non-default port, calls `/work`
  with `jolt.http-client`, flushes telemetry, and proves at least one parent/
  child trace plus a correlated log can be queried through the API.
- All commands use `/home/chuck/ai-src/tools/jolt-with-chez-10.4.1` and writable
  cache directories. The demo does not commit, publish, push, or modify its
  local dependencies.
