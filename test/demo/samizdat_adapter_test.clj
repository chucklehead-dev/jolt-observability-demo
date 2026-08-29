(ns demo.samizdat-adapter-test
  (:require [clojure.core.async :as async]
            [clojure.test :refer [deftest is]]
            [demo.samizdat-adapter :as adapter]
            [demo.workbench-fixture :as fixture]
            [otel.context :as context]
            [otel.exporter.memory :as memory]
            [otel.sdk :as sdk]
            [otel.trace :as trace]
            [samizdat.embed :as embed]))

(defn- with-memory-sdk [f]
  (let [exporter (memory/exporter)
        handle (sdk/init! {:service-name "samizdat-adapter-test"
                           :exporter exporter :processor :simple
                           :metrics? false})]
    (try (f exporter)
         (finally (sdk/shutdown! handle)))))

(deftest durable-tail-drives-the-workbench-even-without-a-wakeup
  (let [seen-prompt (atom nil)
        started (atom nil)
        events (atom [])
        completed (promise)
        unsubscribed (atom 0)
        read-suppression (atom [])
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
                    (swap! read-suppression conj
                           [:journal (context/instrumentation-suppressed?)])
                    (let [page (vec (filter #(> (:id %) cursor) journal))]
                      {:events page :next (or (:id (last page)) cursor)}))
                  embed/get-run
                  (fn [_ _]
                    (swap! read-suppression conj
                           [:run (context/instrumentation-suppressed?)])
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
        (is (seq @read-suppression))
        (is (every? true? (map second @read-suppression)))
        (is (true? ((:join! handle) 1000)))
        (is (= 1 @unsubscribed))))))

(deftest abort-is-a-semantic-child-of-the-captured-run-context
  (with-memory-sdk
    (fn [exporter]
      (let [terminal? (atom false)
            started (promise)
            completed (promise)
            tracer (sdk/tracer "samizdat-adapter-test-parent")]
        (with-redefs [embed/subscribe (fn [_] (async/chan 1))
                      embed/unsubscribe! (fn [_] nil)
                      embed/start-run!
                      (fn [_ {:keys [on-start]}]
                        (on-start "run-abort-1")
                        {:run-id "run-abort-1"
                         :future (future {:status :aborted})})
                      embed/abort! (fn [_ _] (reset! terminal? true))
                      embed/journal-tail
                      (fn [_ _ cursor _]
                        (if @terminal?
                          {:events [{:id 1 :kind "run-finished"
                                     :data {:status "aborted"}}]
                           :next 1}
                          {:events [] :next cursor}))
                      embed/get-run
                      (fn [_ _] {:run {:model "fixture" :status "aborted"}})]
          (let [handle
                (trace/with-span [_ tracer "samizdat.run"]
                  (fixture/start-async!
                   (adapter/adapter ::embedded {:start-timeout-ms 1000})
                   "abort this run"
                   {:started! #(deliver started [%1 %2])
                    :event! (fn [_])
                    :complete! #(deliver completed [%1 %2])
                    :failed! #(deliver completed [:failed (ex-message %)])}))]
            (is (not= ::timeout (deref started 1000 ::timeout)))
            ((:abort! handle))
            (is (not= ::timeout (deref completed 2500 ::timeout)))
            (is (true? ((:join! handle) 1000)))
            (let [spans (memory/spans exporter)
                  parent (first (filter #(= "samizdat.run" (:name %)) spans))
                  abort (first (filter #(= "samizdat.control abort" (:name %)) spans))]
              (is (some? abort))
              (is (= (get-in parent [:span-context :span-id])
                     (:parent-span-id abort)))
              (is (= "run-abort-1"
                     (get (:attributes abort) "samizdat.run.id"))))))))))
