# Plotje editor portability seam

Oracle: `scicloj/plotje` `b2a9199681c4143ec1068ed02feec4e2997d50ca`
(parent `8a8dc6135e949f268fcd058316738dc535012b41`).

The upstream public path used by `demo.plotje-editor` is
`pj/lay-line|lay-point|lay-bar` -> `pj/options` -> `pj/plot` ->
`scicloj.plotje.render.svg/hiccup->svg-str`.

The exact source closure is not currently loadable by Jolt:

| Stage | Upstream files | Portability boundary |
| --- | --- | --- |
| Pose/layers | `api.clj`, `impl/pose.clj`, `layer_type.clj` | Plain-map contract is portable, but implementation eagerly loads Tablecloth, dtype-next, Kindly, and clojure2d. |
| Draft/plan | `impl/resolve.clj`, `impl/plan.clj`, `impl/stat.clj`, `impl/scale.clj`, `impl/layout.clj` | Algorithms are extractable; current namespaces load Tablecloth/dtype-next, Wadogo, Fastmath, and java-time. |
| Drawing | `render/membrane.clj`, `render/mark.clj`, `render/panel.clj`, `impl/membrane.clj` | JVM Membrane records/protocols and dtype-next make this closure non-portable. |
| SVG | `render/svg.clj` | Serializer logic is pure, but the namespace imports JVM Membrane classes and dispatches on them. |

`demo.plotje-portable/spec->svg` is therefore a compatibility backend for the
strict editor subset, not a fork of the grammar. It consumes the same normalized
spec and keeps upstream layer order, color grouping, categorical bars, Set1
palette colors, bounds, and escaping. The JVM namespace remains the conformance
oracle. At HEAD, the latency fixture oracle summary is: width 760, height 420,
one panel, two polylines, twelve points, one clip, colors
`rgb(228,26,28)`/`rgb(55,126,184)`, and title/axis text preserved.

A deeper upstream extraction should split three seams: pure pose construction
out of `api.clj`/`impl.pose.clj`; dataset operations behind a small rows/columns
protocol in `impl.resolve`, `impl.plan`, and `impl.stat`; and direct SVG drawing
behind plan records so `render.svg` no longer requires Membrane. That would let
Jolt reuse the full plan pipeline rather than maintain a bounded backend.
