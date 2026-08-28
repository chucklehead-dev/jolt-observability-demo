# Jolt observability demo

A self-contained Ring application that receives and generates OpenTelemetry
traces, logs, and metrics, stores them in embedded chDB, and serves a lightweight
trace workbench. The initial page and trace details are server-rendered; the SSE
enhancement streams durable updates without instrumenting the viewer's own
requests.

## Run it

Install [Jolt](https://github.com/jolt-lang/jolt), then install the native chDB
library pinned by this project:

```sh
jolt -M:setup-native
jolt -m demo.main
```

Open <http://127.0.0.1:8080/> and select **Generate work**. The resulting trace
contains six parent-linked spans across an embedded DB query, a propagated
producer/consumer queue handoff, and a real loopback HTTP client/server boundary,
with correlated logs. Traces and logs appear in the open page through a bounded
SSE stream; the page remains usable without JavaScript.

![Trace waterfall with DB, queue, and HTTP spans](docs/screenshots/03-trace-waterfall-dialog.png)

For the Samizdat-shaped model trace, point the demo at an OpenAI-compatible
Lemonade server and use either model action in the header:

```sh
DEMO_LEMONADE_BASE_URL=http://model-host.example:8000/v1 \
DEMO_LEMONADE_MODEL=local-model \
DEMO_LEMONADE_DISABLE_THINKING=true \
JOLT_CHDB_LIB=/path/to/libchdb.so jolt -m demo.main
```

**Run model (metadata only)** records the run/branch/turn/generation/HTTP/tool
hierarchy and usage without content. **Run model (show exchange)** explicitly
records the bounded prompt and assistant response after stripping common
delimited thinking output. **Run multi-turn intervention** records the first
answer, a controller revision, and a second model turn in one parent-linked
trace. Credentials and tool arguments are never recorded; thinking is disabled
by default.

Samizdat presentation policy lives beside its instrumentation vocabulary in
`demo.samizdat-kindly`. It annotates values with Kindly's standard
[`:kindly/kind` and `:kindly/options` value metadata](https://scicloj.github.io/kindly-noted/kindly/);
the reusable viewer
only understands generic Kindly values plus a small namespaced set of layout
hints. Raw stored telemetry and JSON APIs remain presentation-free.

Configuration is optional:

- `DEMO_PORT` selects the listening port (default `8080`).
- `DEMO_CHDB_SPEC` selects the chDB database (default `chdb::memory:`).
- `JOLT_CHDB_LIB` selects an existing `libchdb` installation.
- `JOLT_CHDB_CACHE_DIR` selects where the installer stores chDB.
- `DEMO_LEMONADE_BASE_URL` selects the physical OpenAI-compatible endpoint,
  which is never copied into telemetry.
- `DEMO_LEMONADE_TELEMETRY_ADDRESS` selects a non-identifying display label
  (default `local-model-host`).
- `DEMO_LEMONADE_DISABLE_THINKING` defaults to true; set it to `false` only when
  intentionally testing provider thinking behavior.

![Navigating a complete agent trace](docs/screenshots/agent-trace-tour.gif)

![Controller intervention and revised turn](docs/screenshots/07-agent-controller-intervention.png)

For persistent data, prefer a map dbspec from an embedding application. The
standalone demo also accepts a `chdb:` URI through `DEMO_CHDB_SPEC`; the demo
does not delete persistent data.

## Run workbench (fixture)

`GET /workbench` is a separate, self-contained page: enter a prompt and watch
one run evolve through ordered semantic-stage events — `run-opened`,
`turn-started`, `model-requested`, `tool-dispatched`, `tool-completed`,
`controller-decided`, `run-closed` — down to a terminal response and capture
state. State lives in a `glimmer.ratom` cell; `jolt.datastar.core` streams
live updates over SSE scoped to that one route.

**This is a fixture, not a Samizdat integration.** `demo.workbench-fixture`
is a deterministic, offline, hand-scripted stand-in shaped like a Samizdat
control-loop run — it never opens a socket, reads an environment variable, or
names a physical host. It tells the same paranoid-android stale-dashboard and
square-root-of-minus-one story as `test/browser/model-fixture-server.js`.
`demo.workbench-fixture/RunAdapter` is the seam a real Samizdat-backed
adapter would implement to replace it, without changing the route, state
machine, or rendering layer.

`/workbench` GET, POST, and SSE traffic is excluded from application
instrumentation the same way `/`, `/traces/*`, and the agent-demo routes are,
so viewing or driving the workbench cannot feed telemetry back into itself.

## Receive OTLP

The server accepts OTLP/HTTP JSON on:

- `POST /v1/traces`
- `POST /v1/logs`
- `POST /v1/metrics`

Requests are bounded and deliberately excluded from application
instrumentation, preventing receiver/viewer feedback loops. See [SPEC.md](SPEC.md)
for the route, lifecycle, query, and safety contracts.

## Test

Install the pinned native test engine, then run the deterministic, integration,
and shrinking property suites:

```sh
jolt -M:setup-native
jolt -A:test -m hegel.install
jolt -M:test
```

The browser story requires Node 18+ and the pinned Chromium bundle:

```sh
npm install
npx playwright install chromium
JOLT_CHDB_LIB=/path/to/libchdb.so npm run test:browser
```

Use a fresh filesystem-backed chDB for the same live-update story with:

```sh
JOLT_CHDB_LIB=/path/to/libchdb.so npm run test:browser:persistent
```

The same deterministic story owns the checked-in documentation frames:

```sh
JOLT_CHDB_LIB=/path/to/libchdb.so npm run docs:screenshots
```

See [docs/storyboard.md](docs/storyboard.md) for the tested interaction and
[docs/instrumented-build-spike.md](docs/instrumented-build-spike.md) for the
proposed provider-neutral aspect/weaver proof. The resolved Samizdat run,
control-loop, model, tool, memory, HTTP, and DB seams are recorded in
[docs/samizdat-instrumentation.md](docs/samizdat-instrumentation.md).

Workspace development commands that can invoke Chez must use the repository's
Chez 10.4.1 wrapper, as documented by the workspace `AGENTS.md`.

## License

Copyright 2026 contributors. Distributed under the Eclipse Public License 2.0;
see [LICENSE](LICENSE).
