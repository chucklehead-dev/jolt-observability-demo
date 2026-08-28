(ns demo.samizdat-aspect-provider
  "Composite Samizdat build-aspect consumer.

  The compiler supplies already-evaluated arguments through explicit aspect
  contracts. Observational roles use :args-v1; the HTTP role uses
  :replace-args-v1 to pass a copied request map with Trace Context. This
  consumer creates duration spans and mirrors semantic nesting into
  demo.aspect-journal. Prompt, response, tool arguments, exception messages,
  endpoints, and hostnames are not inspected unless the application explicitly
  binds a bounded content-capture policy."
  (:require [clojure.string :as str]
            [demo.aspect-journal :as journal]
            [otel.context :as context]
            [otel.propagation :as propagation]
            [otel.sdk :as sdk]
            [otel.trace :as trace]))

(def samizdat-build-id
  "35b01fddd20fa9e6d77678eadc2a2bcc6fb9ac2d")
(def ^:private scope-name "io.github.yogthos/samizdat.auto")

(def max-content-chars
  "Hard ceiling for any captured prompt or response attribute."
  4096)

(def default-content-policy
  "Privacy default: record semantic metadata, never exchange content."
  {:capture? false :max-chars 512 :redact nil})

(def ^:dynamic *content-policy*
  "Dynamically scoped content policy. Jolt futures convey this binding."
  default-content-policy)

