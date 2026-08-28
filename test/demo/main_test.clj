(ns demo.main-test
  (:require [clojure.data.json :as json]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [demo.datastar :as demo-datastar]
            [demo.main :as demo]
            [demo.otlp :as demo-otlp]
            [demo.samizdat-kindly :as samizdat-kindly]
            [jolt.http-client :as http-client]
            [jolt.http.body :as http-body]
            [jolt.http.server :as http-server]
            [jdbc.core :as jdbc]
            [otel.exporter.chdb.explorer :as explorer]
            [otel.otlp.http-receiver :as otlp-receiver]
            [otel.sdk.export :as export]
            [otel.sdk :as sdk]
            [otel.viewer :as viewer]
            [oscope.live :as oscope]
            [teensyp.ffi-net :as net]))

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
     :work-fn (fn [_] {:upstream {:ok true}})
     :agent-work-fn (fn [_ capture-response?]
                      {:response-captured capture-response?})
     :agent-intervention-work-fn
     (fn [_] {:response-captured true :controller-intervened true})}))

(defn decode [response]
  (json/read-str (:body response) :key-fn keyword))

(deftest model-telemetry-defaults-are-private-and-reasoning-safe
  (let [app (test-app)]
    (is (= "local-model-host" (:lemonade-telemetry-address app)))
    (is (true? (:lemonade-disable-thinking? app))))
  (is (false? (:lemonade-disable-thinking?
               (demo/app-context {:lemonade-disable-thinking? false})))
      "callers can explicitly opt back into provider thinking"))

(deftest all-otlp-signal-routes-reach-the-suppressed-receiver
  (let [seen (atom [])
        h (demo/handler
           (assoc (test-app)
                  :otlp-handler
                  (fn [request]
                    (swap! seen conj
                           [(:uri request)
                            (otlp-receiver/telemetry-suppressed? request)])
                    {:status 202 :headers {} :body ""})))]
    (doseq [path [otlp-receiver/traces-path
                  otlp-receiver/logs-path
                  otlp-receiver/metrics-path]]
      (is (= path (demo/route-for path)))
      (is (= 202 (:status (h {:request-method :post :uri path})))))
    (is (= [[otlp-receiver/traces-path true]
            [otlp-receiver/logs-path true]
            [otlp-receiver/metrics-path true]]
           @seen))))

