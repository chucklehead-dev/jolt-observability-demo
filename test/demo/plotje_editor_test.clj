(ns demo.plotje-editor-test
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [demo.plotje-editor :as editor]))

(deftest telemetry-example-renders-through-real-plotje
  (let [svg (editor/spec->svg (:latency editor/telemetry-specs))]
    (is (str/starts-with? svg "<svg"))
    (is (str/includes? svg "Checkout latency by percentile"))
    (is (str/includes? svg "Latency (ms)"))
    (is (not (str/includes? svg "<script")))))

(deftest parser-rejects-executable-oversized-and-overpowered-input
  (doseq [[label text]
          [["reader evaluation" "#=(System/exit 0)"]
           ["tagged literals" "#demo/thing {:x 1}"]
           ["unknown top-level key" "{:data [{:x 1 :y 2}] :layers [{:mark :line :x :x :y :y}] :url \"x\"}"]
           ["unsupported mark" "{:data [{:x 1 :y 2}] :layers [{:mark :html :x :x :y :y}]}" ]]]
    (testing label
      (is (thrown? clojure.lang.ExceptionInfo (editor/parse-spec text)))))
  (is (thrown? clojure.lang.ExceptionInfo
               (editor/parse-spec (apply str (repeat 33000 "x"))))))

(deftest preview-escapes-user-controlled-text-and-contains-errors
  (let [evil "<script>alert('chart')</script>"
        spec (assoc (:errors editor/telemetry-specs) :title evil)
        svg-fragment (editor/render-preview (pr-str spec))
        error-fragment (editor/render-preview "{:data [] :layers []}")]
    (is (str/includes? svg-fragment "&lt;script&gt;alert"))
    (is (not (str/includes? svg-fragment evil)))
    (is (str/includes? error-fragment "Spec error"))
    (is (str/includes? error-fragment "data must be a vector"))))

(deftest ring-surface-has-post-fallback-and-live-preview-endpoint
  (let [encoded (java.net.URLEncoder/encode editor/default-spec-text "UTF-8")
        post (editor/handler {:request-method :post :uri "/plotje-editor"
                              :body (str "spec=" encoded)})
        preview (editor/handler {:request-method :post :uri "/plotje-editor/preview"
                                 :body (str "spec=" encoded)})
        asset (editor/handler {:request-method :get :uri "/assets/plotje-editor.js"})]
    (is (= 200 (:status post)))
    (is (str/includes? (:body post) "<form method=\"post\""))
    (is (str/includes? (:body post) "<svg"))
    (is (= 200 (:status preview)))
    (is (str/starts-with? (:body preview) "<section id=\"plotje-preview\""))
    (is (str/includes? (:body asset) "setTimeout(render,300)"))))

(deftest bounds-protect-render-and-request-surfaces
  (let [too-many (assoc (:errors editor/telemetry-specs)
                        :data (vec (repeat 513 {:service "x" :errors 1})))
        huge-response (editor/handler {:request-method :post
                                       :uri "/plotje-editor/preview"
                                       :body (apply str (repeat 40001 "x"))})]
    (is (thrown? clojure.lang.ExceptionInfo (editor/validate-spec too-many)))
    (is (= 413 (:status huge-response)))
    (is (<= (count (:body huge-response)) 100000))))
