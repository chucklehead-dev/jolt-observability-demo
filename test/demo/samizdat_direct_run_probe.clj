(ns demo.samizdat-direct-run-probe
  "Diagnostic entry point for the deterministic real-loop fixture."
  (:require [samizdat.embed :as embed]))

(defn -main [& _]
  (let [root (or (System/getenv "DEMO_SAMIZDAT_ROOT")
                 (throw (ex-info "DEMO_SAMIZDAT_ROOT is required" {})))
        embedded (embed/open!
                  {:run {:root root :max-turns 8 :beam-width 1
                         :verify-focused? false :require-test? false}
                   :db {:path (or (System/getenv "DEMO_SAMIZDAT_DB") ":memory:")}})]
    (try
      (let [handle (embed/start-run!
                    embedded
                    {:problem "Fix square, run its regression test, and report the result."
                     :start-timeout-ms 10000})]
        (prn {:run-id (:run-id handle)})
        (let [result @(:future handle)]
          (prn {:result (select-keys result [:status :answer :run-id])})))
      (catch Throwable error
        (prn {:error-type (str (class error))
              :error (ex-message error)
              :data (ex-data error)})
        (System/exit 1))
      (finally
        (prn {:close (embed/close! embedded 5000)})))))