(deftest agent-demo-routes-select-content-capture-without-http-wrapper-spans
  (let [calls (atom [])
        flushes (atom 0)
        app (assoc (test-app)
                   :agent-work-fn (fn [_ capture-response?]
                                    (swap! calls conj capture-response?))
                   :agent-intervention-work-fn
                   (fn [_] (swap! calls conj :intervention))
                   :flush-fn #(swap! flushes inc))
        h (demo/handler app)]
    (is (= "/agent-work" (demo/route-for "/agent-work")))
    (is (= "/agent-work-with-response"
           (demo/route-for "/agent-work-with-response")))
    (is (= "/agent-work-intervention"
           (demo/route-for "/agent-work-intervention")))
    (is (= 303 (:status (h {:request-method :post :uri "/agent-work"}))))
    (is (= 204 (:status
                (h {:request-method :post :uri "/agent-work-with-response"
                    :headers {"x-otel-enhancement" "fetch"}}))))
    (is (= 303 (:status
                (h {:request-method :post :uri "/agent-work-intervention"}))))
    (is (= [false true :intervention] @calls))
    (is (= 3 @flushes))))

(deftest trace-workbench-query-selection-is-bounded-and-applied
  (is (= {"service" "api service" "operation" "GET /users"}
         (demo/parse-query-params
          "service=api+service&operation=GET%20%2Fusers&service=ignored")))
  (is (= {:service "" :operation "" :status ""
          :min-duration-ms "" :window ""}
         (demo/trace-filter-selection "service=%zz&status=maybe")))
  (let [seen (atom [])
        app (demo/app-context
             {:summary-fn (constantly sample-summary)
              :filtered-traces-fn
              (fn [selection now]
                (swap! seen conj [:traces selection now])
                sample-traces)
              :trace-filter-options-fn
              (fn [selection now]
                (swap! seen conj [:options selection now])
                {:selected selection
                 :service-options [{:value "api" :label "API"}]
                 :status-options [{:value "error" :label "Error"}]
                 :window-options [{:value "1h" :label "Last hour"}]})
              :now-nanos-fn (constantly 123)
              :trace-fn (fn [id] {:traceId id :spans [] :spanTree [] :logs []})
              :logs-fn (constantly sample-logs)
              :work-fn (fn [_] {:upstream {:ok true}})})
        query "service=api&operation=GET+%2Fusers&status=error&min-duration-ms=12.5&window=1h"
        response ((demo/raw-handler app)
                  {:request-method :get :uri "/" :query-string query})
        selected {:service "api" :operation "GET /users" :status "error"
                  :min-duration-ms "12.5" :window "1h"}]
    (is (= 200 (:status response)))
    (is (str/includes? (:body response) "value=\"api\" selected"))
    (is (str/includes? (:body response) "value=\"GET /users\""))
    (is (= [[:options selected 123] [:traces selected 123]] @seen))
    (reset! seen [])
    (is (= sample-traces
           (decode ((demo/raw-handler app)
                    {:request-method :get :uri "/api/traces"
                     :query-string query}))))
    (is (= [[:traces selected 123]] @seen))))

(deftest trace-workbench-query-is-bounded-parameterized-and-group-safe
  (let [calls (atom [])
        now 2000000000000000000
        selected {:service "api'; DROP TABLE otel_traces; --"
                  :operation "GET /users" :status "error"
                  :min-duration-ms "12.5" :window "1h"}]
    (with-redefs [jdbc/fetch (fn [_ query] (swap! calls conj query) [])]
      (demo/query-traces ::connection selected now))
    (let [[sql start service operation duration] (first @calls)]
      (is (= (- now (* 60 60 1000000000)) start))
      (is (= (:service selected) service))
      (is (= "GET /users" operation))
      (is (= 12500000 duration))
      (is (not (str/includes? sql (:service selected))))
      (is (not (str/includes? sql (:operation selected))))
      (is (str/includes? sql "GROUP BY TraceId HAVING"))
      (is (str/includes? sql "countIf(ServiceName = ?) > 0"))
      (is (str/includes? sql "positionCaseInsensitiveUTF8(SpanName, ?)"))
      (is (str/includes? sql "max(Duration) >= ?"))
      (is (str/includes? sql "LIMIT 100"))))
  (with-redefs [explorer/top-values
                (fn [_ request]
                  (is (= :spans (:signal request)))
                  [{:field :service-name :value "worker" :count 3}])]
    (let [model (demo/query-trace-filter-options
                 ::connection {:service "api"} 2000000000000000000)]
      (is (= [{:value "api" :label "api"}
              {:value "worker" :label "worker (3)"}]
             (:service-options model)))
      (is (= "api" (get-in model [:selected :service]))))))

(def ^:private live-test-port (atom (+ 28600 (rand-int 200))))

(defn- next-live-test-port [] (swap! live-test-port inc))

(defn- raw-response!
  "Send one raw HTTP request and require the server to answer and close within
  the bound. Closing the client on timeout also releases the test worker."
  [port request]
  (let [socket (atom nil)
        worker
        (future
          (let [client (net/connect-loopback port)]
            (reset! socket client)
            (try
              (net/client-send-all client (.getBytes request "UTF-8"))
              (loop [response ""]
                (let [chunk (try
                              (net/client-recv client 8192)
                              (catch Throwable error
                                (if (= :connection-reset
                                       (:jolt.net/kind (ex-data error)))
                                  ::reset
                                  (throw error))))]
                  (cond
                    (= ::reset chunk) {:response response :reset? true}
                    chunk (recur (str response (String. chunk "UTF-8")))
                    :else {:response response :reset? false})))
              (finally
                (net/close! client)))))]
    (let [response (deref worker 7000 ::timeout)]
      (when (= ::timeout response)
        (when-let [client @socket] (net/close! client))
        (deref worker 2000 nil)
        (throw (ex-info "raw loopback request did not complete"
                        {:port port})))
      response)))

(defn- raw-status [{:keys [response]}]
  (some-> (re-find #"HTTP/1\.1 ([0-9]+)" response) second parse-long))

(defn- raw-header [{:keys [response]} header]
  (let [pattern (re-pattern (str "(?i)(?:^|\\r?\\n)" header ":\\s*([^\\r\\n]+)"))]
    (some-> (re-find pattern response) second str/trim str/lower-case)))

(defn- stop-server-bounded! [server]
  (let [worker (future (http-server/stop-server server) true)
        result (deref worker 7000 ::timeout)]
    (when (= ::timeout result)
      (throw (ex-info "test HTTP server did not stop" {})))
    result))

(def otlp-trace-id "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa")
(def otlp-root-span-id "1111111111111111")
(def otlp-child-span-id "2222222222222222")

(def otlp-wire
  {"resourceSpans"
   [{"resource"
     {"attributes"
      [{"key" "service.name" "value" {"stringValue" "otlp-loopback"}}]}
     "scopeSpans"
     [{"scope" {"name" "demo.otlp" "version" "1"}
       "spans"
       [{"traceId" otlp-trace-id
         "spanId" otlp-root-span-id
         "name" "ingest-root"
         "kind" 2
         "startTimeUnixNano" "1785609674781645000"
         "endTimeUnixNano" "1785609674785645000"
         "status" {"code" 1}}
        {"traceId" otlp-trace-id
         "spanId" otlp-child-span-id
         "parentSpanId" otlp-root-span-id
         "name" "ingest-child"
         "kind" 3
         "startTimeUnixNano" "1785609674782645000"
         "endTimeUnixNano" "1785609674784645000"
         "events" [{"timeUnixNano" "1785609674783645000"
                     "name" "exception"
                     "attributes"
                     [{"key" "exception.type"
                       "value" {"stringValue" "example.SafeFailure"}}]}]
         "status" {"code" 1}}]}]}]})

(defn- chunked-request-body [chunks]
  (let [remaining (atom chunks)]
    (reify http-body/RequestBody
      (body-recv [_]
        (let [chunk (first @remaining)]
          (swap! remaining next)
          chunk))
      (body-bytes [_] (throw (ex-info "parser must read bounded chunks" {})))
      (body-string [_ _] (throw (ex-info "parser must count encoded bytes" {}))))))

(deftest otlp-parser-counts-actual-utf8-bytes-and-stops-at-limit
  (let [payload (json/write-str {"resourceSpans" [] "label" "trace-😀"})
        encoded (.getBytes payload "UTF-8")
        split (quot (alength encoded) 2)
        left (byte-array split)
        right (byte-array (- (alength encoded) split))]
    (System/arraycopy encoded 0 left 0 split)
    (System/arraycopy encoded split right 0 (alength right))
    (let [parsed (demo-otlp/parse-json-body
                  {:body (chunked-request-body [left right])}
                  (alength encoded))]
      (is (= (alength encoded) (:encoded-bytes parsed)))
      (is (= "trace-😀" (get (:value parsed) "label"))))
    (let [failure (try
                    (demo-otlp/parse-json-body
                     {:body (chunked-request-body [left right])}
                     (dec (alength encoded)))
                    nil
                    (catch Throwable error error))]
      (is (= :otel.otlp.http-receiver/body-too-large
             (:type (ex-data failure))))
      (is (= (alength encoded) (:actual (ex-data failure)))))))

(deftest otlp-ring-policy-is-suppressed-before-instrumentation
  (let [suppressed? (atom nil)
        exported (atom [])
        receiver-handler
        (demo-otlp/wrap-close-rejected-bodies
         (otlp-receiver/handler
          {:parse-body (fn [request limit]
                         (reset! suppressed?
                                 (otlp-receiver/telemetry-suppressed? request))
                         (demo-otlp/parse-json-body request limit))
           :exporter ::fake
           :export-spans! (fn [_ spans]
                            (swap! exported into spans)
                            true)
           :max-body-bytes demo-otlp/max-body-bytes
           :max-concurrency demo-otlp/max-concurrency}))
        h (demo/handler (assoc (test-app) :otlp-handler receiver-handler))
        body (json/write-str otlp-wire)
        request {:request-method :post :uri "/v1/traces"
                 :headers {"content-type" "application/json"}
                 :body body}
        response (h request)]
    (is (= "/v1/traces" (demo/route-for "/v1/traces")))
    (is (= 200 (:status response)))
    (is (true? @suppressed?))
    (is (= ["ingest-root" "ingest-child"] (mapv :name @exported)))
    (is (= 405 (:status (h (assoc request :request-method :get)))))
    (is (= 415 (:status (h (assoc-in request [:headers "content-type"]
                                  "application/x-protobuf")))))
    (let [too-large (h (assoc-in request [:headers "content-length"]
                                 (str (inc demo-otlp/max-body-bytes))))]
      (is (= 413 (:status too-large)))
      (is (= "close" (get-in too-large [:headers "Connection"]))))))

(deftest rejected-live-otlp-bodies-release-the-http-producer
  (let [port (next-live-test-port)
        block-export? (atom false)
        export-entered (promise)
        release-export (promise)
        exporter
        (reify export/SpanExporter
          (export-spans! [_ _]
            (when @block-export?
              (deliver export-entered true)
              @release-export)
            true)
          (flush-exporter! [_] true)
          (shutdown-exporter! [_] true))
        app (assoc (test-app) :otlp-handler (demo-otlp/handler exporter))
        server (http-server/run-server (demo/handler app)
                                       :port port :server-name "127.0.0.1"
                                       :reuse-address? true)]
    (Thread/sleep 250)
    (try
      (testing "oversized declared length rejects early and releases a queued producer"
        (let [body (apply str (repeat 100000 "x"))
              response
              (raw-response!
               port
               (str "POST /v1/traces HTTP/1.1\r\n"
                    "Host: localhost\r\n"
                    "Content-Type: application/json\r\n"
                    "Content-Length: " (inc demo-otlp/max-body-bytes) "\r\n\r\n"
                    body))]
          (is (or (:reset? response)
                  (and (= 413 (raw-status response))
                       (= "close" (raw-header response "connection"))))
              "the rejected socket terminates instead of parking its producer")))

      (testing "measured chunked limit crossing releases unread body bytes"
        (let [chunk-size (+ demo-otlp/max-body-bytes 8192)
              body (apply str (repeat chunk-size "x"))
              response
              (raw-response!
               port
               (str "POST /v1/traces HTTP/1.1\r\n"
                    "Host: localhost\r\n"
                    "Content-Type: application/json\r\n"
                    "Transfer-Encoding: chunked\r\n\r\n"
                    (Long/toHexString chunk-size) "\r\n" body "\r\n"))]
          (is (or (:reset? response)
                  (and (= 413 (raw-status response))
                       (= "close" (raw-header response "connection"))))
              "the cap-crossing socket terminates with unread chunk bytes")))

      (testing "concurrent rejection releases its body while the admitted export proceeds"
        (reset! block-export? true)
        (let [payload (json/write-str otlp-wire)
              admitted
              (future
                (http-client/post
                 (str "http://127.0.0.1:" port "/v1/traces")
                 {:headers {"Content-Type" "application/json"}
                  :body payload :conn-timeout 2000 :socket-timeout 5000
                  :throw-exceptions false}))]
          (is (= true (deref export-entered 5000 ::timeout))
              "first request owns the receiver concurrency slot")
          (let [policy-rejected
                (raw-response!
                 port
                 (str "POST /v1/traces HTTP/1.1\r\n"
                      "Host: localhost\r\n"
                      "Content-Type: application/json\r\n"
                      "Content-Length: 0\r\n\r\n"))]
            (is (= 429 (raw-status policy-rejected)))
            (is (= "close" (raw-header policy-rejected "connection"))))
          (let [body (apply str (repeat 100000 "x"))
                rejected
                (raw-response!
                 port
                 (str "POST /v1/traces HTTP/1.1\r\n"
                      "Host: localhost\r\n"
                      "Content-Type: application/json\r\n"
                      "Content-Length: " (count body) "\r\n\r\n" body))]
            (is (or (:reset? rejected)
                    (and (= 429 (raw-status rejected))
                         (= "close" (raw-header rejected "connection"))))
                "the concurrent body producer is released by connection close"))
          (reset! block-export? false)
          (deliver release-export true)
          (let [response (deref admitted 5000 ::timeout)]
            (is (not= ::timeout response))
            (is (= 200 (:status response))))))

      (is (= 200 (:status
                  (http-client/get
                   (str "http://127.0.0.1:" port "/api/summary")
                   {:conn-timeout 2000 :socket-timeout 5000
                    :throw-exceptions false})))
          "the same server makes progress after all three rejection paths")
      (finally
        (deliver release-export true)
        (is (true? (stop-server-bounded! server))
            "rejected request producers do not strand server shutdown")))))

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
    (is (str/includes? body "id=\"otel-live\" data-otel-live=\"true\""))
    (is (str/includes? (viewer/enhancement-script) "new EventSource"))
    (is (str/includes? (viewer/enhancement-script) "datastar-sse"))
    (is (not (str/includes? body "/assets/datastar.js")))
    (is (str/includes? body
                       "<script src=\"/assets/otel-viewer.js?v=2\" defer></script>"))
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

(deftest datastar-stream-stops-for-server-shutdown
  (let [state (demo-datastar/stream-state
               {:interval-ms 10 :heartbeat-ms 100 :max-streams 1})
        response (demo-datastar/stream-response state (constantly "snapshot"))
        writes (atom 0)
        writer (future
                 (http-body/write-body-to-sink
                  (:body response) response
                  (reify http-body/Sink
                    (sink-write! [_ _ _ _] (swap! writes inc))
                    (sink-close! [_] nil))))]
    (loop [attempt 0]
      (when (and (zero? @writes) (< attempt 100))
        (Thread/sleep 5)
        (recur (inc attempt))))
    (is (pos? @writes) "the live writer started")
    (demo-datastar/stop-streams! state)
    (is (nil? (deref writer 1000 ::timeout))
        "shutdown finishes the stream without a peer write failure")
    (is (= 0 @(:active state)) "shutdown releases the stream permit")
    (is (= 503 (:status (demo-datastar/stream-response
                         state (constantly "late"))))
        "shutdown rejects new streams")))

(deftest datastar-peer-disconnect-is-normal-stream-completion
  (let [state (demo-datastar/stream-state
               {:interval-ms 0 :heartbeat-ms 100 :max-streams 1})
        response (demo-datastar/stream-response state (constantly "snapshot"))
        sink (reify http-body/Sink
               (sink-write! [_ _ _ _]
                 (throw (ex-info "peer left"
                                 {:jolt.net/kind :connection-reset})))
               (sink-close! [_] nil))]
    (is (nil? (http-body/write-body-to-sink (:body response) response sink)))
    (is (= 0 @(:active state)))))

(deftest live-sse-crosses-the-real-http-server-before-close
  (let [port (next-live-test-port)
        lifecycle (demo/start! {:port port :db-spec "chdb::memory:"})
        client (atom nil)
        reader
        (future
          (let [fd (net/connect-loopback port)]
            (reset! client fd)
            (try
              (net/client-send-all
               fd (.getBytes
                   (str "GET /?datastar-sse=true HTTP/1.1\r\n"
                        "Host: 127.0.0.1\r\nConnection: close\r\n\r\n")
                   "UTF-8"))
              (loop [received ""]
                (if (str/includes? received "event: datastar-patch-elements")
                  received
                  (if-let [chunk (net/client-recv fd 8192)]
                    (recur (str received (String. chunk "UTF-8")))
                    received)))
              (finally (net/close! fd)))))]
    (try
      (let [received (deref reader 5000 ::timeout)]
        (is (not= ::timeout received)
            "the initial live event is flushed before the infinite body closes")
        (is (str/includes? received "HTTP/1.1 200 OK"))
        (is (str/includes? received "Content-Type: text/event-stream"))
        (is (str/includes? received "selector #otel-live")))
      (finally
        (when-let [fd @client] (net/close! fd))
        (deref reader 2000 nil)
        (demo/stop! lifecycle)))))

(deftest lifecycle-retries-ingress-stop-before-closing-owned-state
  (let [port (next-live-test-port)
        lifecycle (demo/start! {:port port :db-spec "chdb::memory:"})
        real-stop http-server/stop-server
        attempts (atom 0)]
    (try
      (with-redefs [http-server/stop-server
                    (fn [server]
                      (if (= 1 (swap! attempts inc))
                        (throw (ex-info "injected ingress stop timeout" {}))
                        (real-stop server)))]
        (is (thrown-with-msg? clojure.lang.ExceptionInfo
                              #"injected ingress stop timeout"
                              (demo/stop! lifecycle)))
        (is (false? @(:closed? (:oscope-source lifecycle)))
            "an unconfirmed ingress stop leaves oscope callbacks live")
        (is (= [{:answer 1}]
               (jdbc/fetch (:connection lifecycle) "select 1 as answer")))
        (is (= :closed (:status (demo/stop! lifecycle)))
            "a retry confirms ingress stopped before closing the database")
        (is (true? @(:closed? (:oscope-source lifecycle))))
        (is (= 2 @attempts)))
      (finally (demo/stop! lifecycle)))))

(deftest lifecycle-retires-shared-oscope-before-sdk-and-connection
  (let [port (next-live-test-port)
        events (atom [])
        lifecycle (demo/start!
                   {:port port :db-spec "chdb::memory:"
                    :workbench-source-close!
                    (fn [] (swap! events conj :workbench-source)
                      {:status :closed})})
        real-oscope-close oscope/close!
        real-sdk-shutdown sdk/shutdown!]
    (try
      (with-redefs
        [oscope/close!
         (fn [source]
           (swap! events conj :oscope)
           (real-oscope-close source))
         sdk/shutdown!
         (fn [handle]
           (swap! events conj :sdk)
           (is (true? @(:closed? (:oscope-source lifecycle))))
           (is (= [{:answer 1}]
                  (jdbc/fetch (:connection lifecycle) "select 1 as answer"))
               "the shared connection remains open while oscope retires")
           (real-sdk-shutdown handle))]
        (is (= :closed (:status (demo/stop! lifecycle)))))
      (is (= [:workbench-source :oscope :sdk] @events))
      (is (thrown? Throwable
                   (jdbc/fetch (:connection lifecycle) "select 1 as answer")))
      (finally (demo/stop! lifecycle)))))

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
              database (some #(when (= "SELECT demo readiness" (:name %)) %) spans)
              producer (some #(when (= "demo.jobs publish" (:name %)) %) spans)
              consumer (some #(when (= "demo.jobs process" (:name %)) %) spans)
              client (some #(when (and (= "HTTP GET /upstream" (:name %))
                                       (= "client" (:kind %))) %) spans)
              upstream (some #(when (and (= "HTTP GET /upstream" (:name %))
                                         (= "server" (:kind %))) %) spans)]
          (is (= "HTTP GET /work" (:rootSpan summary)))
          (is (= 6 (count spans))
              "one trace contains DB, queue propagation, and both HTTP edges")
          (is (= remote-span-id (:parentSpanId server)))
          (is (= (:spanId server) (:parentSpanId database)))
          (is (= (:spanId server) (:parentSpanId producer)))
          (is (= (:spanId producer) (:parentSpanId consumer)))
          (is (= (:spanId server) (:parentSpanId client)))
          (is (= (:spanId client) (:parentSpanId upstream)))
          (is (some #(and (= (:traceId detail) (:traceId %))
                          (not= "" (:spanId %))) (:logs detail))
              "the trace detail API returns a correlated log")
          (let [page (http-client/get (str base "/traces/" trace-id))]
            (is (= 200 (:status page)))
            (is (str/includes? (:body page) "<details open>"))
            (is (str/includes? (:body page) "HTTP GET /upstream"))
            (is (str/includes? (:body page) "SELECT demo readiness"))
            (is (str/includes? (:body page) "demo.jobs process"))
            (is (not (str/includes? (:body page) "<script"))))
          (let [before-post (:traceCount (decode (http-client/get (str base "/api/summary"))))
                live-response ((demo/handler (:app lifecycle))
                               {:request-method :get :uri "/"
                                :query-string "datastar-sse=true" :headers {}})
                initial (promise)
                changed (promise)
                first-event (atom nil)
                cancel-writer? (atom false)
                live-writer
                (future
                  (try
                    (http-body/write-body-to-sink
                     (:body live-response) live-response
                     (reify http-body/Sink
                       (sink-write! [_ bytes offset length]
                         (when @cancel-writer?
                           (throw (ex-info "live test cancelled" {})))
                         (let [event (String. bytes offset length "UTF-8")]
                           (if-let [first @first-event]
                             (when (not= first event)
                               (deliver changed event)
                               (throw (ex-info "live update observed" {})))
                             (do (reset! first-event event)
                                 (deliver initial event)))))
                       (sink-close! [_] nil)))
                    (catch Throwable _ :cancelled)))
                _ (is (not= :timeout (deref initial 5000 :timeout))
                      "the live stream emits its initial durable snapshot")
                generated (http-client/post
                           (str base "/work")
                           {:headers {"X-Otel-Enhancement" "fetch"}
                            :throw-exceptions false})
                update-event (deref changed 5000 :timeout)
                _ (reset! cancel-writer? true)
                writer-result (deref live-writer 5000 ::timeout)
                after-post (:traceCount (decode (http-client/get (str base "/api/summary"))))]
            (is (= 204 (:status generated)))
            (is (not= :timeout update-event)
                "an open SSE stream receives the generated trace without refresh")
            (is (= :cancelled writer-result)
                "the in-memory SSE writer cancels and completes within the bound")
            (is (str/includes? update-event "class=\"otel-trace-list\""))
            (is (= (inc before-post) after-post)
                "POST returns only after its request span is durably flushed"))))
      (finally (demo/stop! lifecycle)))))

