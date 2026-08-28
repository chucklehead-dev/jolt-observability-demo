(ns demo.samizdat-main
  "Standalone demo entry point backed by the real in-process Samizdat loop.

  Samizdat opens its own durable run store but no second HTTP server. The demo
  remains the sole web ingress and owns shutdown of the adapter, Samizdat,
  telemetry SDK, and embedded chDB in that order."
  (:require [clojure.string :as str]
            [demo.main :as demo]
            [demo.samizdat-adapter :as samizdat-adapter]
            [samizdat.embed :as embed]))

(defn- env-long [name default]
  (let [raw (System/getenv name)]
    (if (str/blank? raw) default (parse-long raw))))

(defn- env-true? [name]
  (contains? #{"1" "true" "yes"}
             (some-> (System/getenv name) str/trim str/lower-case)))

(defn- capture-content? []
  ;; Keep the original model-specific switch as a compatibility alias. Tool
  ;; arguments/results use the same bounded redaction policy, so the preferred
  ;; name describes the complete opt-in boundary.
  (or (env-true? "DEMO_CAPTURE_CONTENT")
      (env-true? "DEMO_CAPTURE_MODEL_CONTENT")))

(defn- configured-redactor []
  (let [terms (->> (str/split (or (System/getenv "DEMO_REDACT_TERMS") "") #",")
                   (map str/trim)
                   (remove str/blank?)
                   vec)]
    (fn [value]
      (reduce #(str/replace %1 %2 "[redacted]") (str value) terms))))

(defn- required-root []
  (let [root (System/getenv "DEMO_SAMIZDAT_ROOT")]
    (when (str/blank? root)
      (throw (ex-info
              "DEMO_SAMIZDAT_ROOT must name a disposable project checkout"
              {:environment "DEMO_SAMIZDAT_ROOT"})))
    root))

(defn start!
  "Open embedded Samizdat and the observability demo around it.

  Accepts ordinary demo options plus `:samizdat-overrides`, `:run-options`,
  and a required `:project-root` (or DEMO_SAMIZDAT_ROOT)."
  ([] (start! {}))
  ([{:keys [project-root samizdat-overrides run-options] :as options}]
   (let [root (or project-root (required-root))
         samizdat (embed/open!
                   (merge-with merge
                               {:run {:root root
                                      :max-turns (env-long "DEMO_SAMIZDAT_MAX_TURNS" 8)
                                      :beam-width 1
                                      :stop-on-first-done? true}
                                :db {:path (or (System/getenv "DEMO_SAMIZDAT_DB")
                                               ".data/samizdat.sqlite3")}}
                               samizdat-overrides))
         redact (configured-redactor)
         adapter (samizdat-adapter/adapter
                  samizdat
                  (merge {:start-timeout-ms 10000
                          :display-redact redact
                          :otel-content-policy
                          {:capture? (capture-content?)
                           :max-chars (env-long "DEMO_CAPTURE_MAX_CHARS" 2048)
                           :redact redact}}
                         run-options))]
     (try
       (assoc
        (demo/start!
         (-> options
             (dissoc :project-root :samizdat-overrides :run-options)
             (assoc :workbench-adapter adapter
                    :workbench-kind :samizdat
                    :workbench-source-close! #(embed/close! samizdat 5000))))
        :samizdat samizdat)
       (catch Throwable error
         (embed/close! samizdat 5000)
         (throw error))))))

(defn stop! [lifecycle]
  (demo/stop! lifecycle))

(defn- stop-until-closed! [lifecycle]
  (loop [attempt 1]
    (let [result (stop! lifecycle)]
      (when (and (= :closing (:status result)) (< attempt 12))
        (Thread/sleep 250)
        (recur (inc attempt))))))

(defn -main [& _]
  ;; Samizdat and the HTTP reactor both create workers. Mask SIGINT first so
  ;; only the primordial thread parked below can receive the shutdown signal.
  (jolt.host/block-sigint)
  (let [lifecycle (start!)]
    (println (str "Jolt observability + Samizdat workbench listening on "
                  "http://127.0.0.1:" (:port lifecycle)))
    (jolt.host/add-shutdown-hook #(stop-until-closed! lifecycle))
    (jolt.host/park-until-interrupt)))
