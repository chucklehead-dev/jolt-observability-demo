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

Configuration is optional:

- `DEMO_PORT` selects the listening port (default `8080`).
- `DEMO_CHDB_SPEC` selects the chDB database (default `chdb::memory:`).
- `JOLT_CHDB_LIB` selects an existing `libchdb` installation.
- `JOLT_CHDB_CACHE_DIR` selects where the installer stores chDB.

For persistent data, prefer a map dbspec from an embedding application. The
standalone demo also accepts a `chdb:` URI through `DEMO_CHDB_SPEC`; the demo
does not delete persistent data.

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
proposed provider-neutral aspect/weaver proof.

Workspace development commands that can invoke Chez must use the repository's
Chez 10.4.1 wrapper, as documented by the workspace `AGENTS.md`.

## License

Copyright 2026 contributors. Distributed under the Eclipse Public License 2.0;
see [LICENSE](LICENSE).
