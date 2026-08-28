# Embedded observability demo contract

This is the frozen implementation contract for the first demo. It is a Jolt
program using `jolt-http`'s Ring-shaped handler, `jolt-lang/http-client`,
`jolt-otel`, `jolt-chdb`, and `jolt-otel-clickhouse`.

## Ownership and lifecycle

- Open one application-owned `jdbc.core` connection to `chdb::memory:` by
  default; allow `DEMO_CHDB_SPEC` to select a persistent `chdb:` path.
  Persistent demo data is operator-owned: choose a disposable path or establish
  retention and deletion policy before reuse. The demo never deletes it.
- Build `otel.exporter.chdb/exporter` with `{:connection conn}` and initialize
  `otel.sdk` with service `jolt-observability-demo`, logs enabled, and batching.
- Start `jolt.http.server/run-server`; `DEMO_PORT` defaults to 8080.
- Shutdown order is HTTP server, OTel SDK (flush), then database connection.
- No endpoint accepts raw SQL, filesystem paths, or arbitrary outbound URLs.

## Routes

- `GET /` returns server-rendered Selmer HTML with summary cards, recent traces,
  and recent logs. Basic operation requires no JavaScript or polling.
- `GET /traces/<32 lowercase hex chars>` returns a server-rendered trace detail
  with semantic `details`/`summary` span rows, hierarchy, timing, attributes,
  and correlated logs. Other identifiers return 400 without querying.
- `POST /work` generates the same fixed workload, flushes after the request span
  closes, and redirects to `/` with 303 for ordinary HTML form use.
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
  also performs a real embedded chDB readiness query and propagates W3C context
  through a producer/consumer queue envelope. Together these create six
  parent-linked server, DB, messaging, client, and loopback-server spans with
  correlated INFO logs; errors are recorded and return 502.
- `GET /upstream` returns a fixed JSON payload and records one server span/log.
- `POST /agent-work` and `POST /agent-work-with-response` run one
  Lemonade-backed, Samizdat-shaped control-loop turn. Both record a root run,
  control loop, branch, turn, logical generation, physical HTTP attempt, and
  tool span. The first is metadata-only; the second explicitly records a
  bounded prompt and response after common delimited thinking output is
  stripped.
- `POST /agent-work-intervention` runs two model turns and records a controller
  intervention between them. The second generation prompt includes the first
  response and bounded controller revision, making the control-loop decision
  inspectable. Explicit capture never includes physical model hostnames,
  credentials, or tool arguments; provider thinking is disabled by default.
- `POST /v1/traces` accepts OTLP/HTTP JSON traces through the host-agnostic
  `otel.otlp.http-receiver`. The demo parser measures actual UTF-8 encoded bytes,
  limits bodies to 1 MiB, rejects compression, and admits one parse/export at a
  time because all signals share one embedded chDB connection.
- Any other route returns 404. Unsupported methods return 405.

## Telemetry and queries

- Application requests are wrapped in a server span named
  `HTTP <METHOD> <route>` with `http.request.method`, `http.route`, and
  `url.path`; response status and exceptions are recorded. Viewer and
  JSON-query routes are excluded so the viewer does not observe its own reads.
- `/work` demonstrates W3C Trace Context across a real HTTP boundary: the client
  injects `traceparent`, the Ring edge extracts it, and the local server, client,
  and upstream server spans remain in one parent-linked trace.
- Query the ClickStack-aligned columns frozen by `jolt-otel-clickhouse`:
  `Timestamp`, `TraceId`, `SpanId`, `ParentSpanId`, `ServiceName`, `SpanName`,
  `SpanKind`, `Duration`, `StatusCode`, `StatusMessage`, `Body`,
  `SeverityText`, and attribute maps.
- The UI must still render an empty state before any `/work` request.
- The initial viewer is complete server-rendered HTML. When Datastar is
  available, a bounded SSE stream refreshes only `#otel-live` from durable chDB
  snapshots every 750ms and emits a 2s heartbeat. Keeping the heartbeat below
  the HTTP server's shutdown deadline prevents disconnected unchanged streams
  from stranding shutdown workers. At most eight streams may be
  active, leaving normal jolt-http worker capacity reserved.
- With JavaScript available, Generate work posts in place and lets the existing
  SSE stream render the result. The static form retains POST/redirect behavior
  without JavaScript; enhanced use avoids closing and reopening the live stream.
- Viewer HTML, assets, JSON APIs, trace detail, and SSE snapshot rendering are
  excluded from instrumentation so observing telemetry cannot recursively
  generate more viewer telemetry.
- Samizdat-specific rendering advice is attached only to the trace tree passed
  to HTML rendering. It follows Kindly value metadata (`:kindly/kind`,
  `:kindly/options`, including scalar `:wrapped-value`) and never changes the
  chDB rows or JSON trace-detail representation.
