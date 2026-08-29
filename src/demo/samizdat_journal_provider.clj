(ns demo.samizdat-journal-provider
  "Bounded, content-free Samizdat aspect consumer independent of OpenTelemetry."
  (:require [demo.aspect-journal :as journal]
            [samizdat.instrumentation :as samizdat-instrumentation]))

(defn around
  "Record semantic entry/terminal ordering without inspecting arguments."
  [join-point _evaluated-args proceed]
  (journal/around join-point proceed))

(def aspect-provider
  {:schema 1
   :libraries {'yogthos/samizdat samizdat-instrumentation/compatibility-id}
   :roles {:samizdat/run {:fn 'demo.samizdat-journal-provider/around
                          :contract :args-v1}
           :samizdat/control-loop {:fn 'demo.samizdat-journal-provider/around
                                   :contract :args-v1}
           :samizdat/branch-open {:fn 'demo.samizdat-journal-provider/around
                                  :contract :args-v1}
           :samizdat/branch-close {:fn 'demo.samizdat-journal-provider/around
                                   :contract :args-v1}
           :samizdat/turn {:fn 'demo.samizdat-journal-provider/around
                           :contract :args-v1}
           :samizdat/model {:fn 'demo.samizdat-journal-provider/around
                            :contract :args-v1}
           :samizdat/tool-selection {:fn 'demo.samizdat-journal-provider/around
                                     :contract :args-v1}
           :samizdat/tool {:fn 'demo.samizdat-journal-provider/around
                           :contract :args-v1}
           :samizdat/steer {:fn 'demo.samizdat-journal-provider/around
                            :contract :args-v1}}})
