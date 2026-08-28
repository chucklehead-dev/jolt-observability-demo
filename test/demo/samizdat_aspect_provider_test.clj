(ns demo.samizdat-aspect-provider-test
  (:require [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.test :refer [deftest is]]
            [demo.aspect-journal :as journal]
            [demo.samizdat-aspect-provider :as provider]
            [demo.samizdat-journal-provider :as journal-provider]
            [otel.exporter.memory :as memory]
            [otel.instrumentation.http-client :as generic-http]
            [otel.propagation :as propagation]
            [otel.sdk :as sdk]
            [otel.trace :as trace]))

(defn- join-point [role id]
  {:id id
   :advice-role role
   :library {:id 'yogthos/samizdat :version provider/samizdat-build-id}})

(defn- observed-around [join-point evaluated-args proceed]
  (journal-provider/around
    join-point evaluated-args
    (fn [] (provider/around join-point evaluated-args proceed))))

(defn- with-memory-sdk [f]
  (let [exporter (memory/exporter)
        handle (sdk/init! {:service-name "samizdat-aspect-test"
                           :exporter exporter
                           :processor :simple
                           :metrics? false})]
    (try
      (f exporter)
      (finally (sdk/shutdown! handle)))))

(deftest independent-consumers-share-otel-and-journal-parentage
  (with-memory-sdk
    (fn [exporter]
      (let [j (journal/journal 32)
            run-jp (join-point :samizdat/run :samizdat.api.control/beam-run)
            turn-jp (join-point :samizdat/turn :samizdat.agent.beam/turn)
            model-jp (join-point :samizdat/model :samizdat.agent.infer/model)
            result (binding [journal/*journal* j]
                     (observed-around
                       run-jp
                       [{:problem "never persist this prompt"
                         :llm-config {:provider :openai
                                      :model "test-model"
                                      :api-key "never persist this key"}}]
                       (fn []
                         (observed-around
                           turn-jp [{:run-id "run-1"} {:id "branch-1"} 2]
                           (fn []
                             (observed-around
                               model-jp
                               [:adapter {:provider :openai :model "test-model"}
                                [{:role "user" :content "secret prompt"}]
                                {:max-tokens 64}]
                               (fn [] {:content "secret response"
                                       :finish-reason "stop"
                                       :usage {:prompt-tokens 7
                                               :completion-tokens 3}}))
                             {:id "branch-1" :status :active}))
                         {:run-id "run-1" :status :done :branches [{}]})))]
        (is (= {:run-id "run-1" :status :done :branches [{}]} result))
        (let [spans (memory/spans exporter)
              by-name (into {} (map (juxt :name identity) spans))
              run (get by-name "samizdat.run")
              turn (get by-name "samizdat.turn")
              model (get by-name "samizdat.model")]
          (is (= 3 (count spans)))
          (is (= (get-in run [:span-context :span-id]) (:parent-span-id turn)))
          (is (= (get-in turn [:span-context :span-id]) (:parent-span-id model)))
          (is (= 7 (get (:attributes model) "gen_ai.usage.input_tokens")))
          (is (= "omitted"
                 (get (:attributes model) "samizdat.prompt.content_state")))
          (is (nil? (get (:attributes model) "samizdat.prompt.sanitized")))
          (is (nil? (get (:attributes model) "samizdat.response.sanitized"))))
        (let [events (journal/snapshot j)
              enters (filter #(= :enter (:phase %)) events)
              [run turn model] enters]
          (is (= 6 (count events)))
          (is (nil? (:parent-operation-id run)))
          (is (= (:operation-id run) (:parent-operation-id turn)))
          (is (= (:operation-id turn) (:parent-operation-id model))))
        (let [serialized (pr-str {:spans (memory/spans exporter)
                                  :journal (journal/snapshot j)})]
          (doseq [secret ["never persist this prompt" "never persist this key"
                          "secret prompt" "secret response"]]
            (is (not (.contains serialized secret)))))))))

(deftest application-exception-is-identical-and-message-is-redacted
  (with-memory-sdk
    (fn [exporter]
      (let [j (journal/journal 8)
            failure (ex-info "secret response leaked in provider error" {:body "secret"})
            observed (try
                       (binding [journal/*journal* j]
                         (observed-around
                           (join-point :samizdat/model :samizdat.agent.infer/model)
                           [:adapter {:model "test-model"} [{:content "secret prompt"}] {}]
                           (fn [] (throw failure))))
                       (catch :default error error))
            span (first (memory/spans exporter))
            serialized (pr-str {:span span :journal (journal/snapshot j)})]
        (is (identical? failure observed))
        (is (= :error (get-in span [:status :code])))
        (is (= "samizdat operation failed" (get-in span [:status :description])))
        (is (not (.contains serialized "secret response leaked")))
        (is (not (.contains serialized "secret prompt")))
        (is (= :throw (:phase (last (journal/snapshot j)))))))))

(deftest future-created-inside-run-preserves-run-parent
  (with-memory-sdk
    (fn [exporter]
      (let [j (journal/journal 16)
            tracer (sdk/tracer "samizdat-aspect-test.request")
            result
            (binding [journal/*journal* j]
              (trace/with-span [_request tracer "request"]
                @(future
                   (observed-around
                     (join-point :samizdat/run :samizdat.api.control/beam-run)
                     [{:llm-config {:provider :openai :model "test-model"}}]
                     (fn []
                       @(future
                          (observed-around
                            (join-point :samizdat/turn :samizdat.agent.beam/turn)
                            [{:run-id "run-future"} {:id "branch-future"} 1]
                            (fn [] {:id "branch-future" :status :active}))))))))
            spans (memory/spans exporter)
            request (first (filter #(= "request" (:name %)) spans))
            run (first (filter #(= "samizdat.run" (:name %)) spans))
            turn (first (filter #(= "samizdat.turn" (:name %)) spans))
            enters (filter #(= :enter (:phase %)) (journal/snapshot j))
            run-enter (first enters)
            turn-enter (second enters)]
        (is (= {:id "branch-future" :status :active} result))
        (is (= (get-in request [:span-context :span-id]) (:parent-span-id run)))
        (is (= (get-in run [:span-context :trace-id])
               (get-in turn [:span-context :trace-id])))
        (is (= (get-in run [:span-context :span-id]) (:parent-span-id turn)))
        (is (= (:operation-id run-enter) (:parent-operation-id turn-enter)))))))

(deftest tool-span-keeps-only-whitelisted-envelope-metadata
  (with-memory-sdk
    (fn [exporter]
      (let [result (provider/around
                     (join-point :samizdat/tool :samizdat.agent.loop/tool)
                     [{:run-id "run-2" :turn 3 :tool-name "read_file"
                       :branch {:id "branch-2"}
                       :args {:path "/secret/project" :claim "secret claim"}}]
                     (fn [] {:category :evidence :progress? true
                             :result "secret tool result"}))
            span (first (memory/spans exporter))
            attrs (:attributes span)
            serialized (pr-str span)]
        (is (= :evidence (:category result)))
        (is (= "read_file" (get attrs "gen_ai.tool.name")))
        (is (= "omitted" (get attrs "samizdat.tool.arguments_state")))
        (is (= "omitted" (get attrs "samizdat.tool.result_state")))
        (is (= true (get attrs "samizdat.tool.progress")))
        (doseq [secret ["/secret/project" "secret claim" "secret tool result"]]
          (is (not (.contains serialized secret))))))))

(deftest tool-details-use-the-shared-bounded-content-policy
  (with-memory-sdk
    (fn [exporter]
      (binding [provider/*content-policy*
                (provider/content-policy
                 {:capture? true :max-chars 64
                  :redact #(str/replace % "/private/root" "[root]")})]
        (provider/around
         (join-point :samizdat/tool :samizdat.agent.loop/tool)
         [{:run-id "run-tool" :turn 2 :tool-name "read_file"
           :branch {:id "branch-tool"}
           :args {:path "/private/root/src/calc.clj"}}]
         (fn [] {:category :evidence :progress? true :timeout? false
                 :result "contents from /private/root/src/calc.clj"})))
      (let [attrs (:attributes (first (memory/spans exporter)))]
        (is (= "captured" (get attrs "samizdat.tool.arguments_state")))
        (is (= "captured" (get attrs "samizdat.tool.result_state")))
        (is (.contains (get attrs "samizdat.tool.arguments_sanitized") "[root]"))
        (is (.contains (get attrs "samizdat.tool.result_sanitized") "[root]"))
        (is (<= (count (get attrs "samizdat.tool.arguments_sanitized")) 64))
        (is (<= (count (get attrs "samizdat.tool.result_sanitized")) 64))
        (is (= false (get attrs "samizdat.tool.timeout")))))))

(deftest content-capture-is-explicit-sanitized-and-bounded
  (with-memory-sdk
    (fn [exporter]
      (binding [provider/*content-policy*
                (provider/content-policy
                  {:capture? true :max-chars 48
                   :redact #(str/replace % "private-model-host" "[host]")})]
        (provider/around
          (join-point :samizdat/model :samizdat.agent.infer/model)
          [:adapter {:provider :openai :model "test-model"}
           [{:role "user"
             :content "ask private-model-host <think>private reasoning</think> for a long answer"}]
           {}]
          (fn [] {:content "private-model-host says <think>secret thought</think> forty-two and more"
                  :finish-reason "stop"})))
      (let [attrs (:attributes (first (memory/spans exporter)))
            prompt (get attrs "samizdat.prompt.sanitized")
            response (get attrs "samizdat.response.sanitized")
            serialized (pr-str attrs)]
        (is (= "captured" (get attrs "samizdat.prompt.content_state")))
        (is (= "captured" (get attrs "samizdat.response.content_state")))
        (is (<= (count prompt) 48))
        (is (<= (count response) 48))
        (is (.contains prompt "[host]"))
        (is (.contains response "[host]"))
        (is (not (.contains serialized "private reasoning")))
        (is (not (.contains serialized "secret thought")))
        (is (not (.contains serialized "private-model-host")))))))

(deftest bounded-model-capture-keeps-the-opening-user-task-visible
  (with-memory-sdk
    (fn [exporter]
      (binding [provider/*content-policy*
                (provider/content-policy
                  {:capture? true :max-chars 160 :redact identity})]
        (provider/around
          (join-point :samizdat/model :samizdat.agent.infer/model)
          [:adapter {:provider :local :model "fixture"}
           [{:role "system" :content (apply str (repeat 512 "s"))}
            {:role "user"
             :content "Repair calc.square; proof nonce user-task-7f31c92b."}
            {:role "assistant" :content "I will inspect the source."}]
           {}]
          (fn [] {:content "done" :finish-reason "stop"})))
      (let [attrs (:attributes (first (memory/spans exporter)))
            prompt (get attrs "samizdat.prompt.sanitized")]
        (is (<= (count prompt) 160))
        (is (.contains prompt "[message 2/3] user:"))
        (is (.contains prompt "user-task-7f31c92b"))
        (is (.contains prompt "[message 1/3] system:"))))))

(deftest content-policy-is-bounded-and-fails-closed
  (is (= provider/default-content-policy
         (provider/content-policy {})))
  (doseq [invalid [{:capture? :yes}
                   {:capture? true :max-chars 0}
                   {:capture? true :max-chars 4097}
                   {:capture? true :unknown true}]]
    (is (thrown? Exception (provider/content-policy invalid)))))

(deftest redactor-failure-drops-content-without-changing-the-call
  (with-memory-sdk
    (fn [exporter]
      (let [expected {:content "response"}
            actual
            (binding [provider/*content-policy*
                      (provider/content-policy
                        {:capture? true :max-chars 32
                         :redact (fn [_] (throw (Exception. "redactor failed")))})]
              (provider/around
                (join-point :samizdat/model :samizdat.agent.infer/model)
                [:adapter {:model "test-model"}
                 [{:role "user" :content "prompt"}] {}]
                (fn [] expected)))
            attrs (:attributes (first (memory/spans exporter)))]
        (is (identical? expected actual))
        (is (= "redaction-failed"
               (get attrs "samizdat.prompt.content_state")))
        (is (= "redaction-failed"
               (get attrs "samizdat.response.content_state")))
        (is (nil? (get attrs "samizdat.prompt.sanitized")))
        (is (nil? (get attrs "samizdat.response.sanitized")))
        (is (not (.contains (pr-str attrs) "redactor failed")))))))

(deftest provider-declares-args-contract-for-every-role
  (is (= {'yogthos/samizdat provider/samizdat-build-id}
         (:libraries provider/aspect-provider)))
  (doseq [[_ role] (:roles provider/aspect-provider)]
    (is (contains? #{:args-v1 :replace-args-v1} (:contract role))))
  (is (= :replace-args-v1
         (get-in provider/aspect-provider [:roles :http/client :contract])))
  (is (= 'demo.samizdat-aspect-provider/around-http-client
         (get-in provider/aspect-provider [:roles :http/client :fn])))
  (doseq [role [:samizdat/run :samizdat/turn :samizdat/model :samizdat/tool]]
    (is (= 'demo.samizdat-aspect-provider/around
           (get-in provider/aspect-provider [:roles role :fn])))))

(deftest outbound-http-advice-injects-its-client-context-without-content
  (with-memory-sdk
    (fn [exporter]
      (let [observed (atom nil)
            response {:status 200 :body "private model response"}
            model-jp (join-point :samizdat/model :samizdat.agent.infer/model)
            http-jp (join-point :http/client :samizdat.llm.client/http-post)
            actual
            (provider/around
              model-jp
              [:adapter {:provider :local :model "fixture"}
               [{:role "user" :content "private model prompt"}] {}]
              (fn []
                (provider/around-http-client
                  http-jp
                  ["http://private-model.invalid/v1/chat/completions"
                   {:headers {"Authorization" "private credential"
                              "TraceParent" "stale-string"
                              :TraceState "stale-keyword-state"
                              :traceparent "stale-keyword-parent"}
                    :body "private request body"}]
                  (fn [[url request]]
                    (let [suppressed? (atom nil)
                          result
                          (generic-http/around
                           {:id :http-client.core/request
                            :advice-role :http/client}
                           [{:request-method :post}]
                           (fn
                             ([] (reset! suppressed? true) response)
                             ([_] (reset! suppressed? false) response)))]
                      (reset! observed {:url url :request request
                                        :suppressed? @suppressed?})
                      result)))))
            spans (memory/spans exporter)
            model (first (filter #(= "samizdat.model" (:name %)) spans))
            client (first (filter #(= "HTTP POST" (:name %)) spans))
            traceparent (get-in @observed [:request :headers "traceparent"])
            serialized (pr-str spans)]
        (is (identical? response actual))
        (is (= :client (:kind client)))
        (is (true? (:suppressed? @observed))
            "the privacy-specialized boundary suppresses generic HTTP advice")
        (is (= (get-in model [:span-context :span-id])
               (:parent-span-id client)))
        (is (= (get-in model [:span-context :trace-id])
               (get-in client [:span-context :trace-id])))
        (is (= traceparent
               (propagation/format-traceparent (:span-context client))))
        (is (nil? (get-in @observed [:request :headers "TraceParent"])))
        (is (nil? (get-in @observed [:request :headers :TraceState])))
        (is (nil? (get-in @observed [:request :headers :traceparent])))
        (is (= "private credential"
               (get-in @observed [:request :headers "Authorization"])))
        (doseq [secret ["private-model.invalid" "private credential"
                        "private request body" "private model response"]]
          (is (not (.contains serialized secret))))))))

(deftest library-supplied-manifest-identifies-the-run-entry
  (let [manifest (-> "META-INF/jolt/aspects/samizdat-m2-embed.edn"
                     io/resource slurp edn/read-string)
        embed (first (filter #(= :samizdat.embed/beam-run (:id %))
                             (:aspects manifest)))]
    (is (= provider/samizdat-build-id (get-in manifest [:library :version])))
    (is (= {:entry 'samizdat.agent.beam/run! :arity 1}
           (:match embed)))
    (is (= {:matches 1} (:expect embed)))))

(deftest core-manifest-selects-one-maintained-http-client-call
  (let [manifest (-> "META-INF/jolt/aspects/samizdat-m2-core.edn"
                     io/resource slurp edn/read-string)
        http (first (filter #(= :samizdat.llm.client/http-post (:id %))
                            (:aspects manifest)))]
    (is (= {:ns 'samizdat.llm.client
            :call 'jolt.http-client/post
            :arity 2}
           (:match http)))
    (is (= :http/client (:advice-role http)))
    (is (= {:matches 1} (:expect http)))
    (is (= 4 (count (:aspects manifest))))))
