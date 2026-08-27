(ns demo.threadstatus-probe
  "Fresh-process workload probe for ClickHouse ThreadStatus diagnostics."
  (:require [clojure.data.json :as json]
            [demo.main :as demo]
            [jolt.http-client :as http-client]
            [otel.sdk :as sdk]))

(defn- get! [url options]
  (http-client/get url (merge {:conn-timeout 2000
                               :socket-timeout 5000
                               :throw-exceptions false}
                              options)))

(defn- enhanced-work! [base]
  (http-client/post (str base "/work")
                    {:headers {"X-Otel-Enhancement" "fetch"}
                     :conn-timeout 2000
                     :socket-timeout 5000
                     :throw-exceptions false}))

(defn- sse! [base]
  (try
    (get! (str base "/?datastar-sse=true") {:socket-timeout 1200})
    (catch Throwable _ nil)))

(defn- otlp-body [i]
  (let [trace-id (format "%032x" (inc i))
        root-id (format "%016x" (+ 1000 (* 2 i)))
        child-id (format "%016x" (+ 1001 (* 2 i)))
        start (+ 1785609674781645000 (* i 10000000))]
    (json/write-str
     {"resourceSpans"
      [{"resource" {"attributes"
                     [{"key" "service.name"
                       "value" {"stringValue" "threadstatus-otlp"}}]}
        "scopeSpans"
        [{"scope" {"name" "demo.threadstatus"}
          "spans"
          [{"traceId" trace-id "spanId" root-id "name" "probe-root"
            "kind" 2 "startTimeUnixNano" (str start)
            "endTimeUnixNano" (str (+ start 4000000))}
           {"traceId" trace-id "spanId" child-id "parentSpanId" root-id
            "name" "probe-child" "kind" 3
            "startTimeUnixNano" (str (+ start 1000000))
            "endTimeUnixNano" (str (+ start 3000000))}]}]}]})))

(defn- otlp! [base i]
  (let [response
        (http-client/post
         (str base "/v1/traces")
         {:headers {"Content-Type" "application/json"}
          :body (otlp-body i)
          :conn-timeout 2000 :socket-timeout 5000
          :throw-exceptions false})]
    (when-not (= 200 (:status response))
      (throw (ex-info "OTLP probe export failed"
                      {:index i :status (:status response)
                       :body (:body response)})))))

(defn- exercise! [scenario base]
  (case scenario
    "startup" nil
    "work" (dotimes [_ 12] (get! (str base "/work") {}))
    "post-flush" (dotimes [_ 20] (enhanced-work! base))
    "viewer" (dotimes [_ 12]
               (get! (str base "/") {})
               (get! (str base "/api/summary") {})
               (get! (str base "/api/traces") {})
               (get! (str base "/api/logs") {}))
    "sse" (dotimes [_ 6] (sse! base))
    "sse-work" (dotimes [_ 6]
                 (let [reader (future (sse! base))]
                   (Thread/sleep 150)
                   (enhanced-work! base)
                   @reader))
    "otlp" (dotimes [i 24] (otlp! base i))
    "otlp-sse" (dotimes [round 6]
                 (let [reader (future (sse! base))]
                   (Thread/sleep 150)
                   (dotimes [i 4] (otlp! base (+ (* round 4) i)))
                   @reader))
    "mixed-stress"
    (dotimes [_ 4]
      (let [readers (doall (repeatedly 4 #(future (sse! base))))]
        (Thread/sleep 150)
        (dotimes [_ 10]
          (enhanced-work! base)
          (get! (str base "/") {})
          (get! (str base "/api/traces") {}))
        (doseq [reader readers] @reader)))
    (throw (ex-info "unknown probe scenario" {:scenario scenario}))))

(defn -main [& [scenario port-text]]
  (let [scenario (or scenario "sse-work")
        port (if port-text (parse-long port-text) 28181)
        lifecycle (demo/start! {:port port :db-spec "chdb::memory:"})
        base (str "http://127.0.0.1:" port)]
    (try
      (exercise! scenario base)
      (when-not (sdk/force-flush! (:otel lifecycle))
        (throw (ex-info "telemetry flush failed" {:scenario scenario})))
      (let [summary (get! (str base "/api/summary") {})]
        (println "THREADSTATUS_PROBE_OK" scenario (:status summary)))
      (finally
        (demo/stop! lifecycle)))))
