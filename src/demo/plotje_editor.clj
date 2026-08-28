(ns demo.plotje-editor
  "JVM oracle adapter from the normalized bounded chart spec to upstream Plotje.

  The runnable Jolt Ring editor lives in `demo.plotje-portable-editor`. This
  namespace deliberately owns no parser, HTTP surface, or mutable state: the
  shared bounded spec is validated before it reaches either renderer, and this
  adapter answers only whether upstream Plotje can render that same value."
  (:require [scicloj.plotje.api :as pj]
            [scicloj.plotje.render.svg :as plotje-svg]))

(defn- add-layer [pose {:keys [mark x y color]}]
  (let [options (cond-> {} color (assoc :color color))]
    (case mark
      :line (pj/lay-line pose x y options)
      :point (pj/lay-point pose x y options)
      :bar (pj/lay-bar pose x y options))))

(defn spec->svg
  "Render one already-normalized bounded chart spec through Plotje's public
  pose/plot/SVG pipeline."
  [{:keys [data layers width height title x-label y-label]}]
  (let [pose (reduce add-layer data layers)
        options (cond-> {:format :svg :width width :height height}
                  title (assoc :title title)
                  x-label (assoc :x-label x-label)
                  y-label (assoc :y-label y-label))]
    (-> pose (pj/options options) pj/plot plotje-svg/hiccup->svg-str)))
