# Publication checklist

This workspace slice spans independently versioned repositories. Keep the
demo's exact-SHA composition green while publishing them in dependency order;
do not replace exact coordinates with branch names or unpublished local roots.

## Preset and source-annotation candidate snapshot

The `codex/demo-presets-annotations-current` candidate exercises the reviewed
Jolt compiler at `523c8d89929e9867563faa48d863c4df05a0d849`. Its root and
standalone Samizdat woven builds expand package-owned `basic` presets for DB,
HTTP client, and HTTP server while keeping the demo-local and Samizdat journal
providers explicit. The exact library refs relevant to this change are:

- `casselc/jolt-http@fb596ea1a7d1899fd437d21c9da4fbfab0e436a2`, whose HTTP
  server manifest is generated from cooperative entry annotations;
- `casselc/db@7075e5934caeae7f64a6784cf87a7ae16d427032`, whose DB execution
  manifest is generated from a stock-compatible entry annotation; its
  test-only validation tip is
  `2f13feb862aa1cd4b1a14e544ee36aff22306002`;
- `jolt-otel-instrumentation-http-client@ca76104a575dadfb0c7d4a1ab6aa1e253e180fc9`,
  `jolt-otel-instrumentation-db@9ed00197288d1704f4a7ba69f2755467e3874d4b`,
  and `jolt-otel-instrumentation-http-server@02db523456f5c6cba99a536669c7d0a58f033729`.

`jolt aspects manifest --check` passes for the two annotated libraries and the
demo's generated workbench manifest. The demo manifest uses semantic ABI
version `0.1.0`; unlike a dependency coordinate, that embedded value is not a
self-referential repository-head SHA. The resolved root plan contains all three
preset identities, four providers, and six selected aspects. Every compiled
demo gate also validates non-vacuous three-phase compiler effect evidence and
requires woven aspect-site identities to survive optimization unchanged. These
refs remain review branches; the published-main snapshot below is unchanged
until they are promoted.

At the exact compiler and dependency refs above, the focused Clojure suite
passes 79 tests / 596 assertions, including seven seeded Hegel properties with
libhegel 0.33.3. The compiler-only demo smoke also passes with 3,158 effect
subjects and two woven aspect sites. The standalone Samizdat build completes
in 573.79 seconds and its runtime smoke passes with 10,509 effect subjects, 16
woven aspect sites, working viewer/editor routes, and clean SIGINT shutdown.
The current root source and woven Chromium stories each pass all nine cases;
the woven root build records 6,088 effect subjects and six preserved aspect
sites, including `:db.jdbc-shim/execute`.
The DB validation tip passes its full suite and checks the manifest's declared
arity against the live annotated function's argument list.

## Published composition snapshot

As of 2026-09-01, the exact graph exercised by the source, woven, persistent
chDB, and Samizdat gates is on the owning repositories' `main` branches for:

- `jolt-otel-instrumentation-db` at `eaebe2250558e6b94a2152cb245eae071d0e593c`;
- `jolt-otel-instrumentation-http-client` at `158bc20e5f34dd8047a3ffabdd602bc9a68915dc`;
- `jolt-otel-instrumentation-http-server` at `23a90e77406c5f4d99fa61b160c9723417ef87c0`;
- `jolt-chdb` at `39c04ed04933fdeaa5e2f481aa8c9b294afb5e7e`;
- `jolt-otel-clickhouse` at `a247f418a462357d0a114d5f51024d00bd38189f`;
- `jolt-otel-viewer` at `9ae943fcaf7725b1b36e00bc216aef17405f295a`;
- `oscope` at `eb9b231b4e1743817feca76a990dc0502eb5c9ad`.

GitHub's authoritative refs were checked after those fast-forwards, and the
demo's non-OTel woven aspect smoke passed again from the exact `main` tip.
The compiler and compatibility forks listed below intentionally remain exact
branch SHAs until their own upstream disposition is decided.