(deftest agent-model-traces-cover-control-loop-and-explicit-content-policy
  (let [port (next-live-test-port)
        lifecycle (demo/start! {:port port :db-spec "chdb::memory:"})
        app (assoc (:app lifecycle)
                   :lemonade-base-url "http://model.test/v1"
                   :lemonade-model "test-model"
                   :lemonade-telemetry-address "local-model-host"
                   :lemonade-disable-thinking? true)
        requests (atom [])]
    (try
      (with-redefs
        [http-client/post
         (fn [url request]
           (let [payload (json/read-str (:body request) :key-fn keyword)
                 revised? (> (count (:messages payload)) 1)
                 content (if revised? "revised controlled answer"
                             "<think>private chain of thought</think>bounded sanitized answer")]
             (swap! requests conj [url payload])
             {:status 200 :headers {}
              :body (json/write-str
                     {:choices [{:message {:role "assistant" :content content}
                                 :finish_reason "stop"}]
                      :usage {:prompt_tokens (if revised? 31 17)
                              :completion_tokens (if revised? 7 5)
                              :total_tokens (if revised? 38 22)}})}))]
        (let [h (demo/handler app)]
          (is (= 303 (:status
                      (h {:request-method :post :uri "/agent-work"}))))
          (is (= 303 (:status
                      (h {:request-method :post
                          :uri "/agent-work-with-response"}))))
          (is (= 303 (:status
                      (h {:request-method :post
                          :uri "/agent-work-intervention"}))))))
      (is (= 4 (count @requests)))
      (is (every? #(= "http://model.test/v1/chat/completions" (first %))
                  @requests))
      (is (every? #(= false
                         (get-in % [1 :chat_template_kwargs :enable_thinking]))
                  @requests))
      (is (true? (sdk/force-flush! (:otel lifecycle))))
      (let [traces (demo/query-traces (:connection lifecycle))
            details (mapv #(demo/query-trace (:connection lifecycle) (:traceId %))
                          traces)
            captured (some (fn [detail]
                             (when (and (= 7 (count (:spans detail)))
                                        (some #(= "bounded sanitized answer"
                                             (get-in % [:attributes
                                                        "samizdat.response.sanitized"]))
                                              (:spans detail)))
                               detail))
                           details)
            intervention (some #(when (= 11 (count (:spans %))) %) details)
            omitted (some #(when (and (= 7 (count (:spans %)))
                                      (not= (:traceId %) (:traceId captured))) %)
                          details)]
        (is (= 3 (count traces)) "agent routes do not create wrapper HTTP traces")
        (is (every? #(= "samizdat.run" (:rootSpan %)) traces))
        (is (= [7 7 11] (sort (map :spanCount traces))))
        (is (some? captured))
        (is (some? omitted))
        (is (some? intervention))
        (let [by-name (into {} (map (juxt :name identity)) (:spans captured))]
          (is (str/blank? (:parentSpanId (get by-name "samizdat.run"))))
          (doseq [[child parent]
                  [["samizdat.control-loop" "samizdat.run"]
                   ["samizdat.branch B1" "samizdat.control-loop"]
                   ["samizdat.turn 1" "samizdat.branch B1"]
                   ["chat" "samizdat.turn 1"]
                   ["HTTP POST /v1/chat/completions" "chat"]
                   ["execute_tool response_length" "samizdat.turn 1"]]]
            (is (= (:spanId (get by-name parent))
                   (:parentSpanId (get by-name child)))
                (str child " is parented by " parent))))
        (let [spans (:spans intervention)
              by-name (into {} (map (juxt :name identity)) spans)
              by-turn-name
              (fn [name turn]
                (some #(when (and (= name (:name %))
                                  (= (str turn)
                                     (str (get-in % [:attributes
                                                    "samizdat.turn.number"])))) %)
                      spans))
              turn-1 (get by-name "samizdat.turn 1")
              turn-2 (get by-name "samizdat.turn 2")
              chat-1 (by-turn-name "chat" 1)
              chat-2 (by-turn-name "chat" 2)
              http-1 (by-turn-name "HTTP POST /v1/chat/completions" 1)
              http-2 (by-turn-name "HTTP POST /v1/chat/completions" 2)]
          (is (= (:spanId (get by-name "samizdat.branch B1"))
                 (:parentSpanId turn-1)
                 (:parentSpanId (get by-name "controller intervention"))
                 (:parentSpanId turn-2)))
          (is (= (:spanId turn-1) (:parentSpanId chat-1)))
          (is (= (:spanId chat-1) (:parentSpanId http-1)))
          (is (= (:spanId turn-2) (:parentSpanId chat-2)))
          (is (= (:spanId chat-2) (:parentSpanId http-2)))
          (is (= (:spanId turn-2)
                 (:parentSpanId (get by-name "execute_tool response_length")))))
        (is (not-any? #(contains? (:attributes %)
                                  "samizdat.response.sanitized")
                      (:spans omitted)))
        (is (not (str/includes? (pr-str (mapcat :spans details)) "model.test"))
            "physical model hostnames are not emitted by the default telemetry label")
        (is (not (str/includes? (pr-str (mapcat :spans details))
                                "private chain of thought"))
            "delimited reasoning is stripped even when an endpoint ignores thinking=false")
        (is (not (str/includes? (pr-str (:spans omitted))
                                "brain the size of a planet"))
            "metadata-only mode never records the prompt")
        (is (str/includes? (pr-str (:spans captured))
                           "brain the size of a planet")
            "explicit exchange mode records the bounded prompt")
        (let [api-response
              ((demo/handler app)
               {:request-method :get
                :uri (str "/api/traces/" (:traceId captured))})]
          (is (= 200 (:status api-response)))
          (is (not (str/includes? (:body api-response) "kindly"))
              "the raw trace API never persists presentation advice")
          (is (not (str/includes? (:body api-response) "otel.viewer"))))
        (let [html-captured
              (viewer/render-fragment
               {:trace (samizdat-kindly/advise-trace captured)})
              html-omitted
              (viewer/render-fragment
               {:trace (samizdat-kindly/advise-trace omitted)})
              html-intervention
              (viewer/render-fragment
               {:trace (samizdat-kindly/advise-trace intervention)})]
          (is (str/includes? html-captured "samizdat.control-loop"))
          (is (str/includes? html-captured ">Control</span>"))
          (is (str/includes? html-captured "Captured prompt"))
          (is (str/includes? html-captured "brain the size of a planet"))
          (is (str/includes? html-captured "bounded sanitized answer"))
          (is (str/includes? html-omitted
                             "Content not recorded (privacy default)"))
          (is (not (str/includes? html-omitted "bounded sanitized answer")))
          (is (str/includes? html-intervention ">Intervention</span>"))
          (is (str/includes? html-intervention
                             "first draft required a concrete correctness review"))
          (is (str/includes? html-intervention "Controller intervention:"))
          (is (str/includes? html-intervention "revised controlled answer"))))
      (finally (demo/stop! lifecycle)))))

