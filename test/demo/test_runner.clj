(ns demo.test-runner
  (:require [clojure.test :as test]
            [demo.main-test]))

(defn -main [& _]
  (let [result (test/run-tests 'demo.main-test)
        failures (+ (:fail result) (:error result))]
    (System/exit (if (zero? failures) 0 1))))
