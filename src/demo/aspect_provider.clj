(ns demo.aspect-provider
  "Non-OTel aspect consumer for the demo workbench.

  The journal is observational only: bounded, content-free, and never read to
  decide application behavior. A plain build never invokes `around`, so the
  same app remains fully functional with an empty journal."
  (:require [demo.aspect-journal :as journal]))

(def observations (journal/journal 128))

(defn around [join-point proceed]
  (binding [journal/*journal* observations]
    (journal/around join-point proceed)))

(defn snapshot [] (journal/snapshot observations))

(def aspect-provider
  {:schema 1
   :libraries {'chucklehead-dev/jolt-observability-demo
               "f4a730b2d80e9d94fa1a8d99093fb585584ea89d"}
   :roles {:agent/run 'demo.aspect-provider/around}})
