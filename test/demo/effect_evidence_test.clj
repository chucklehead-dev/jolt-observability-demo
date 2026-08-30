(ns demo.effect-evidence-test
  (:require [clojure.test :refer [deftest is testing]]
            [demo.effect-evidence :as evidence]))

(def subjects
  [{:subject {:kind :var-arity :fqn "demo/run" :arity {:fixed 0}}
    :closure {:effects [] :aspect-sites ["site-1"] :unknown? false}}])

(defn phase [name site-bearing?]
  {:phase name
   :coverage {:subjects 1 :subject-kinds {:var-arity 1}}
   :summaries (if site-bearing?
                subjects
                [(assoc-in (first subjects) [:closure :aspect-sites] [])])})

(def woven-report
  {:schema 1
   :analysis "jolt.effects/build-v1"
   :build-identity "build-1"
   :phases [(phase :plain false) (phase :woven true) (phase :optimized true)]
   :verification {:analysis "jolt.effects/verification-v1"
                  :phases [:plain :woven :optimized]
                  :findings []}})

(def aspect-report
  {:schema 1
   :identity "build-1"
   :aspects [{:id :demo/run :sites [{:site-id "site-1"}]}]})

(deftest accepts-non-vacuous-matched-evidence
  (is (= {:mode :woven :subjects 1 :aspect-sites 1}
         (evidence/validate! woven-report :woven aspect-report))))

(deftest rejects-vacuous-or-mismatched-evidence
  (testing "no subjects"
    (is (thrown? clojure.lang.ExceptionInfo
                 (evidence/validate!
                   (assoc-in woven-report [:phases 0 :coverage :subjects] 0)
                   :woven aspect-report))))
  (testing "identity mismatch"
    (is (thrown? clojure.lang.ExceptionInfo
                 (evidence/validate! woven-report :woven
                                     (assoc aspect-report :identity "other")))))
  (testing "site removed by optimization"
    (is (thrown? clojure.lang.ExceptionInfo
                 (evidence/validate!
                   (assoc-in woven-report [:phases 2] (phase :optimized false))
                   :woven aspect-report))))
  (testing "compiler finding"
    (is (thrown? clojure.lang.ExceptionInfo
                 (evidence/validate!
                   (assoc-in woven-report [:verification :findings]
                             [{:rule :jolt.rule/example}])
                   :woven aspect-report)))))
