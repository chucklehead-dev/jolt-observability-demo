(ns demo.samizdat-adapter-test
  (:require [clojure.core.async :as async]
            [clojure.test :refer [deftest is]]
            [demo.samizdat-adapter :as adapter]
            [demo.workbench-fixture :as fixture]
            [samizdat.embed :as embed]))

(deftest durable-tail-drives-the-workbench-even-without-a-wakeup
  (let [seen-prompt (atom nil)
        started (atom nil)
        events (atom [])
        completed (promise)
        unsubscribed (atom 0)
        journal [{:id 1 :kind "run-started" :data {}}
                 {:id 2 :kind "branch-opened" :branch_id "B1" :data {}}
                 {:id 3 :kind "turn" :branch_id "B1" :turn 1
                  :data {:tool "project/stat"}}
                 {:id 4 :kind "run-finished" :data {:status "completed"}}]]
    (with-redefs [embed/subscribe (fn [_] (async/chan 1))
                  embed/unsubscribe! (fn [_] (swap! unsubscribed inc))
                  embed/start-run!
                  (fn [_ {:keys [problem on-start]}]
                    (reset! seen-prompt problem)
                    (on-start "real-run-1")
                    {:run-id "real-run-1"
                     :abort (atom false)
                     :future (future {:status :completed
                                      :answer "Implemented and tested."})})
                  embed/journal-tail
                  (fn [_ _ cursor _]
                    (let [page (vec (filter #(> (:id %) cursor) journal))]
                      {:events page :next (or (:id (last page)) cursor)}))
                  embed/get-run
                  (fn [_ _]
                    {:run {:model "fixture-model" :status "completed"
                           :final_answer "Implemented and tested."}})]
      (let [handle
            (fixture/start-async!
             (adapter/adapter ::embedded {:start-timeout-ms 1000})
             "Use this exact coding task"
             {:started! #(reset! started [%1 %2])
              :event! #(swap! events conj %)
              :complete! #(deliver completed [%1 %2])
              :failed! #(deliver completed [:failed (ex-message %)])})
            [response capture] (deref completed 2000 ::timeout)]
        (is (= "Use this exact coding task" @seen-prompt))
        (is (= "real-run-1" (first @started)))
        (is (= [:run-opened :event :tool-completed :run-closed]
               (mapv :stage @events)))
        (is (= "Implemented and tested." response))
        (is (= "fixture-model" (:model capture)))
        (is (true? ((:join! handle) 1000)))
        (is (= 1 @unsubscribed))))))
