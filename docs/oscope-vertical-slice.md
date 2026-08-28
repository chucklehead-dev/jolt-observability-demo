# oscope shared vertical slice

The first oscope slice is published as `chucklehead-dev/oscope` and embedded by
the working observability demo rather than copied into it. It composes four
tested contracts:

1. `oscope.query/compile-query` maps a small EDN selection to the allowlisted,
   bounded `otel.exporter.chdb.explorer/top-values` request.
2. `oscope.view-model/screen` maps those distribution rows to serializable EDN
   containing semantic controls, accessible table rows, and a validated Plotje
   bar-chart spec. The screen retains the exact SQL-free `:query-plan` that
   produced its chart and table, so refresh and export do not have to guess the
   absolute query window from a relative label.
3. `oscope.plotje.svg/spec->svg` renders that chart on Jolt today; a JVM Plotje
   renderer can consume the same chart spec as an oracle.
4. `oscope.raw-export` maps only closed physical signal choices and an absolute
   half-open window to parameterized Arrow or Parquet queries. Its source-wide
   admission remains held until jolt-http drains or fails the owned byte body.

The screen model deliberately contains no HTML, callbacks, atoms, database
handles, or native objects. A web adapter can render its controls, table, and
SVG; a Glimmer/AppKit adapter can bind the same controls and either display the
portable SVG or map the chart spec to a native chart view. Shell-local state
such as focus, window geometry, and Glimmer ratoms stays outside the model.

The web adapter keeps that boundary in its progressive live mode. A small
same-origin asset permits one refresh request at a time, suspends hidden tabs,
rejects stale/cancelled completions, and atomically replaces one complete
`:oscope.view/version 1` fragment. Exponential retry backoff is capped. Freeze
cancels pending work and copies the displayed screen's exact absolute bounds
into the existing export form; it never reconstructs a moving window from the
relative `:window` label. The initial page, manual query, and export remain
fully functional with JavaScript disabled.

This vertical slice supports one top-value distribution at a time over the
explorer's current spans, logs, or metrics fields. Its query windows are 15
minutes, 1 hour, 6 hours, or the explorer's hard 24-hour maximum; limits remain
under the explorer's 100-bucket cap. It does not yet add arbitrary SQL, saved
queries, multi-dimensional grouping, saved dashboards, or a new ingest path.
Those should extend this proven selection/plan/model seam instead of bypassing
the embedded explorer's safety contract.

## Web/native interaction boundary

The adapters must replace an entire screen after a query. They must not mutate
`:selection` or a selected signal in place while leaving the old `:chart`,
`:table`, and `:query-plan` attached; that would describe data as though it came
from a query that never ran. In particular, the explorer signal is `:spans`,
not the presentation synonym `:traces`.

Both adapters should emit the same data-only intent containing a complete
selection, rather than backend-specific callbacks entering shared state:

```clojure
{:oscope.command/version 1
 :command/type :query
 :request-id 42
 :selection {:signal :logs
             :field :severity-text
             :window :1h
             :limit 12}}
```

An adapter-specific effect interpreter validates the selection, samples its
clock, calls `compile-query`, `run`, and `screen`, and then atomically installs
the returned screen. A synchronous web request may do that directly. A native
shell should run the database effect away from its UI thread. Once either shell
allows more than one query in flight, the completion event must echo
`:request-id` and the state owner must discard stale completions. The current
web enhancement instead admits only one fetch and uses a monotonic generation
to reject a completion after cancellation or Freeze. Loading,
cancellation, and sanitized error presentation belong in a small shared app
state around the canonical screen; they must not be represented by partially
rewriting a successful screen.

## Native and web proof

The published `chucklehead-dev/oscope` repository now owns these namespaces.
Its CSP-safe Ring adapter, Glitter/GTK adapter, and Glimmer adapter all consume
`:oscope.view/version 1`; its command effect validates a complete selection and
atomically replaces the whole screen. Headless tests, the web snapshot, Glimmer
compilation, real-chDB lifecycle, real jolt-http downloads, independent
Arrow/Parquet readers, and a no-JavaScript browser download gate pass. The earlier
`oscope-native-spike` is retained only as design history; consumers should use
the exact published oscope coordinate.
