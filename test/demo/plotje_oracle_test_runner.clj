(ns demo.plotje-oracle-test-runner
  (:require [clojure.test :as test]
            [demo.plotje-editor-test]))

(defn -main [& _]
  (let [{:keys [fail error]}
        (test/run-tests 'demo.plotje-editor-test)]
    (System/exit (if (zero? (+ fail error)) 0 1))))
