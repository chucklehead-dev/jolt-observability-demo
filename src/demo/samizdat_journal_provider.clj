(ns demo.samizdat-journal-provider
  "Bounded, content-free Samizdat aspect consumer independent of OpenTelemetry."
  (:require [demo.aspect-journal :as journal]
            [demo.samizdat-aspect-provider :as otel-provider]))

(defn around
  "Record semantic entry/terminal ordering without inspecting arguments."
  [join-point _evaluated-args proceed]
  (journal/around join-point proceed))

(defn around-http-client
  "Transparent role required by the selected core manifest.

  HTTP is intentionally observed only by the privacy-specialized OTel consumer;
  the journal vocabulary stays at run, turn, model, and tool operations."
  [_join-point _evaluated-args proceed]
  (proceed))

(def aspect-provider
  {:schema 1
   :libraries {'yogthos/samizdat otel-provider/samizdat-build-id}
   :roles {:samizdat/run {:fn 'demo.samizdat-journal-provider/around
                          :contract :args-v1}
           :samizdat/turn {:fn 'demo.samizdat-journal-provider/around
                           :contract :args-v1}
           :samizdat/model {:fn 'demo.samizdat-journal-provider/around
                            :contract :args-v1}
           :samizdat/tool {:fn 'demo.samizdat-journal-provider/around
                           :contract :args-v1}
           :http/client {:fn 'demo.samizdat-journal-provider/around-http-client
                         :contract :replace-args-v1}}})
