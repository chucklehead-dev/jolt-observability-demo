(ns demo.plotje-portable
  "Compatibility name for oscope's canonical Jolt-portable Plotje renderer."
  (:require [oscope.plotje.svg :as svg]))

(def spec->svg svg/spec->svg)
