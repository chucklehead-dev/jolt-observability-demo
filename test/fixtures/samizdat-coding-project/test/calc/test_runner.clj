(ns calc.test-runner
  (:require [calc.core-test]
            [clojure.test :as test]))

(defn -main [& _]
  (let [result (test/run-tests 'calc.core-test)]
    (System/exit (if (zero? (+ (:fail result) (:error result))) 0 1))))
