(ns demo.threadstatus-probe
  "Fresh-process workload probe for ClickHouse ThreadStatus diagnostics."
  (:require [demo.main :as demo]
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

(defn- exercise! [scenario base]
  (case scenario
    "startup" nil
    "work" (dotimes [_ 12] (get! (str base "/work") {}))
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
