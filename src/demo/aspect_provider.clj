(ns demo.aspect-provider
  "Non-OTel aspect consumer for the demo workbench.

  The journal is observational only: bounded, content-free, and never read to
  decide application behavior. A plain build never invokes `around`, so the
  same app remains fully functional with an empty journal."
  (:require [demo.aspect-journal :as journal]))

(defn around [join-point proceed]
  (journal/around join-point proceed))

(def aspect-provider
  {:schema 1
   :libraries {'chucklehead-dev/jolt-observability-demo
               "9840fbb3245211bbecf3098777403b2b30678e1d"}
   :roles {:agent/run 'demo.aspect-provider/around}})