- `/v1/traces` is marked by the receiver suppression wrapper before HTTP
  instrumentation inspects the request. Receiver parsing, export responses, and
  subsequent viewer reads therefore cannot create an ingest feedback loop.
- Every non-success OTLP response closes its HTTP/1.1 connection. The pinned
  jolt-http host has no request-body cancellation method; connection teardown
  closes its bounded body channel and releases a producer when policy rejects
  before consuming the body, the measured cap is crossed, or concurrency is
  exhausted.
- OTLP export is synchronous and has no fabricated timeout: the embedded chDB
  query cannot be cancelled safely, so concurrency and body limits are the
  truthful bounds for this initial receiver.

## Verification

- Pure handler tests cover HTML and JSON representations, 200/303/400/404/405
  behavior, hostile-value escaping, semantic markup, the zero-JavaScript
  fallback, and the reusable fragment renderer.
- Datastar stream tests cover the fixed selector, bounded admission, changing
  snapshots, explicit non-closing sink flushes, disconnect cleanup, and
  rejection of malformed SSE flags.
- An integration test starts the server on a non-default port, calls `/work`
  with `jolt.http-client`, flushes telemetry, and proves the complete external
  parent/server/client/upstream parent chain, correlated logs, and HTML detail.
- A second live integration posts a parent/child OTLP JSON trace, proves the
  hierarchy through the JSON API and server-rendered viewer, observes it on an
  already-open SSE stream, and verifies the receiver/viewer added no spans.
- `scripts/probe-threadstatus.sh` runs startup, ordinary work, synchronous
  enhanced POST/flush, viewer polling,
  SSE disconnect, concurrent SSE/work, repeated OTLP ingest, concurrent
  SSE/OTLP ingest, and a mixed stress scenario in separate
  fresh Jolt processes. It preserves per-scenario stdout/stderr evidence and
  fails if the native ClickHouse `ThreadStatus` diagnostic appears.
  Each child runs under a pseudo-terminal because libclickhouse suppresses this
  diagnostic when stderr is redirected; the gate strips terminal color before
  matching the transcript.
  `THREADSTATUS_PROBE_SCENARIOS` selects cases and
  `THREADSTATUS_PROBE_REPEAT` repeats each in a fresh process for soak runs.
- Publication and CI commands use Jolt v0.7.27 and writable cache directories.
  Workspace verification sets `JOLT_WRAPPER` to the required Chez 10.4.1
  wrapper. Verification does not modify dependency sources.
- Playwright drives Chromium over the real HTTP server, proves the initial SSE
  response and post-work patch arrive before stream close, rejects browser
  console/page errors, verifies the dialog without navigation, and separately
  runs the zero-JavaScript POST/303/detail/back path. The documentation capture
  reuses the same semantic story.
- The persistent Playwright gate records a trace in a filesystem-backed chDB,
  terminates the complete Jolt process, starts a second process over the same
  path, and opens that exact trace with all six spans. Both process transcripts
  must remain free of the native ClickHouse `ThreadStatus` diagnostic.
- An optional live-model Playwright project calls all three agent routes,
  verifies the one- and two-turn control-loop hierarchy and privacy state,
  captures docs screenshots, and rejects the configured physical model
  hostname anywhere in rendered HTML. Its telemetry address is an explicit
  neutral override. The checked-in GIF runs the same story against a local
  deterministic model fixture.

## Adoption boundaries

The pinned `io.github.chucklehead-dev/jolt-otel-viewer` package supplies
`otel.viewer/render-fragment`, `otel.viewer/render-page`,
`otel.viewer/styles`, and `otel.viewer/enhancement-script` as pure rendering
seams over bounded host-supplied data. The optional enhancement opens trace
links in a native dialog with native focus management and Escape-to-close.
Ordinary trace links and the explicit `All traces` link remain the fallback
when scripts or native dialog support are unavailable.

The demo uses `jolt-lang/glimmer-datastar` for v1 patch-event encoding. Its
small viewer-package script opens and applies those events without Datastar's
expression evaluator, preserving the strict `script-src 'self'` CSP without
requiring `unsafe-eval`.
Its bounded streaming body is currently a jolt-http-specific adapter; a host
must choose stream capacity relative to its worker pool rather than inheriting
the demo's eight-stream limit blindly.
A host such as Samizdat owns routing, authentication/CSRF, database queries, and
work actions; it can mount the fragment under its own shell without adopting
the demo lifecycle.

This demo exercises the embedded exporter and query seam only. Exact ClickStack
collector schema compatibility, supported Langfuse ingestion, and Samizdat's
derived-observability lifecycle remain separate gates documented in
`../jolt-otel-clickhouse/docs/clickstack-compatibility.md`,
`../jolt-otel-clickhouse/docs/langfuse-bridge.md`, and
`../jolt-otel-clickhouse/docs/samizdat-adoption.md`.
