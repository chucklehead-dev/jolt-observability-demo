# On-demand instrumentation spike

Status: proposed proof, 2026-08-27. This document defines an experiment; Jolt
does not yet expose the required supported compiler extension point.

## Goal

Build the demo in two modes from the same application and library sources:

- a plain artifact with no OpenTelemetry dependency introduced into `db`,
  `jolt-http`, or `http-client`; and
- an instrumented artifact that produces DB, HTTP client, and HTTP server spans
  from separately selected instrumentation packages.

This follows the separation used by OpenTelemetry Go compile instrumentation
[`otelc`](https://github.com/open-telemetry/opentelemetry-go-compile-instrumentation)
and the aspect/join-point/advice model documented by
[Orchestrion](https://github.com/DataDog/orchestrion). It is build-time weaving,
not runtime var replacement or Linux-only eBPF probing.

## Ownership boundary

Each base library owns an inert, provider-neutral EDN resource such as
`META-INF/jolt/aspects/jolt-http.edn`. The manifest contains no executable forms
and names semantic roles rather than OTel namespaces:

```clojure
{:schema 1
 :library {:id io.github.casselc/jolt-http
           :version "<exact source revision>"}
 :aspects
 [{:id :http/server-request
   :match {:ns jolt.http.protocol
           :call jolt.http.protocol/invoke-handler
           :arity 7}
   :advice-role :http/server
   :expect {:matches 1}}]}
```

Library maintainers update the drift-sensitive selector with the code. A
provider package such as `jolt-otel-instrumentation-http` statically maps
`:http/server` to advice. An application must explicitly select both pieces;
dependency manifests never activate themselves.

A possible application configuration is:

```clojure
{:jolt/aspects
 [{:resource "META-INF/jolt/aspects/db.edn"
   :provider io.github.chucklehead-dev/jolt-otel-instrumentation-db}
  {:resource "META-INF/jolt/aspects/http-client.edn"
   :provider io.github.chucklehead-dev/jolt-otel-instrumentation-http-client}]}
```

## Weaver contract

The production weaver runs after macro expansion and name resolution, but
before inference, inlining, direct linking, and tree shaking. It must:

- match resolved namespaces, vars or calls, arity, and optional protocol
  identity; never source line numbers;
- fail the build when an aspect has zero or ambiguous matches, an unsupported
  schema, an incompatible library revision, or a missing provider role;
- preserve source position metadata for stack traces;
- emit a deterministic weave report listing each selected aspect and match;
- include the weaver version, manifest bytes, provider mapping, and selected
  aspects in the AOT cache key so plain and instrumented artifacts cannot
  collide; and
- statically link advice so ordinary optimization and dead-code elimination
  still apply.

The minimal synchronous advice shape is conceptually
`(around join-point normalized-input proceed)`. `proceed` is zero-arity and may
be invoked exactly once. Application results and exception identity pass
through unchanged. Instrumentation failures fail open by running the operation,
but must not swallow an application exception.

Async advice additionally owns an explicit exactly-once completion token and
may wrap callbacks. Ambient thread bindings are insufficient after a handler
returns; use the existing `otel.context/bind-fn*` behavior when work crosses an
executor boundary.

## Demo proof sequence

1. Weave the eager `db.driver/execute-handle` call in `db.jdbc-shim`. Record a
   low-cardinality DB operation span without parameter values. Use `internal`
   kind for embedded chDB and `client` for network PostgreSQL.
2. Build and run a tiny plain/instrumented pair. Prove identical results,
   identical thrown application exceptions, zero spans when plain, one span
   when instrumented, and distinct cache/artifact identities under a normal
   direct-linked release build.
3. Weave the actual HTTP client request/attempt boundary, inject W3C
   `traceparent`, and remove the handwritten client span from
   `demo.main/real-work!`.
4. Weave the HTTP server handler plus `respond`/`raise` lifecycle. Extract the
   remote parent before calling the handler and end the span exactly once when
   the response completes or fails, including async handlers.

The target demo trace is:

```text
HTTP POST /work server
  +-- db/execute
  `-- HTTP GET /upstream client
       `-- HTTP GET /upstream server
            `-- db/execute or queue consume
```

The existing private-pass tap in `jolt-sim/perturb/src/perturb/ir.clj` may be
used to validate one IR transformation. It is load-order-sensitive and does not
cover the normal closed-world dependency build, so it is not an acceptable
published integration. Promotion requires a supported compiler/build hook that
loads selected manifests before dependency analysis.

## Acceptance gates

- Base library dependency graphs contain no OTel provider.
- Plain source builds without an aspect provider and emits no generated spans.
- Instrumented dev and direct-linked release builds emit the same parent-linked
  trace.
- Rebuilding with the same inputs produces the same weave report and cache key.
- Changing a selected manifest or provider changes the cache identity.
- A deliberately stale selector fails with a useful zero-match report.
- Advice never captures DB parameters, request bodies, credentials, or arbitrary
  headers by default.
- Playwright observes the generated DB/client/server spans in the trace dialog
  without a page reload.
