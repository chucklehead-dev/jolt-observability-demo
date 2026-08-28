# On-demand instrumentation spike

Status: synchronous call-site and fixed-arity function-entry V1 implemented
and integration-gated, 2026-08-28. The reusable DB, HTTP client, and HTTP
server packages are published. The Ring provider now owns callback completion
and a post-span completion hook without adding an async primitive to the
compiler contract.

## Goal

Build applications in two modes from the same application and library sources:

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
`META-INF/jolt/aspects/http-server.edn`. The manifest contains no executable forms
and names semantic roles rather than OTel namespaces:

```clojure
{:schema 1
 :library {:id casselc/jolt-http
           :version "<exact source revision>"}
 :aspects
 [{:id :http/server-ring-handler
   :match {:entry jolt.http.protocol/invoke-handler :arity 8}
   :advice-role :http/server
   :expect {:matches 1}}
  {:id :http/server-sanitized-response
   :match {:entry jolt.http.protocol/sanitize-response :arity 1}
   :advice-role :http/server-response
   :expect {:matches 1}}]}
```

Library maintainers update the drift-sensitive selector with the code. A
provider package such as `jolt-otel-instrumentation-http` statically maps
`:http/server` to advice. An application must explicitly select both pieces;
dependency manifests never activate themselves.

The same package may export a pure presentation adviser for the telemetry it
defines. Advisers attach standard Kindly value metadata at render time rather
than changing span storage. This keeps join-point vocabulary, emitted
attributes, privacy policy, and recommended rendering together while the
viewer remains domain-neutral. The viewer-specific option namespace is only a
layout vocabulary; payloads use ordinary `:kind/*` values.

The embedded Samizdat demo uses the implemented application configuration:

```clojure
{:jolt/build
 {:aspects
  [{:resource "META-INF/jolt/aspects/samizdat-m2-embed.edn"
    :provider demo.samizdat-aspect-provider}
   {:resource "META-INF/jolt/aspects/samizdat-m2-core.edn"
    :provider demo.samizdat-aspect-provider}]
  :aspect-report "target/samizdat-aspects.edn"}}
```

The manifests are published by the pinned Samizdat fork under
`resources/META-INF/jolt/aspects/`; the demo no longer carries copies. Their
compatibility id is the source revision whose entry and call-site surface they describe,
while resource-only commits may advance independently. Samizdat itself has no
OTel dependency.

## Weaver contract

The production weaver runs after macro expansion and name resolution, but
before inference, inlining, direct linking, and tree shaking. It:

- matches either a qualified fixed-arity function definition entry or a
  resolved namespace, qualified call, and arity; never source line numbers;
- fails the build when an aspect has zero or ambiguous matches, an unsupported
  schema, an incompatible library revision, or a missing provider role;
- preserves source position metadata for stack traces;
- emits a deterministic weave report listing each selected aspect and match;
- includes the weaver version, manifest bytes, provider mapping, and selected
  aspects in the AOT cache key so plain and instrumented artifacts cannot
  collide; and
- statically links advice so ordinary optimization and dead-code elimination
  still apply.

V1 has three synchronous contracts. `:proceed-v1` receives
`[join-point proceed]`; `:args-v1` additionally receives the already-evaluated
argument vector; `:replace-args-v1` may call `proceed` with an exact-arity
replacement vector, which the demo uses to inject W3C Trace Context into the
maintained HTTP client call. Arguments evaluate once. The target executes once.
Its exact result or exception wins, and missing, repeated, or failing advice
fails open without retrying an application operation that already began.

V1 does not ask the compiler to understand async completion. The HTTP server
provider uses `:replace-args-v1` to replace the normalized Ring handler with a
wrapper that owns the existing `respond`/`raise` callbacks. It serializes their
terminal decision, restores the captured OTel context, ends exactly once after
the accepted callback, and then invokes an observational completion hook. That
hook lets the embedded collector durably flush the completed server span before
a redirect triggers the next viewer query. This is Ring callback completion,
not proof that bytes reached the peer.

## Verified integration

The current woven Samizdat artifact produces this real control-loop trace:

```text
samizdat.run
  `-- samizdat.turn
       `-- samizdat.model
            +-- HTTP POST client
            `-- samizdat.tool
```

`test/samizdat_playwright_e2e.sh` proves the exact submitted coding prompt
drives the real embedded Samizdat loop, every model request carries a valid
`traceparent` matching the stored HTTP client span, durable events arrive over
SSE, the tool edits and verifies the fixture project, and the stored spans have
the expected parentage. The default run omits model content;
`test/samizdat_real_run_smoke.sh` proves the explicit bounded-capture mode.

`test/aspect_build_smoke.sh` proves the same provider boundary with a bounded
non-OTel event journal. In the compiler repository, `make aspectsmoke` covers
plain, release, dev, optimized, no-direct-link, and no-whole-program modes. Its
standalone native-error fixture combines an aspect-selected call with
`{:blocking true :capture-native-error true}` and proves advice cannot change
the exact `[result native-error]` pair.

`test/aspect_demo_e2e.sh` runs the same live-update, editor, oscope, and
no-JavaScript browser story first from source and then from a woven native
binary. Source mode retains five explicit fallback spans tagged
`demo.instrumentation.mode=source-fallback`. Woven mode has six spans, uses the
generic DB and HTTP providers, rejects every fallback tag, and verifies that
the post-span completion hook prevents partial traces after redirects.

## Next contracts and packages

1. Extend the now-tested fixed-arity function-entry selector only when a real
   anonymous, variadic, or generated-method seam requires a new contract.
2. Generalize callback completion only when another library cannot express its
   lifecycle by wrapping already-explicit callback arguments.
3. Keep the generic client's trace-only injection boundary explicit. The
   server package now accepts a caller-selected inbound propagator and fails
   closed to a fresh root for invalid or throwing configurations; retain the
   woven malformed Trace Context and baggage-privacy matrix as a release gate.
4. Keep Kindly presentation advisers beside each instrumentation vocabulary,
   while leaving stored telemetry and generic viewer APIs presentation-free.

## Acceptance gates

- Base library dependency graphs contain no OTel provider.
- Plain source builds without an aspect provider and emits no generated spans.
- Instrumented dev, release, optimized, no-direct-link, and no-whole-program
  builds preserve application behavior.
- Rebuilding with the same inputs produces the same weave report and identity.
- Changing selected manifest or provider material changes the identity.
- Deliberately stale, overlapping, or incompatible selectors fail before
  publishing a replacement artifact/report pair.
- Advice never captures DB parameters, request bodies, credentials, or arbitrary
  headers by default.
- Playwright observes generated run/turn/model/tool/client spans without a page
  reload, and the viewer/workbench routes cannot instrument themselves.
- Library-supplied presentation advice follows Kindly scalar wrapping and
  metadata rules, is bounded by the host, and never enters persisted telemetry.

The DB and HTTP packages, callback-owned server lifecycle, compiler, and
Samizdat integration no longer depend on a proposed or private compiler hook.
