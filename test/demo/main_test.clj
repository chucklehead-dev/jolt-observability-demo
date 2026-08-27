(ns demo.main-test
  (:require [clojure.data.json :as json]
            [clojure.string :as str]
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
(def sample-spans
  [{:timestamp "2026-08-27T10:00:00Z" :spanId "root" :parentSpanId ""
    :name "request" :durationNs 4000000}
   {:timestamp "2026-08-27T10:00:00.001Z" :spanId "client" :parentSpanId "root"
    :name "HTTP GET" :durationNs 2000000}
   {:timestamp "2026-08-27T10:00:00.0015Z" :spanId "decode" :parentSpanId "client"
    :name "decode" :durationNs 500000}
   {:timestamp "2026-08-27T10:00:00.003Z" :spanId "orphan" :parentSpanId "missing"
    :name "orphan" :durationNs 250000}])

(defn test-app []
  (demo/app-context
    {:summary-fn (constantly sample-summary)
     :traces-fn (constantly sample-traces)
     :trace-fn (fn [id] {:traceId id :spans sample-spans
                         :spanTree (demo/span-tree sample-spans)
                         :logs sample-logs})
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
      (let [response (h {:request-method :get
                         :uri "/api/traces/0123456789abcdef0123456789abcdef"})
            detail (decode response)]
        (is (= 200 (:status response)))
        (is (= (count sample-spans) (count (:spans detail))))
        (is (= ["root" "orphan"] (mapv :spanId (:spanTree detail)))))
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

(deftest span-tree-preserves-hierarchy-and-orphans
  (let [forest (demo/span-tree sample-spans)
        root (first forest)
        client (first (:children root))]
    (is (= ["root" "orphan"] (mapv :spanId forest)))
    (is (= "client" (:spanId client)))
    (is (= ["decode"] (mapv :spanId (:children client))))
    (is (= [] (:children (second forest)))))
  (testing "cycles and self-parenting remain bounded"
    (let [forest (demo/span-tree [{:spanId "a" :parentSpanId "b"}
                                  {:spanId "b" :parentSpanId "a"}
                                  {:spanId "self" :parentSpanId "self"}])]
      (is (= #{"a" "self"} (set (map :spanId forest))))
      (is (= "b" (->> forest (some #(when (= "a" (:spanId %)) %))
                         :children first :spanId))))))

(deftest dashboard-trace-viewer-contract
  (let [body (:body ((demo/handler (test-app))
                     {:request-method :get :uri "/"}))]
    (is (str/includes? body "function buildTree(spans)"))
    (is (str/includes? body "path.has(id)"))
    (is (str/includes? body "detail.spanTree"))
    (is (str/includes? body "node('div','timeline-bar')"))
    (is (str/includes? body "span.status==='error'"))
    (is (str/includes? body "textContent"))
    (is (not (str/includes? body "innerHTML")))))

(deftest live-loopback-integration
  (let [port (+ 24000 (rand-int 3000))
        lifecycle (demo/start! {:port port :db-spec "chdb::memory:"})
        trace-id "0123456789abcdef0123456789abcdef"
        remote-span-id "0123456789abcdef"
        traceparent (str "00-" trace-id "-" remote-span-id "-01")]
    (try
      (Thread/sleep 300)
      (let [base (str "http://127.0.0.1:" port)
            _ (http-client/get (str base "/api/summary"))
            _ (http-client/get (str base "/api/traces"))
            _ (http-client/get (str base "/api/logs"))
            _ (is (true? (sdk/force-flush! (:otel lifecycle))))
            before (decode (http-client/get (str base "/api/summary")))
            work (http-client/get (str base "/work")
                                  {:headers {"traceparent" traceparent}
                                   :conn-timeout 2000 :socket-timeout 5000
                                   :throw-exceptions false})]
        (is (= 0 (:traceCount before))
            "dashboard polling does not create self-observation traces")
        (is (= 200 (:status work)))
        (is (true? (sdk/force-flush! (:otel lifecycle))))
        (let [traces (decode (http-client/get (str base "/api/traces")))
              summary (some #(when (= trace-id (:traceId %)) %) traces)
              detail (decode (http-client/get (str base "/api/traces/" trace-id)
                                              {:conn-timeout 2000 :socket-timeout 5000}))
              spans (:spans detail)
              server (some #(when (= "HTTP GET /work" (:name %)) %) spans)
              client (some #(when (and (= "HTTP GET /upstream" (:name %))
                                       (= "client" (:kind %))) %) spans)
              upstream (some #(when (and (= "HTTP GET /upstream" (:name %))
                                         (= "server" (:kind %))) %) spans)]
          (is (= "HTTP GET /work" (:rootSpan summary)))
          (is (= 3 (count spans)) "one propagated trace contains both HTTP edges")
          (is (= remote-span-id (:parentSpanId server)))
          (is (= (:spanId server) (:parentSpanId client)))
          (is (= (:spanId client) (:parentSpanId upstream)))
          (is (some #(and (= (:traceId detail) (:traceId %))
                          (not= "" (:spanId %))) (:logs detail))
              "the trace detail API returns a correlated log")))
      (finally (demo/stop! lifecycle)))))
