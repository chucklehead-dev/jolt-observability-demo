(ns demo.main-test
  (:require [clojure.data.json :as json]
            [clojure.test :refer [deftest is testing]]
            [demo.main :as demo]
            [jolt.http-client :as http-client]
            [otel.sdk :as sdk]))

(def sample-summary {:traceCount 1 :spanCount 2 :logCount 1 :errorCount 0})
(def sample-traces [{:traceId "0123456789abcdef0123456789abcdef"
                     :startedAt "1.0" :durationNs 3 :service "demo"
                     :rootSpan "root" :spanCount 2 :status "ok"}])
(def sample-logs [{:timestamp "1.0" :severity "INFO" :service "demo"
                   :body "hello" :traceId "0123456789abcdef0123456789abcdef"
                   :spanId "0123456789abcdef"}])

(defn test-app []
  (demo/app-context
    {:summary-fn (constantly sample-summary)
     :traces-fn (constantly sample-traces)
     :trace-fn (fn [id] {:traceId id :spans [] :logs sample-logs})
     :logs-fn (constantly sample-logs)
     :work-fn (fn [_] {:upstream {:ok true}})}))

(defn decode [response]
  (json/read-str (:body response) :key-fn keyword))

(deftest pure-handler-statuses-and-shapes
  (let [h (demo/handler (test-app))]
    (testing "dashboard and bounded APIs"
      (is (= 200 (:status (h {:request-method :get :uri "/"}))))
      (is (= sample-summary (decode (h {:request-method :get :uri "/api/summary"}))))
      (is (= sample-traces (decode (h {:request-method :get :uri "/api/traces"}))))
      (is (= sample-logs (decode (h {:request-method :get :uri "/api/logs"}))))
      (is (= {:ok true :upstream {:ok true}}
             (decode (h {:request-method :get :uri "/work"})))))
    (testing "validated trace identifiers"
      (is (= 200 (:status (h {:request-method :get
                              :uri "/api/traces/0123456789abcdef0123456789abcdef"}))))
      (is (= 400 (:status (h {:request-method :get :uri "/api/traces/ABC"}))))
      (is (= 400 (:status (h {:request-method :get :uri "/api/traces/../../etc"})))))
    (testing "method and route errors"
      (is (= 405 (:status (h {:request-method :post :uri "/work"}))))
      (is (= 404 (:status (h {:request-method :get :uri "/missing"})))))))

(deftest work-failure-is-bounded
  (let [app (assoc (test-app) :work-fn (fn [_] (throw (ex-info "boom" {}))))
        response ((demo/handler app) {:request-method :get :uri "/work"})]
    (is (= 502 (:status response)))
    (is (= {:error "upstream request failed"} (decode response)))))

(deftest live-loopback-integration
  (let [port (+ 24000 (rand-int 3000))
        lifecycle (demo/start! {:port port :db-spec "chdb::memory:"})]
    (try
      (Thread/sleep 300)
      (let [base (str "http://127.0.0.1:" port)
            work (http-client/get (str base "/work")
                                  {:conn-timeout 2000 :socket-timeout 5000
                                   :throw-exceptions false})]
        (is (= 200 (:status work)))
        (is (true? (sdk/force-flush! (:otel lifecycle))))
        (let [traces (decode (http-client/get (str base "/api/traces")
                                              {:conn-timeout 2000 :socket-timeout 5000}))
              nested (some #(when (>= (:spanCount %) 2) %) traces)
              detail (when nested
                       (decode (http-client/get (str base "/api/traces/" (:traceId nested))
                                                {:conn-timeout 2000 :socket-timeout 5000})))
              span-ids (set (map :spanId (:spans detail)))
              child (some #(when (contains? span-ids (:parentSpanId %)) %) (:spans detail))]
          (is (some? nested) "one trace contains at least two exported spans")
          (is (some? child) "a child ParentSpanId identifies another returned span")
          (is (some #(and (= (:traceId detail) (:traceId %))
                          (not= "" (:spanId %))) (:logs detail))
              "the trace detail API returns a correlated log")))
      (finally (demo/stop! lifecycle)))))