1. **Jolt compiler (`jolt-lang/jolt`, coordination required).** The complete
   demo composition currently uses the fork integration commit `6aef4d4c`.
   Independently reviewable compiler slices through role selection, stable
   site provenance, and test-only control advice are published on
   `casselc/jolt` prep branches. The remaining action is to carry the manifest
   and build hook plus the observational `:args-v1` and argument-replacing
   `:replace-args-v1` contracts through an explicitly authorized upstream
   review sequence.
   Its gate must retain evaluation exactly once and left-to-right, legacy
   provider compatibility, fail-open result/exception identity, exact-match
   reporting, and all release/dev/optimized/plain build modes. This is the one
   component not owned by the user's organizations, so keep it on a fork until
   an upstream change is explicitly authorized.
2. **Samizdat fork.** The demo pins the published fork commit `7a72c9b`. Keep
   the no-HTTP `samizdat.embed` lifecycle, maintained
   DB/http-client dependencies, retryable close semantics, the complete source
   dependency graph regression for dynamically loaded cells, and the missing
   `samizdat.agent.reflect` AOT preload. Rebase only on the user's active M2
   fork branch and preserve its no-commit-without-request policy.
3. **OTel libraries.** The DB, HTTP-client, HTTP-server, and ClickHouse
   instrumentation/export repos are now on their owned `main` branches and are
   exact-pinned by the demo. The maintained OTel runtime remains fork-pinned at
   `b18830a`; the corrected library-owned HTTP-client aspect lineage now merges
   upstream v0.0.6 and remains fork-pinned at `a42592a`; and the
   provider-neutral `jolt-http` seam remains fork-pinned at `d9fa893`. Do not
   report those three as upstream-main fixes.
   The server consumer owns accepted Ring callback
   completion, request-scoped source-fallback detection, and an observational
   post-span hook. Update each dependent repo to an exact resulting SHA only
   after its own full gate is green.
4. **Observability demo.** `chucklehead-dev/jolt-observability-demo:main`
   contains the Jolt 0.8 graph and exact-executable launcher gate. Dependency
   commit `8831501` records the current upstream-v0.0.6 HTTP lineage and
   dependency-owned time-provider graph without
   trying to embed the repository's self-changing `main` tip in this document.
   The library graph uses exact Samizdat, viewer,
   OTel, chDB, ClickHouse exporter, and oscope coordinates. The intentional
   `samizdat-demo/` local root names the enclosing application being compiled,
   not an unpublished library. Retain both the ordinary fixture build and the
   compiler-woven standalone build. The standalone Playwright gate remains
   release-blocking: exact prompt, uninterrupted SSE mutation, real source edit
   and test, run/turn/model/tool parent IDs, clean shutdown, and no
   `ThreadStatus` fatal.
5. **Jolt time provider metadata.** Jolt 0.8 dependency-owned host-class
   discovery requires the `jolt.time` provider declaration tracked by ledger
   issue #66. `data.json` now owns its runtime dependency on the verified
   provider repair `2494b21`; consumers no longer need a demo-only direct pin.
   Keep that issue open until `jolt-lang/time` contains and independently
   verifies the provider table.
6. **oscope extraction.** `chucklehead-dev/oscope` is published on its coherent
   Jolt 0.8 graph at `eb9b231`, and the demo consumes that exact SHA. Its
   canonical EDN query/screen/command/effect
   contract drives Web, Glitter/GTK, and Glimmer adapters without copied query
   or view-model namespaces. Preserve bounded explorer caps and exact query-plan
   provenance. Its published raw-export contract now proves source-wide
   admission, owned Arrow/Parquet bytes, real HTTP delivery, independent
   readers, half-open windows, truncation, and no viewer feedback. Plotje-compatible
   SVG remains platform-neutral; toolkit handles and ratoms remain shell-local.
   Native chart rendering, standalone OTLP serving, and the opt-in web
   Live/Freeze contract now pass. Explicit toolkit unmount ownership, native
   asynchronous stale-completion rejection, and preserve-to-table policy remain
   follow-up gates.

No upstream pull request is part of this checklist until separately approved.
Before any push, record exact parents, inspect every dirty worktree, run
`git diff --check`, and commit repository-sized changes independently so a
dependency can be reviewed or reverted without dragging the demo with it.
