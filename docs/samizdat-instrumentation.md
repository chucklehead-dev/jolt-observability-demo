# Samizdat instrumentation map

Grounded against the provider-neutral instrumentation resources published by
the user's Samizdat fork at
`7a72c9b52e35f4dd31daefcef2a9b3235a80e475`. Their semantic compatibility id
is `35b01fddd20fa9e6d77678eadc2a2bcc6fb9ac2d`; resource-only commits may advance
without pretending the selected source surface changed. The active entries are
compiled resolved-IR join points for the build-time aspect mechanism described in
[instrumented-build-spike.md](instrumented-build-spike.md); no runtime var
replacement is assumed. Rows explicitly described as future coverage remain
design inventory rather than claims of the current manifest.

## Trace shape

```text
samizdat.run                         invoke_agent
`-- samizdat.control_loop            beam scheduler / workflow loop
    `-- samizdat.turn N
        |-- inference policy and retry events
        |-- samizdat.model            generation
        |   `-- HTTP POST             infrastructure
        |-- tool-selection and steer events
        `-- execute_tool NAME
            `-- DB / filesystem / process spans
```

Runs, scheduler loops, turns, generations, and tools have duration and therefore
are spans. Branch open/close, tool selection, and steer evaluation are currently
instantaneous span events. Parse repair, retry-budget changes, policy refusal,
intervention, and artifact vocabulary remain follow-up event coverage. This
exposes the control loop without tracing every internal function.

## Samizdat-owned rules

The application manifest owns vocabulary that would be meaningless in a
generic HTTP or database library:

| Role | Selected definition or call | Current coverage |
| --- | --- | --- |
| Run | entry of `samizdat.agent.beam/run!` arity 1 | Every caller, including embedded, control, and OpenAI surfaces |
| Control loop | entry of `samizdat.agent.beam/run-rounds` arity 3 | One scheduler-duration span under the run |
| Branch lifecycle | entries of `samizdat.store.runs/open-branch!` arity 3 and `close-branch!` arity 5 | Open and successful state-changing close events; stale zero-row closes emit no false transition |
| Beam turn | entry of `samizdat.agent.beam/advance-branch` arity 3 | Every live beam turn |
| Model | entry of `samizdat.llm.client/chat` arity 4 | All calls reach the canonical four-argument arity; the public arity 3 delegates to it without a duplicate outer span |
| Model HTTP | `jolt.http-client/post` arity 2 | `llm/client.clj:152`; the single provider-independent maintained-client call site; inference calls nest beneath the model span, while auxiliary Samizdat calls retain their current run context |
| Tool selection event | `samizdat.agent.infer/absorb` arity 3 | `agent/loop.clj:377` |
| Tool execution | entry of `samizdat.agent.tools/run-tool` arity 1 | Every tool dispatch through the semantic wrapper |
| Steering event | entry of `samizdat.agent.arbiter/decide` arity 1 | Records bounded gate/tool metadata when selected and an evaluated-without-selection event otherwise |
| Semantic memory (future) | `remember!` 2, `recall` 2/3, `record-outcome!` 3, `forget!` 2 | Inventory only; not selected by the current manifest |

`advance-branch` is a complete turn only for the live beam driver. The
single-driver workflow back-edge lives inside Mycelium, so universal turn spans
still need a Mycelium interceptor or explicit hooks around `:loop/assemble` and
`:loop/route`; function-entry weaving removes caller gaps but does not invent a
semantic operation where the alternate driver has a different lifecycle.

## Library-owned rules

- The current Samizdat proof deliberately matches its public maintained
  `jolt.http-client/post` call, not the client's version-locked private seams.
  Its `:replace-args-v1` advice passes the selected call one exact-arity
  replacement vector containing the original URL and a copied request header
  map, creates one client span, and injects that span's W3C Trace Context. It
  records neither the physical endpoint nor request/response content. A
  reusable library-owned http-client package can later select the
  lower physical-attempt seam once that private ABI is explicitly versioned.
- Samizdat now uses the shared `db.jdbc` / `jdbc.core` surface. The separately
  selected generic DB instrumentation package observes the shared execution
  seam and nests query spans under the active Samizdat semantic operation; the
  Samizdat manifests do not duplicate driver-specific SQL join points.
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
through Samizdat's redaction boundary, is capped, and records
`samizdat.prompt.sanitized` and `samizdat.response.sanitized` only when their
corresponding `*.content_state` is `captured`.

The standalone demo does not claim Samizdat's full redaction policy. It disables
model thinking by default, strips common delimited `<think>...</think>` output,
and caps the visible response. Capture mode still intentionally records model
content and must not be enabled for sensitive workloads.

## Library-owned presentation advice

The generic viewer must not learn Samizdat span names or attribute vocabulary.
Samizdat therefore supplies a rendering adviser beside these join-point rules.
At the HTML boundary it maps a bounded span tree to the same tree with a
`:kindly {:value value}` note. The value follows the Kindly contract:

- `:kindly/kind` and `:kindly/options` are value metadata;
- scalar values are wrapped in a one-element vector with
  `:kindly/options {:wrapped-value true}`;
- fragments compose `:kind/table`, `:kind/code`, and `:kind/println` values;
- viewer layout hints are namespaced under `:otel.viewer/*`.

The current adviser lives in `demo.samizdat-kindly`; a real Samizdat
instrumentation package should export the equivalent pure function or inert
advice data. chDB rows and `/api/traces/<id>` stay raw. Other libraries can add
their own advisers without adding their vocabulary to `jolt-otel-viewer`.

Tool spans use the same contract. Their card always shows bounded semantic
metadata (tool name, category, turn, progress, and timeout outcome). When the
application explicitly enables content capture, the card also renders redacted,
bounded argument and result summaries as `:kind/code`; otherwise it says that
those values were not recorded.
