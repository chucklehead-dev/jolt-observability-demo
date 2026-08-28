# Demo storyboard

The browser acceptance test and documentation capture share this user story.
Run `npm run test:browser` for assertions and `npm run docs:screenshots` to
refresh the checked-in frames under `docs/screenshots/`.

## 1. Arrive at an empty workbench

Open `/`. The complete page is server-rendered and usable before JavaScript
runs. Verify zero totals, explicit empty trace/log states, and the live-region
marker. The enhancement opens one same-origin EventSource.

![Empty workbench](screenshots/01-empty-workbench.png)

## 2. Generate representative work

Select **Generate work**. With JavaScript, the POST returns 204 and the page
must not navigate or reload. A durable SSE patch adds the newest trace and logs.
The workload records six parent-linked spans:

```text
HTTP POST /work
  +-- SELECT demo readiness
  +-- demo.jobs publish
  |    `-- demo.jobs process
  `-- HTTP GET /upstream
       `-- HTTP GET /upstream
```

The DB query executes against embedded chDB. The queue handoff injects and
extracts W3C Trace Context through an ordinary envelope, modeling the boundary
used by an fs-backed or cross-process queue. The HTTP client/server pair uses a
real loopback request.

![Live trace arrival](screenshots/02-live-trace-arrives.png)

## 3. Inspect and dismiss the trace

Open the newest trace. Progressive enhancement loads its server-rendered detail
into a native dialog without changing the index URL. Verify the six-span tree,
timeline, parent metadata, DB and messaging operations, and correlated logs.
Escape closes the dialog and returns focus to the workbench.

![Trace waterfall dialog](screenshots/03-trace-waterfall-dialog.png)

## 4. Narrow the workbench

Filter operation names for `demo.jobs` and status `OK`. The browser follows the
semantic GET form, the server builds a bounded parameterized query, and the
matching trace remains visible. **Clear** returns to the unfiltered index.

![Filtered workbench](screenshots/04-filtered-workbench.png)

## 5. Prove the baseline

The second browser test disables JavaScript. Generate work follows the ordinary
POST/303 redirect, a trace link navigates to its full detail page, and **All
traces** returns to the index. This keeps the static HTML contract executable
instead of treating progressive enhancement as the only application.

## Future beats

- Restart the fixture against the same temporary `chdb:` path and prove the
  trace survives.
- Build the demo with selected instrumentation aspects and prove the generated
  DB and HTTP spans replace the handwritten equivalents; see
  [instrumented-build-spike.md](instrumented-build-spike.md).

## Agent/model privacy pair

An optional live storyboard calls an OpenAI-compatible Lemonade server twice
with the same overqualified-maintenance-android task. The first trace retains
only model, latency, finish reason, token usage, and control-loop structure. The
second additionally stores the bounded assistant response after common
delimited thinking output is stripped. Prompts, system instructions, physical
model hostnames, credentials, and tool arguments remain absent in both modes;
provider thinking is disabled by default.

Run it with an endpoint reachable from the demo process and a neutral telemetry
display address so private hostnames never enter checked-in screenshots:

```sh
DEMO_LEMONADE_BASE_URL=http://model-host.example:8000/v1 \
DEMO_LEMONADE_TELEMETRY_ADDRESS=local-model-host \
DEMO_LEMONADE_DISABLE_THINKING=true \
JOLT_CHDB_LIB=/path/to/libchdb.so npm run docs:agent-screenshots
```

![Agent trace without response content](screenshots/05-agent-metadata-only.png)

![Agent trace with bounded sanitized response](screenshots/06-agent-with-response.png)

The checked-in animated tour is generated against a deterministic local model
fixture, so it contains no private endpoint or machine identity:

```sh
npm run docs:agent-gif
```

The recorder uses the pinned Playwright dependency and requires `ffmpeg` on
`PATH` for the final WebM-to-GIF conversion.

![Navigating the complete agent trace](screenshots/agent-trace-tour.gif)