(deftest samizdat-advice-follows-kindly-value-metadata-contract
  (let [advised
        (samizdat-kindly/advise-trace
         {:spanTree
          [{:name "chat"
            :attributes
            {"gen_ai.operation.name" "chat"
             "samizdat.prompt.content_state" "captured"
             "samizdat.response.content_state" "captured"
             "samizdat.prompt.sanitized" "bounded prompt"
             "samizdat.response.sanitized" "bounded response"}}]})
        note (get-in advised [:spanTree 0 :kindly :value])
        prompt (second note)]
    (is (= :kind/fragment (:kindly/kind (meta note))))
    (is (= "Generation"
           (get-in (meta note) [:kindly/options :otel.viewer/role])))
    (is (= :kind/code (:kindly/kind (meta prompt))))
    (is (= true (get-in (meta prompt) [:kindly/options :wrapped-value])))
    (is (= ["bounded prompt"] prompt))))

(deftest live-otlp-parent-child-ingestion-without-feedback
  (let [port (+ 27050 (rand-int 900))
        lifecycle (demo/start! {:port port :db-spec "chdb::memory:"})
        base (str "http://127.0.0.1:" port)
        live-response ((demo/handler (:app lifecycle))
                       {:request-method :get :uri "/"
                        :query-string "datastar-sse=true" :headers {}})
        initial (promise)
        changed (promise)
        writes (atom 0)
        cancel-writer? (atom false)
        live-writer
        (future
          (try
            (http-body/write-body-to-sink
             (:body live-response) live-response
             (reify http-body/Sink
               (sink-write! [_ bytes offset length]
                 (when @cancel-writer?
                   (throw (ex-info "OTLP live test cancelled" {})))
                 (let [event (String. bytes offset length "UTF-8")
                       n (swap! writes inc)]
                   (deliver initial event)
                   (when (str/includes? event otlp-trace-id)
                     (deliver changed event)
                     (throw (ex-info "OTLP live update observed" {})))
                   (when (> n 8)
                     (throw (ex-info "bounded live test exhausted writes" {})))))
               (sink-close! [_] nil)))
            (catch Throwable _ :cancelled)))]
    (try
      (is (not= :timeout (deref initial 5000 :timeout))
          "SSE emits its initial static snapshot")
      (let [payload (json/write-str otlp-wire)
            response (http-client/post
                      (str base "/v1/traces")
                      {:headers {"Content-Type" "application/json"}
                       :body payload
                       :conn-timeout 2000 :socket-timeout 5000
                       :throw-exceptions false})
            update-event (deref changed 5000 :timeout)]
        (is (= 200 (:status response)))
        (is (= {} (decode response)))
        (is (not= :timeout update-event)
            "the open SSE stream observes directly exported OTLP spans")
        (is (str/includes? update-event "ingest-root"))
        (reset! cancel-writer? true)
        (is (= :cancelled (deref live-writer 5000 ::timeout))
            "the OTLP SSE writer cancels and completes within the bound")
        (let [summary (decode (http-client/get (str base "/api/summary")))
              traces (decode (http-client/get (str base "/api/traces")))
              trace-summary (some #(when (= otlp-trace-id (:traceId %)) %) traces)
              detail (decode (http-client/get
                              (str base "/api/traces/" otlp-trace-id)))
              tree (:spanTree detail)
              root (first tree)
              child (first (:children root))
              page (http-client/get (str base "/traces/" otlp-trace-id))]
          (is (= {:traceCount 1 :spanCount 2 :logCount 0 :errorCount 0}
                 summary)
              "receiver, API, viewer, and SSE reads create no feedback spans")
          (is (= "ingest-root" (:rootSpan trace-summary)))
          (is (= 2 (:spanCount trace-summary)))
          (is (= otlp-root-span-id (:spanId root)))
          (is (= [otlp-child-span-id] (mapv :spanId (:children root))))
          (is (= "exception" (get-in child [:events 0 :name]))
              "trace detail retains bounded span events for diagnostics")
          (is (= 200 (:status page)))
          (is (str/includes? (:body page) "ingest-root"))
          (is (str/includes? (:body page) "ingest-child"))))
      (finally
        (reset! cancel-writer? true)
        (deref live-writer 5000 ::timeout)
        (demo/stop! lifecycle)))))
