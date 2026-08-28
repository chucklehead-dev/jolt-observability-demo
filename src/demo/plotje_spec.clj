(ns demo.plotje-spec
  "Demo fixtures around oscope's canonical bounded Plotje contract."
  (:require [oscope.plotje.spec :as spec]))

(def max-spec-chars spec/max-spec-chars)

(def telemetry-specs
  {:latency
   {:title "Checkout latency by percentile"
    :x-label "Minute"
    :y-label "Latency (ms)"
    :width 760
    :height 420
    :data [{:minute 0 :latency-ms 31 :series "p50"}
           {:minute 1 :latency-ms 34 :series "p50"}
           {:minute 2 :latency-ms 29 :series "p50"}
           {:minute 3 :latency-ms 37 :series "p50"}
           {:minute 0 :latency-ms 94 :series "p95"}
           {:minute 1 :latency-ms 102 :series "p95"}
           {:minute 2 :latency-ms 91 :series "p95"}
           {:minute 3 :latency-ms 128 :series "p95"}]
    :layers [{:mark :line :x :minute :y :latency-ms :color :series}
             {:mark :point :x :minute :y :latency-ms :color :series}]}
   :errors
   {:title "Errors by service"
    :x-label "Service"
    :y-label "Errors"
    :width 700
    :height 380
    :data [{:service "gateway" :errors 7}
           {:service "checkout" :errors 3}
           {:service "inventory" :errors 5}
           {:service "payments" :errors 2}]
    :layers [{:mark :bar :x :service :y :errors}]}})

(def default-spec-text (pr-str (:latency telemetry-specs)))
(def validate-spec spec/validate-spec)
(def parse-spec spec/parse-spec)
