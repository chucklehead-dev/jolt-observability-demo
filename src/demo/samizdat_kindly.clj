(ns demo.samizdat-kindly
  (:require [clojure.string :as str]))

(def ^:private hidden-content-attributes
  ["gen_ai.operation.name" "gen_ai.provider.name"
   "gen_ai.request.model" "gen_ai.response.model"
   "gen_ai.request.max_tokens" "gen_ai.request.temperature"
   "gen_ai.request.stream" "gen_ai.request.reasoning_effort"
   "gen_ai.response.finish_reasons"
   "gen_ai.usage.input_tokens" "gen_ai.usage.output_tokens"
   "gen_ai.usage.total_tokens" "gen_ai.input.messages"
   "gen_ai.output.messages" "server.address" "samizdat.turn.number"
   "samizdat.prompt.sanitized" "samizdat.response.sanitized"
   "samizdat.prompt.content_state" "samizdat.response.content_state"])

(defn- kind-value [value kind options]
  ;; Mirrors scicloj.kindly.v4.api/attach-meta-to-value, including the scalar
  ;; wrapping contract, without making Kindly a runtime dependency.
  (let [metadata {:kindly/kind kind :kindly/options options}]
    (if (instance? clojure.lang.IObj value)
      (with-meta value metadata)
      (with-meta [value]
        (assoc metadata :kindly/options
               (assoc (or options {}) :wrapped-value true))))))

(defn- attribute-name [attribute]
  (-> (str attribute) (str/replace #"^:" "") str/lower-case))

(defn- attribute [attributes name]
  (some (fn [[key value]]
          (when (= name (attribute-name key)) value))
        attributes))

(defn- code [label value]
  (when-not (str/blank? (str value))
    (kind-value value :kind/code {:otel.viewer/label label})))

(defn- table [row]
  (kind-value [row] :kind/table nil))

(defn- note [role tone open? label items hidden]
  {:value
   (kind-value (vec (remove nil? items)) :kind/fragment
               {:otel.viewer/role role
                :otel.viewer/tone tone
                :otel.viewer/open? open?
                :otel.viewer/label label
                :otel.viewer/hide-attributes hidden})})

(defn- generation-note [attributes]
  (let [prompt-captured? (= "captured"
                            (some-> (attribute attributes
                                               "samizdat.prompt.content_state")
                                    str str/lower-case))
        response-captured? (= "captured"
                              (some-> (attribute attributes
                                                 "samizdat.response.content_state")
                                      str str/lower-case))
        row (cond-> (array-map)
              (attribute attributes "gen_ai.provider.name")
              (assoc :Provider (attribute attributes "gen_ai.provider.name"))
              (or (attribute attributes "gen_ai.response.model")
                  (attribute attributes "gen_ai.request.model"))
              (assoc :Model (or (attribute attributes "gen_ai.response.model")
                                (attribute attributes "gen_ai.request.model")))
              (attribute attributes "gen_ai.usage.input_tokens")
              (assoc :Input-tokens
                     (attribute attributes "gen_ai.usage.input_tokens"))
              (attribute attributes "gen_ai.usage.output_tokens")
              (assoc :Output-tokens
                     (attribute attributes "gen_ai.usage.output_tokens"))
              (attribute attributes "gen_ai.response.finish_reasons")
              (assoc :Finish-reason
                     (attribute attributes "gen_ai.response.finish_reasons")))
        content-items
        (cond-> []
          prompt-captured?
          (conj (code "Captured prompt"
                      (attribute attributes "samizdat.prompt.sanitized")))
          response-captured?
          (conj (code "Captured response"
                      (attribute attributes "samizdat.response.sanitized"))))
        content-items (if (seq (remove nil? content-items))
                        content-items
                        [(kind-value "Content not recorded (privacy default)"
                                     :kind/println nil)])]
    (note "Generation" :accent false "Generation observation"
          (into [(table row)] content-items)
          hidden-content-attributes)))

(defn- intervention-note [attributes]
  (note "Intervention" :warning true "Controller intervention"
        [(table
          (array-map
           :Action (attribute attributes "samizdat.intervention.action")
           :Source (attribute attributes "samizdat.intervention.source")
           :Reason (attribute attributes "samizdat.intervention.reason")))]
        ["samizdat.intervention.action" "samizdat.intervention.source"
         "samizdat.intervention.reason"]))

(defn- role-note [role tone]
  (note role tone false (str role " span") [] []))

(defn advise-span
  "Attach a Kindly note to a Samizdat span without changing stored OTel data."
  [span]
  (let [attributes (:attributes span)
        operation (some-> (attribute attributes "gen_ai.operation.name")
                          str str/lower-case)
        advice
        (cond
          (or (= operation "execute_tool")
              (attribute attributes "samizdat.tool.name"))
          (role-note "Tool" :tool)

          (contains? #{"chat" "text_completion" "generate_content"} operation)
          (generation-note attributes)

          (attribute attributes "samizdat.intervention.action")
          (intervention-note attributes)

          (= "advance-branch"
             (attribute attributes "samizdat.control.phase"))
          (role-note "Turn" nil)
          (attribute attributes "samizdat.branch.id") (role-note "Branch" nil)
          (attribute attributes "samizdat.control.driver") (role-note "Control" nil)
          (or (= operation "invoke_agent")
              (attribute attributes "samizdat.run.id"))
          (role-note "Agent" nil)

          :else nil)]
    (cond-> span advice (assoc :kindly advice))))

(defn advise-trace
  "Enrich a host-shaped trace tree at the rendering boundary."
  [trace]
  (letfn [(walk [span]
            (-> span
                advise-span
                (update :children #(mapv walk (or % [])))))]
    (update trace :spanTree #(mapv walk (or % [])))))
