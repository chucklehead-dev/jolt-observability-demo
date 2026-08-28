# Publication checklist

This workspace slice spans independently versioned repositories. Keep the
demo's exact-SHA composition green while publishing them in dependency order;
do not replace WIP local roots with branch names.

1. **Jolt compiler (`jolt-lang/jolt`, coordination required).** Publish the
   compiler aspect manifest/build hook and the observational `:args-v1` plus
   argument-replacing `:replace-args-v1` provider contracts.
   Its gate must retain evaluation exactly once and left-to-right, legacy
   provider compatibility, fail-open result/exception identity, exact-match
   reporting, and all release/dev/optimized/plain build modes. This is the one
   component not owned by the user's organizations, so keep it on a fork until
   an upstream change is explicitly authorized.
2. **Samizdat fork.** Publish the no-HTTP `samizdat.embed` lifecycle, maintained
   DB/http-client dependencies, retryable close semantics, the complete source
   dependency graph regression for dynamically loaded cells, and the missing
   `samizdat.agent.reflect` AOT preload. Rebase only on the user's active M2
   fork branch and preserve its no-commit-without-request policy.
3. **OTel libraries.** Publish the maintained http-client/crypto dependency
   graph in `otel`; publish the explorer-owned `db.jdbc` clean-load fix in
   `jolt-otel-clickhouse`; publish bounded span-event rendering in
   `jolt-otel-viewer`. The provider-neutral `http-client` and `jolt-http`
   manifests and the separate `jolt-otel-instrumentation-http-client` and
   `jolt-otel-instrumentation-http-server` consumers are now published and
   exact-pinned by the demo. The server consumer owns accepted Ring callback
   completion, request-scoped source-fallback detection, and an observational
   post-span hook. Update each dependent repo to an exact resulting SHA only
   after its own full gate is green.
4. **Observability demo.** The library graph now uses exact Samizdat, viewer,
   OTel, chDB, ClickHouse exporter, and oscope coordinates. The intentional
   `samizdat-demo/` local root names the enclosing application being compiled,
   not an unpublished library. Retain both the ordinary fixture build and the
   compiler-woven standalone build. The standalone Playwright gate remains
   release-blocking: exact prompt, uninterrupted SSE mutation, real source edit
   and test, run/turn/model/tool parent IDs, clean shutdown, and no
   `ThreadStatus` fatal.
5. **oscope extraction.** `chucklehead-dev/oscope` is published and the demo
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
