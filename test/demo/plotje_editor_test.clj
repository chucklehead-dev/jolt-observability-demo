(ns demo.plotje-editor-test
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is]]
            [demo.plotje-editor :as editor]))

(def latency-spec
  {:title "Checkout latency by percentile"
   :x-label "Minute"
   :y-label "Latency (ms)"
   :width 760
   :height 420
   :data [{:minute 0 :latency-ms 31 :series "p50"}
          {:minute 1 :latency-ms 34 :series "p50"}
          {:minute 0 :latency-ms 94 :series "p95"}
          {:minute 1 :latency-ms 102 :series "p95"}]
   :layers [{:mark :line :x :minute :y :latency-ms :color :series}
            {:mark :point :x :minute :y :latency-ms :color :series}]})

(deftest normalized-spec-renders-through-pinned-upstream-plotje
  (let [svg (editor/spec->svg latency-spec)]
    (is (str/starts-with? svg "<svg"))
    (is (str/includes? svg "Checkout latency by percentile"))
    (is (str/includes? svg "Latency (ms)"))
    (is (not (str/includes? svg "<script")))))
