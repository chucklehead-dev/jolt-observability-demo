(ns demo.main-test
  (:require [clojure.data.json :as json]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [demo.datastar :as demo-datastar]
            [demo.main :as demo]
            [jolt.http-client :as http-client]
            [jolt.http.body :as http-body]
            [otel.sdk :as sdk]
            [otel.viewer :as viewer]))

(def sample-summary {:traceCount 1 :spanCount 2 :logCount 1 :errorCount 0})
(def sample-traces [{:traceId "0123456789abcdef0123456789abcdef"
                     :startedAt "1.0" :durationNs 3 :service "demo"
                     :rootSpan "root" :spanCount 2 :status "ok"}])
(def sample-logs [{:timestamp "1.0" :severity "INFO" :service "demo"
                   :body "hello" :traceId "0123456789abcdef0123456789abcdef"
                   :spanId "0123456789abcdef"}])
(def sample-spans
  [{:timestamp "2026-08-27T10:00:00Z" :timestampUnixNano 1000000000
    :spanId "root" :parentSpanId ""
    :name "request" :durationNs 4000000
    :attributes {"http.route" "/work"}}
   {:timestamp "2026-08-27T10:00:00.001Z" :timestampUnixNano 1001000000
    :spanId "client" :parentSpanId "root"
    :name "HTTP GET" :durationNs 2000000}
   {:timestamp "2026-08-27T10:00:00.0015Z" :timestampUnixNano 1001500000
    :spanId "decode" :parentSpanId "client"
    :name "decode" :durationNs 500000}
   {:timestamp "2026-08-27T10:00:00.003Z" :timestampUnixNano 1003000000
    :spanId "orphan" :parentSpanId "missing"
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
  (let [flushes (atom 0)
        h (demo/handler (assoc (test-app) :flush-fn #(swap! flushes inc)))]
    (testing "dashboard and bounded APIs"
      (is (= 200 (:status (h {:request-method :get :uri "/"}))))
      (is (= sample-summary (decode (h {:request-method :get :uri "/api/summary"}))))
      (is (= sample-traces (decode (h {:request-method :get :uri "/api/traces"}))))
      (is (= sample-logs (decode (h {:request-method :get :uri "/api/logs"}))))
      (let [script (h {:request-method :get :uri "/assets/otel-viewer.js"})]
        (is (= 200 (:status script)))
        (is (= "text/javascript; charset=UTF-8"
               (get-in script [:headers "Content-Type"])))
        (is (str/includes? (:body script) "dialog.showModal()")))
      (let [script (h {:request-method :get :uri "/assets/datastar.js"})]
        (is (= 200 (:status script)))
        (is (str/starts-with? (:body script) "// Datastar v1.0.2")))
      (is (= {:ok true :upstream {:ok true}}
             (decode (h {:request-method :get :uri "/work"})))))
    (testing "HTML trace viewer and form action"
      (let [page (h {:request-method :get
                     :uri "/traces/0123456789abcdef0123456789abcdef"})
            generated (h {:request-method :post :uri "/work"})
            enhanced (h {:request-method :post :uri "/work"
                         :headers {"x-otel-enhancement" "fetch"}})]
        (is (= 200 (:status page)))
        (is (= "text/html; charset=UTF-8" (get-in page [:headers "Content-Type"])))
        (is (str/includes? (:body page) "<details open>"))
        (is (str/includes? (:body page)
                           "class=\"otel-back-link\" href=\"/\">← All traces</a>"))
        (is (str/includes? (:body page)
                           "grid-template-columns:minmax(0,12rem) minmax(0,1fr)"))
        (is (str/includes? (:body page)
                           ".otel-span-meta dt,.otel-span-meta dd{min-width:0;overflow-wrap:anywhere}"))
        (is (= 303 (:status generated)))
        (is (= "/" (get-in generated [:headers "Location"])))
        (is (= 204 (:status enhanced)))
        (is (= 2 @flushes))))
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
      (is (= 405 (:status (h {:request-method :post :uri "/api/logs"}))))
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
  (let [response ((demo/handler (test-app))
                  {:request-method :get :uri "/"})
        body (:body response)]
    (is (str/includes? body "<main class=\"otel-viewer\">"))
    (is (str/includes? body "<html class=\"otel-page\" lang=\"en\">"))
    (is (str/includes? body
                       ".otel-page body{min-height:100vh;margin:0;background:#080d18;color:#f2f6ff}"))
    (is (str/includes? body "--otel-muted:#b6c2d9"))
    (is (str/includes? body ".otel-viewer{"))
    (is (str/includes? body
                       "<form action=\"/work\" method=\"post\" data-otel-work>"))
    (is (str/includes? (viewer/enhancement-script) "X-Otel-Enhancement"))
    (is (str/includes? body
                       "href=\"/traces/0123456789abcdef0123456789abcdef\" data-otel-trace"))
    (is (str/includes? body "<dialog class=\"otel-trace-dialog\""))
    (is (str/includes? body "id=\"otel-live\" data-signals="))
    (is (str/includes? body "datastar-sse=true"))
    (is (str/includes? body
                       "<script type=\"module\" src=\"/assets/datastar.js\"></script>"))
    (is (str/includes? body
                       "<script src=\"/assets/otel-viewer.js\" defer></script>"))
    (is (str/includes? (get-in response [:headers "Content-Security-Policy"])
                       "default-src 'none'"))
    (is (str/includes? (get-in response [:headers "Content-Security-Policy"])
                       "script-src 'self'"))
    (is (not (str/includes? body "innerHTML"))))
  (testing "the reusable fragment escapes telemetry and needs no document shell"
    (let [body (viewer/render-fragment
                 {:summary sample-summary :traces []
                  :logs [{:body "</span><script>alert(1)</script>"
                          :severity "WARN" :timestamp "now"}]})]
      (is (str/starts-with? body "<main class=\"otel-viewer\">"))
      (is (str/includes? body "&lt;script&gt;alert(1)&lt;/script&gt;"))
      (is (not (str/includes? body "<dialog")))
      (is (not (str/includes? body "<script")))))
  (testing "empty collections render explicit accessible states"
    (let [body (viewer/render-fragment
                {:summary {} :traces [] :logs []})]
      (is (str/includes? body "No traces yet. Generate work to begin."))
      (is (str/includes? body "No logs yet."))
      (is (not (str/includes? body "<ol class=\"otel-trace-list\">")))))
  (testing "a host can mount the fragment below its own route"
    (let [body (viewer/render-fragment
                 {:base-path "observability/" :work-path "/work"
                  :summary sample-summary
                  :traces (conj sample-traces {:traceId "not-a-trace-id"})
                  :logs []})]
      (is (str/includes? body "href=\"/observability\""))
      (is (str/includes? body "action=\"/observability/work\""))
      (is (str/includes? body
                         "href=\"/observability/traces/0123456789abcdef0123456789abcdef\""))
      (is (not (str/includes? body "not-a-trace-id"))))))

(deftest datastar-live-region-contract
  (let [state (demo-datastar/stream-state
               {:interval-ms 0 :heartbeat-ms 10000 :max-streams 1})
        renders (atom 0)
        writes (atom [])
        response (demo-datastar/stream-response
                  state #(str "<p>snapshot-" (swap! renders inc) "</p>"))
        second-response (demo-datastar/stream-response state (constantly "full"))
        sink (reify http-body/Sink
               (sink-write! [_ bytes offset length]
                 (swap! writes conj
                        (String. bytes offset length "UTF-8"))
                 (when (= 2 (count @writes))
                   (throw (ex-info "peer closed" {}))))
               (sink-close! [_] nil))]
    (is (= "text/event-stream; charset=utf-8"
           (get-in response [:headers "Content-Type"])))
    (is (= 503 (:status second-response)) "normal requests retain worker capacity")
    (try
      (http-body/write-body-to-sink (:body response) response sink)
      (is false "the simulated peer close must end the stream")
      (catch Throwable _))
    (is (= 0 @(:active state)) "disconnect releases the stream permit")
    (is (= 2 (count @writes)))
    (is (every? #(str/includes? % "event: datastar-patch-elements") @writes))
    (is (every? #(str/includes? % "data: selector #otel-live") @writes))
    (is (str/includes? (first @writes) "snapshot-1"))
    (is (str/includes? (second @writes) "snapshot-2")))
  (is (demo-datastar/sse-request?
       {:query-string "x=1&datastar-sse=true&datastar-selector=%0Aevent:evil"}))
  (is (not (demo-datastar/sse-request?
            {:query-string "datastar-sse=true%0Aevent:evil"}))
      "only the exact flag is recognized; selector input is ignored"))

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
            _ (http-client/get (str base "/"))
            _ (http-client/get (str base "/assets/datastar.js"))
            sse ((demo/handler (:app lifecycle))
                 {:request-method :get :uri "/"
                  :query-string "datastar-sse=true"
                  :headers {}})
            _ (try
                (http-body/write-body-to-sink
                 (:body sse) sse
                 (reify http-body/Sink
                   (sink-write! [_ _ _ _]
                     (throw (ex-info "test reader closed" {})))
                   (sink-close! [_] nil)))
                (catch Throwable _))
            _ (is (true? (sdk/force-flush! (:otel lifecycle))))
            before (decode (http-client/get (str base "/api/summary")))
            work (http-client/get (str base "/work")
                                  {:headers {"traceparent" traceparent}
                                   :conn-timeout 2000 :socket-timeout 5000
                                   :throw-exceptions false})]
        (is (= 0 (:traceCount before))
            "viewer pages, assets, APIs, and SSE rendering create no recursive traces")
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
              "the trace detail API returns a correlated log")
          (let [page (http-client/get (str base "/traces/" trace-id))]
            (is (= 200 (:status page)))
            (is (str/includes? (:body page) "<details open>"))
            (is (str/includes? (:body page) "HTTP GET /upstream"))
            (is (not (str/includes? (:body page) "<script"))))
          (let [before-post (:traceCount (decode (http-client/get (str base "/api/summary"))))
                generated (http-client/post (str base "/work")
                                            {:follow-redirects false
                                             :throw-exceptions false})
                after-post (:traceCount (decode (http-client/get (str base "/api/summary"))))]
            (is (= 303 (:status generated)))
            (is (= "/" (get-in generated [:headers "location"])))
            (is (= (inc before-post) after-post)
                "POST returns only after its request span is durably flushed"))))
      (finally (demo/stop! lifecycle)))))
