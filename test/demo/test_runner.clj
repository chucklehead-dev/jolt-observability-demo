(ns demo.test-runner
  (:require [clojure.test :as test]
            [demo.aspect-journal-test]
            [demo.main-test]
            [demo.oscope-web-test]
            [demo.plotje-portable-test]
            [demo.portable-editors-test]
            [demo.property-test :as property]
            [demo.samizdat-adapter-test]
            [demo.samizdat-aspect-provider-test]
            [demo.workbench-test]))

(defn -main [& _]
  (let [result (test/run-tests 'demo.main-test
                               'demo.oscope-web-test
                               'demo.aspect-journal-test
                               'demo.plotje-portable-test
                               'demo.portable-editors-test
                               'demo.samizdat-adapter-test
                               'demo.samizdat-aspect-provider-test
                               'demo.workbench-test)
        properties (property/run-properties!)
        _ (doseq [{:keys [label result]} properties]
            (println "Hegel" label "seed" (:seed result)
                     "passed" (:passed? result) "flaky" (:flaky? result)))
        property-failures (count (remove #(and (get-in % [:result :passed?])
                                               (not (get-in % [:result :flaky?])))
                                         properties))
        failures (+ (:fail result) (:error result) property-failures)]
    (System/exit (if (zero? failures) 0 1))))
