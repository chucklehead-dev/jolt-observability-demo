(ns demo.aspect-journal-test
  (:require [clojure.test :refer [deftest is testing]]
            [demo.aspect-journal :as journal]))

(def outer {:id :agent/run :advice-role :agent/run
            :library {:id 'samizdat/core :version "fixture"}})
(def inner {:id :model/request :advice-role :genai/request
            :library {:id 'samizdat/core :version "fixture"}})

(deftest synchronous-events-are-balanced-and-parented
  (let [j (journal/journal 16)
        value (binding [journal/*journal* j]
                (journal/around
                 outer
                 (fn []
                   (journal/around inner (fn [] :answer)))))]
    (is (= :answer value))
    (is (= [:enter :enter :return :return]
           (mapv :phase (journal/snapshot j))))
    (is (= [1 2 2 1]
           (mapv :operation-id (journal/snapshot j))))
    (is (= [nil 1 1 nil]
           (mapv :parent-operation-id (journal/snapshot j))))
    (is (every? #(not (contains? % :value)) (journal/snapshot j)))))

(deftest application-exception-retains-identity-without-content
  (let [j (journal/journal)
        boom (Exception. "private model response")
        seen (atom nil)]
    (try
      (binding [journal/*journal* j]
        (journal/around outer (fn [] (throw boom))))
      (catch Exception error (clojure.core/reset! seen error)))
    (is (identical? boom @seen))
    (is (= [:enter :throw] (mapv :phase (journal/snapshot j))))
    (is (= "java.lang.Exception" (:exception-type (last (journal/snapshot j)))))
    (is (not-any? #(some (fn [[_ v]] (= "private model response" v)) %)
                  (journal/snapshot j)))))

(deftest retention-is-bounded-and-reset-is-explicit
  (let [j (journal/journal 3)]
    (binding [journal/*journal* j]
      (journal/around outer (fn [] :one))
      (journal/around inner (fn [] :two)))
    (is (= 3 (count (journal/snapshot j))))
    (is (= [2 3 4] (mapv :seq (journal/snapshot j))))
    (journal/reset! j)
    (is (empty? (journal/snapshot j)))))

(deftest journal-notifies-after-each-observation
  (let [notifications (atom 0)
        j (journal/journal 8 #(swap! notifications inc))]
    (binding [journal/*journal* j]
      (journal/around outer (fn [] :ok)))
    (is (= 2 @notifications))
    (is (= [:enter :return] (mapv :phase (journal/snapshot j))))))

(deftest no-bound-journal-is-a-noop
  (is (= :plain (journal/around outer (fn [] :plain)))))
