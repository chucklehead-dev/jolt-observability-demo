(ns calc.core-test
  (:require [calc.core :as calc]
            [clojure.test :refer [deftest is]]))

(deftest square-multiplies-a-value-by-itself
  (is (= 25 (calc/square 5))))