(defn content-policy
  "Validate a bounded opt-in content policy.

  `:capture? true` enables prompt/response attributes. `:max-chars` must be
  between 1 and 4096. `:redact` may be a pure string -> string function; it is
  applied before truncation. A redactor failure emits no content."
  [{:keys [capture? max-chars redact] :as opts}]
  (let [unknown (seq (remove #{:capture? :max-chars :redact} (keys opts)))
        max-chars (or max-chars (:max-chars default-content-policy))]
    (when unknown
      (throw (ex-info "unknown Samizdat content policy keys" {:keys unknown})))
    (when-not (or (true? capture?) (false? capture?) (nil? capture?))
      (throw (ex-info ":capture? must be boolean" {:capture? capture?})))
    (when-not (and (integer? max-chars) (pos? max-chars)
                   (<= max-chars max-content-chars))
      (throw (ex-info ":max-chars must be between 1 and 4096"
                      {:max-chars max-chars})))
    (when-not (or (nil? redact) (fn? redact))
      (throw (ex-info ":redact must be a function" {:redact redact})))
    {:capture? (true? capture?) :max-chars max-chars :redact redact}))

(defn- strip-delimited-thinking [value]
  (-> (or value "")
      str
      (str/replace #"(?is)<think\b[^>]*>.*?</think>" "")
      str/trim))

(defn- captured [value]
  (when (:capture? *content-policy*)
    (let [{:keys [max-chars redact]} (content-policy *content-policy*)]
      (try
        (let [sanitized (strip-delimited-thinking value)
              redacted (str ((or redact identity) sanitized))]
          (subs redacted 0 (min max-chars (count redacted))))
        (catch :default _ nil)))))

(defn- enum-name [value]
  (cond
    (keyword? value) (name value)
    (symbol? value) (name value)
    (string? value) value
    :else nil))

(defn- bounded-name [value]
  (when-let [s (enum-name value)]
    (if (> (count s) 128) (subs s 0 128) s)))

(defn- indexed-messages [messages]
  (mapv (fn [index message]
          {:index index
           :message message})
        (range)
        messages))

(defn- capture-order
  "Lead a bounded display with Samizdat's opening user task, then retain the
  remaining messages in wire order. Original one-based indices make the
  presentation order explicit instead of misrepresenting it as the request
  sequence. This prevents a large system prompt from consuming the complete
  capture budget before the user task appears."
  [messages]
  (let [indexed (indexed-messages messages)
        first-user (first (filter #(= "user"
                                       (bounded-name
                                         (get-in % [:message :role])))
                                  indexed))]
    (if first-user
      (into [first-user] (remove #(= (:index first-user) (:index %)) indexed))
      indexed)))

(defn- captured-messages [messages]
  (when (:capture? *content-policy*)
    (let [messages (vec messages)
          total (count messages)]
      (captured
        (str/join "\n\n"
                  (map (fn [{:keys [index message]}]
                         (str "[message " (inc index) "/" total "] "
                              (or (bounded-name (:role message)) "message")
                              ": " (or (:content message) "")))
                       (capture-order messages)))))))

(defn- safe-id [value]
  (when (some? value)
    (let [s (str value)]
      (if (> (count s) 128) (subs s 0 128) s))))

(defn- number-value [value]
  (when (number? value) value))

(defn- present [entries]
  (reduce (fn [attrs [key value]]
            (if (nil? value) attrs (assoc attrs key value)))
          {}
          entries))

(defn- run-attributes [[opts]]
  (let [config (:config opts)
        llm-config (:llm-config opts)
        capture? (:capture? *content-policy*)
        prompt (when capture? (captured (:problem opts)))]
    (present
      [[:gen_ai.operation.name "invoke_agent"]
       [:gen_ai.provider.name (bounded-name (:provider llm-config))]
       [:gen_ai.request.model (bounded-name (:model llm-config))]
       [:samizdat.run.max_turns
        (number-value (or (:max-turns opts) (get-in config [:run :max-turns])))]
       [:samizdat.beam.width
        (number-value (or (:beam-width opts) (get-in config [:run :beam-width])))]
       [:samizdat.prompt.content_state
        (cond (not capture?) "omitted" (nil? prompt) "redaction-failed"
              :else "captured")]
       [:samizdat.response.content_state
        (if capture? "capture-requested" "omitted")]
       [:samizdat.prompt.sanitized prompt]])))

(defn- turn-attributes [[ctx branch turn]]
  (present
    [[:gen_ai.operation.name "agent_turn"]
     [:samizdat.run.id (safe-id (:run-id ctx))]
     [:samizdat.branch.id (safe-id (:id branch))]
     [:samizdat.turn.number (number-value turn)]]))

(defn- model-attributes [[_adapter config messages opts]]
  (let [capture? (:capture? *content-policy*)
        prompt (when capture? (captured-messages messages))]
    (present
      [[:gen_ai.operation.name "chat"]
       [:gen_ai.provider.name (bounded-name (:provider config))]
       [:gen_ai.request.model (bounded-name (:model config))]
       [:gen_ai.request.max_tokens
        (number-value (or (:max-tokens opts) (:max-tokens config)))]
       [:samizdat.prompt.content_state
        (cond (not capture?) "omitted" (nil? prompt) "redaction-failed"
              :else "captured")]
       [:samizdat.response.content_state
        (if capture? "capture-requested" "omitted")]
       [:samizdat.prompt.sanitized prompt]])))

(defn- tool-attributes [[ctx]]
  (present
    [[:gen_ai.operation.name "execute_tool"]
     [:gen_ai.tool.name (bounded-name (:tool-name ctx))]
     [:samizdat.run.id (safe-id (:run-id ctx))]
     [:samizdat.branch.id (safe-id (get-in ctx [:branch :id]))]
     [:samizdat.turn.number (number-value (:turn ctx))]
     [:samizdat.tool.arguments_state "not-captured"]
     [:samizdat.tool.result_state "not-captured"]]))

(defn- initial-attributes [role args]
  (case role
    :samizdat/run (run-attributes args)
    :samizdat/turn (turn-attributes args)
    :samizdat/model (model-attributes args)
    :samizdat/tool (tool-attributes args)
    {}))

(defn- result-attributes [role result]
  (case role
    :samizdat/run
    (let [capture? (:capture? *content-policy*)
          response (when capture? (captured (:answer result)))]
      (present [[:samizdat.run.id (safe-id (:run-id result))]
                [:samizdat.run.status (bounded-name (:status result))]
                [:samizdat.run.branch_count
                 (when (sequential? (:branches result)) (count (:branches result)))]
                [:samizdat.response.content_state
                 (cond (not capture?) "omitted" (nil? response) "redaction-failed"
                       :else "captured")]
                [:samizdat.response.sanitized response]]))

    :samizdat/turn
    (present [[:samizdat.branch.status (bounded-name (:status result))]
              [:samizdat.branch.timeout_count (number-value (:timeouts result))]])

    :samizdat/model
    (let [usage (:usage result)
          capture? (:capture? *content-policy*)
          response (when capture? (captured (:content result)))]
      (present [[:gen_ai.response.finish_reasons
                 (bounded-name (:finish-reason result))]
                [:gen_ai.usage.input_tokens
                 (number-value (:prompt-tokens usage))]
                [:gen_ai.usage.output_tokens
                 (number-value (:completion-tokens usage))]
                [:samizdat.model.elapsed_ms (number-value (:elapsed-ms result))]
                [:samizdat.response.content_state
                 (cond (not capture?) "omitted" (nil? response) "redaction-failed"
                       :else "captured")]
                [:samizdat.response.sanitized response]]))

    :samizdat/tool
    (present [[:samizdat.tool.category (bounded-name (:category result))]
              [:samizdat.tool.progress (when (boolean? (:progress? result))
                                         (:progress? result))]
              [:samizdat.tool.timeout (when (boolean? (:timeout? result))
                                        (:timeout? result))]])
    {}))

(defn- span-name [role]
  (case role
    :samizdat/run "samizdat.run"
    :samizdat/turn "samizdat.turn"
    :samizdat/model "samizdat.model"
    :samizdat/tool "samizdat.tool"
    "samizdat.operation"))

(defn- traced [join-point evaluated-args proceed]
  (let [role (:advice-role join-point)
        tracer (sdk/tracer scope-name {:version samizdat-build-id})
        span (trace/start-span tracer (span-name role)
                               {:kind :internal
                                :attributes (initial-attributes role evaluated-args)})]
    (try
      (trace/with-current-span span
        (try
          (let [result (proceed)]
            (trace/set-attributes! span (result-attributes role result))
            result)
          (catch :default error
            ;; Error bodies and exception messages can contain model content.
            ;; A fixed status plus the exception type is sufficient and safe.
            (trace/add-event! span "exception"
                              {:exception.type (.getName (class error))
                               :exception.escaped true})
            (trace/set-status! span :error "samizdat operation failed")
            (throw error))))
      (finally
        (trace/end! span)))))

(defn around
  "Apply both observational sinks around one synchronous Samizdat operation."
  [join-point evaluated-args proceed]
  (journal/around join-point
                  (fn [] (traced join-point evaluated-args proceed))))

(def ^:private trace-context-header-names
  #{"traceparent" "tracestate"})

(defn- without-stale-trace-context [headers]
  (reduce-kv (fn [result key value]
               (if (contains? trace-context-header-names
                              (cond
                                (string? key) (str/lower-case key)
                                (keyword? key) (str/lower-case (name key))
                                :else nil))
                 result
                 (assoc result key value)))
             {}
             (or headers {})))

(defn around-http-client
  "Instrument Samizdat's maintained http-client call without changing that
  library. The compiler supplies the already-evaluated `[url request]` pair.
  Advice deliberately replaces the original call so it can pass a copy of the
  request carrying the client span's W3C Trace Context. It neither records nor
  derives the physical endpoint, request body, response body, or credentials."
  [_join-point [url request] proceed]
  (let [tracer (sdk/tracer scope-name {:version samizdat-build-id})
        span (trace/start-span tracer "HTTP POST"
                               {:kind :client
                                :attributes
                                {:http.request.method "POST"}})]
    (try
      (trace/with-current-span span
        (let [headers (->> (:headers request)
                           without-stale-trace-context
                           (propagation/inject-current
                            propagation/trace-context))
              ;; This specialized boundary omits the physical model endpoint.
              ;; Suppress the generic lower-level HTTP-client aspect while
              ;; preserving this span's explicitly injected Trace Context.
              result (context/with-instrumentation-suppressed
                       (proceed [url (assoc request :headers headers)]))
              status (:status result)]
          (when (number? status)
            (trace/set-attribute! span :http.response.status_code status)
            (when (>= status 400)
              (trace/set-status! span :error "HTTP request failed")))
          result))
      (catch :default error
        ;; The URL, response, and exception message may contain private model
        ;; endpoint or content details. Keep the failure signal generic.
        (trace/add-event! span "exception"
                          {:exception.type (.getName (class error))
                           :exception.escaped true})
        (trace/set-status! span :error "HTTP request failed")
        (throw error))
      (finally
        (trace/end! span)))))

(def aspect-provider
  {:schema 1
   :libraries {'yogthos/samizdat samizdat-build-id}
   :roles {:samizdat/run {:fn 'demo.samizdat-aspect-provider/around
                          :contract :args-v1}
           :samizdat/turn {:fn 'demo.samizdat-aspect-provider/around
                           :contract :args-v1}
           :samizdat/model {:fn 'demo.samizdat-aspect-provider/around
                            :contract :args-v1}
           :samizdat/tool {:fn 'demo.samizdat-aspect-provider/around
                           :contract :args-v1}
           :http/client {:fn 'demo.samizdat-aspect-provider/around-http-client
                         :contract :replace-args-v1}}})
