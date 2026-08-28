(ns demo.samizdat-journal-provider-test
  (:require [clojure.test :refer [deftest is]]
            [demo.aspect-journal :as journal]
            [demo.samizdat-aspect-provider :as otel-provider]
            [demo.samizdat-journal-provider :as provider]))

(defn- join-point [role id]
  {:id id
   :advice-role role
   :library {:id 'yogthos/samizdat
             :version otel-provider/samizdat-build-id}})

(deftest provider-implements-the-complete-selected-role-surface
  (is (= {'yogthos/samizdat otel-provider/samizdat-build-id}
         (:libraries provider/aspect-provider)))
  (is (= #{:samizdat/run :samizdat/turn :samizdat/model
           :samizdat/tool :http/client}
         (set (keys (:roles provider/aspect-provider)))))
  (doseq [role [:samizdat/run :samizdat/turn :samizdat/model :samizdat/tool]]
    (is (= {:fn 'demo.samizdat-journal-provider/around
            :contract :args-v1}
           (get-in provider/aspect-provider [:roles role]))))
  (is (= {:fn 'demo.samizdat-journal-provider/around-http-client
          :contract :replace-args-v1}
         (get-in provider/aspect-provider [:roles :http/client]))))

(deftest journal-consumer-is-useful-without-an-otel-sdk
  (let [j (journal/journal 16)
        run (join-point :samizdat/run :samizdat.embed/beam-run)
        turn (join-point :samizdat/turn :samizdat.agent.beam/turn)
        expected (Object.)
        actual
        (binding [journal/*journal* j]
          (provider/around
            run [{:problem "secret prompt"}]
            (fn []
              (provider/around turn [{:secret "tool arguments"}]
                               (fn [] expected)))))]
    (is (identical? expected actual))
    (let [[run-enter turn-enter turn-return run-return] (journal/snapshot j)]
      (is (= [:enter :enter :return :return]
             (mapv :phase [run-enter turn-enter turn-return run-return])))
      (is (= (:operation-id run-enter) (:parent-operation-id turn-enter)))
      (is (= (:operation-id turn-enter) (:operation-id turn-return)))
      (is (= (:operation-id run-enter) (:operation-id run-return))))
    (let [serialized (pr-str (journal/snapshot j))]
      (is (not (.contains serialized "secret prompt")))
      (is (not (.contains serialized "tool arguments"))))))

(deftest journal-consumer-preserves-application-exception-identity
  (let [j (journal/journal 8)
        failure (ex-info "secret failure" {:response "secret response"})
        observed
        (try
          (binding [journal/*journal* j]
            (provider/around
              (join-point :samizdat/model :samizdat.agent.infer/model)
              [{:content "secret prompt"}]
              (fn [] (throw failure))))
          (catch :default error error))
        terminal (last (journal/snapshot j))
        serialized (pr-str (journal/snapshot j))]
    (is (identical? failure observed))
    (is (= :throw (:phase terminal)))
    (is (string? (:exception-type terminal)))
    (doseq [secret ["secret failure" "secret response" "secret prompt"]]
      (is (not (.contains serialized secret))))))

(deftest http-role-is-transparent-and-does-not-expand-the-journal-vocabulary
  (let [j (journal/journal 8)
        calls (atom [])
        expected (Object.)
        args ["http://private.invalid" {:body "secret"}]
        actual
        (binding [journal/*journal* j]
          (provider/around-http-client
            (join-point :http/client :samizdat.llm.client/http-post)
            args
            (fn
              ([] (swap! calls conj args) expected)
              ([replacement] (swap! calls conj replacement) expected))))]
    (is (identical? expected actual))
    (is (= [args] @calls))
    (is (empty? (journal/snapshot j)))))
