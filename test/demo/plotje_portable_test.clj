(ns demo.plotje-portable-test
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is]]
            [demo.plotje-portable :as portable]))

(def latency
  {:title "Checkout latency by percentile" :x-label "Minute" :y-label "Latency (ms)"
   :width 760 :height 420
   :data [{:minute 0 :latency-ms 31 :series "p50"}
          {:minute 1 :latency-ms 34 :series "p50"}
          {:minute 0 :latency-ms 94 :series "p95"}
          {:minute 1 :latency-ms 102 :series "p95"}]
   :layers [{:mark :line :x :minute :y :latency-ms :color :series}
            {:mark :point :x :minute :y :latency-ms :color :series}]})

(deftest portable-subset-preserves-plotje-semantics
  (let [svg (portable/spec->svg latency)]
    (is (str/starts-with? svg "<svg"))
    (is (= 2 (count (re-seq #"<polyline" svg))))
    (is (= 4 (count (re-seq #"<circle" svg))))
    (is (str/includes? svg "rgb(228,26,28)"))
    (is (str/includes? svg "rgb(55,126,184)"))))

(deftest portable-subset-escapes-text
  (let [svg (portable/spec->svg (assoc latency :title "<script>&boom</script>"))]
    (is (str/includes? svg "&lt;script&gt;&amp;boom&lt;/script&gt;"))
    (is (not (str/includes? svg "<script>")))))

(deftest portable-bars-render-negative-values-with-valid-svg-heights
  (let [svg (portable/spec->svg
             {:data [{:label "gain" :value 3}
                     {:label "loss" :value -2}]
              :layers [{:mark :bar :x :label :y :value}]})]
    (is (= 2 (count (re-seq #"<rect x=" svg))))
    (is (str/includes? svg ">gain</text>"))
    (is (str/includes? svg ">loss</text>"))
    (is (not (str/includes? svg "height=\"-")))))
