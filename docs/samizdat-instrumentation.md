# Samizdat instrumentation map

Grounded against `yogthos/samizdat` commit
`0858ce0a836e72e3c572cc47f868e8db6b32b587`. These are proposed resolved-IR
join points for the build-time aspect mechanism described in
[instrumented-build-spike.md](instrumented-build-spike.md); no runtime var
replacement is assumed.

## Trace shape

```text
samizdat.run                         invoke_agent
`-- beam scheduler / workflow loop
    `-- samizdat.branch
        `-- samizdat.turn
            |-- inference policy and retry events
            |-- chat                       generation
            |   `-- HTTP request attempt   infrastructure
            |-- parse, gate, and steer events
            `-- execute_tool
                `-- semantic memory / DB / filesystem / process spans
```

Runs, branches, turns, generations, and tools have duration and therefore are
spans. Tool selection, parse repair, retry-budget changes, gate firings, branch
pruning, policy refusal, steer/intervention, and artifacts are instantaneous
span events or correlated logs. This exposes the control loop without tracing
every internal function.

## Samizdat-owned rules

The application manifest owns vocabulary that would be meaningless in a
generic HTTP or database library:

| Role | Resolved call | Current call site |
| --- | --- | --- |
| Run | `samizdat.agent.beam/run!` arity 1 | `api/control.clj:100`, inside the future created at line 98; and `api/openai.clj:103` |
| Control loop | Beam scheduler scope around branch advancement | Compose around the run and `advance-branch` rules; no standalone resolved call is claimed |
| Branch | Per-branch scope | Derived from the branch value passed to `advance-branch`; no standalone resolved call is claimed |
| Beam turn | `samizdat.agent.beam/advance-branch` arity 3 | `agent/beam.clj:566`; encloses the production branch turn |
| Model | `samizdat.llm.client/chat` arity 4 | `agent/infer.clj:187`; also cover arity 3 raw/probe callers |
| Tool selection event | `samizdat.agent.infer/absorb` arity 3 | `agent/loop.clj:377` |
| Tool execution | `samizdat.agent.tools/run-tool` arity 1 | `agent/loop.clj:520` |
| Semantic memory | `remember!` 2, `recall` 2/3, `record-outcome!` 3, `forget!` 2 | `store/knowledge.clj` |

`advance-branch` is a complete turn only for the live beam driver. The
single-driver workflow back-edge lives inside Mycelium, so universal turn spans
need either function-entry weaving plus a Mycelium interceptor, or explicit
hooks around `:loop/assemble` and `:loop/route`. A call-site-only weaver must not
pretend it covers both drivers.

## Library-owned rules

- The selected `jolt-http-client` release owns version-locked private request
  seams `jolt.http.platform/perform!` arity 1 and `net-http-send` arity 3. The
  OTel provider injects Trace Context and emits physical HTTP attempt spans.
- Samizdat currently selects the older `jolt-lang/db` API. Its manifest owns
  `db.sqlite/query` arity 3 and `db.pg/run` arity 3. After upgrading to the
  embedded-driver SPI, replace these with `db.driver/execute-handle` arity 4.
- Jolt runtime metadata declares `clojure.core/future-call` and
  `jolt.fibers/spawn` context-propagating boundaries. Jolt futures already copy
  dynamic bindings, including `otel.context/*current*`; weaving another wrapper
  would be redundant. Arbitrary external-thread callbacks still require
  `otel.context/bind-fn*`.

## Lemonade configuration

No Samizdat source change is required for a local Lemonade server exposing the
OpenAI-compatible API:

```sh
HARNESS_PROVIDER=local \
HARNESS_BASE_URL=http://model-host.example:8000/v1 \
HARNESS_MODEL=local-model \
jolt serve
```

The local provider correctly appends `/models` and `/chat/completions`. A future
Lemonade-specific provider ID is useful if the same endpoint routes non-llama
cloud models, because Samizdat's `:local` adapter deliberately supplies
llama.cpp cache/template options.

## Data policy

Metadata-only is the default: model/provider, finish reason, latency, token
usage, bounded error type, run/branch/turn IDs, tool name, and a non-identifying
logical endpoint label. Do not record the physical model hostname,
prompts, system instructions, reasoning, response content, tool arguments or
results, file contents, credentials, environment maps, SQL parameters, or raw
provider error bodies. Content capture is a separate explicit mode, must pass
through Samizdat's redaction boundary, is capped, and uses only
`samizdat.response.sanitized` with
`samizdat.response.content_state=captured`.

The standalone demo does not claim Samizdat's full redaction policy. It disables
model thinking by default, strips common delimited `<think>...</think>` output,
and caps the visible response. Capture mode still intentionally records model
content and must not be enabled for sensitive workloads.
