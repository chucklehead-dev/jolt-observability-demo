# Publication checklist

This workspace slice spans independently versioned repositories. Keep the
demo's exact-SHA composition green while publishing them in dependency order;
do not replace exact coordinates with branch names or unpublished local roots.

## Published composition snapshot

As of 2026-09-01, the exact graph exercised by the source, woven, persistent
chDB, and Samizdat gates is on the owning repositories' `main` branches for:

- `jolt-otel-instrumentation-db` at `c9fdbbfefdaba3cc88563c91d3eeae2c8bf26699`;
- `jolt-otel-instrumentation-http-client` at `8badefe2d035b0533dc068c58460fc39e0851764`;
- `jolt-otel-instrumentation-http-server` at `b83514cf6babf70257538e2c75f0e3ab8ecd9f8a`;
- `jolt-otel-clickhouse` at `9a13e3bbe63205a608875bbcd204175c6f1f242a`;
- this demo at `f5f1431e59c679f54de8d13e3378ff8c6976b8aa`.

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
   `ebcb0d1`; the corrected library-owned HTTP-client aspect lineage remains
   fork-pinned at `7cf4b2d`; and the provider-neutral `jolt-http` seam remains
   fork-pinned at `d9fa893`. Do not report those three as upstream-main fixes.
   The server consumer owns accepted Ring callback
   completion, request-scoped source-fallback detection, and an observational
   post-span hook. Update each dependent repo to an exact resulting SHA only
   after its own full gate is green.
4. **Observability demo.** `chucklehead-dev/jolt-observability-demo:main` now
   contains the Jolt 0.8 graph and exact-executable launcher gate at `f5f1431`.
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
   issue #66. The demo currently pins the verified fork repair at `2494b21`.
   Keep that issue open until `jolt-lang/time` contains and independently
   verifies the provider table.
6. **oscope extraction.** `chucklehead-dev/oscope` is published and the demo
   consumes its exact SHA. Its canonical EDN query/screen/command/effect
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
