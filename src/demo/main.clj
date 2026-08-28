(ns demo.main
  (:require [clojure.data.json :as json]
            [clojure.string :as str]
            [db.jdbc]
            [demo.datastar :as demo-datastar]
            [demo.otlp :as demo-otlp]
            [demo.plotje-portable-editor :as plotje-editor]
            [demo.safe-hiccup-editor :as hiccup-editor]
            [demo.samizdat-kindly :as samizdat-kindly]
            [demo.workbench :as workbench]
            [demo.workbench-fixture :as workbench-fixture]
            [jdbc.chdb]
            [jdbc.core :as jdbc]
            [jolt.http-client :as http-client]
            [jolt.http.server :as http-server]
            [otel.context :as context]
            [otel.exporter.chdb :as chdb-export]
            [otel.exporter.chdb.explorer :as explorer]
            [otel.logs :as logs]
            [otel.otlp.http-receiver :as otlp-receiver]
            [otel.propagation :as propagation]
            [otel.sdk :as sdk]
            [otel.trace :as trace]
            [otel.viewer :as viewer]
            [oscope.live :as oscope]
            [oscope.sample :as oscope-sample]
            [oscope.ui.web :as oscope-web])
  (:import [java.net URLDecoder]))

(def ^:private service-name "jolt-observability-demo")
(def ^:private default-lemonade-base-url "http://127.0.0.1:8000/v1")
(def ^:private default-lemonade-model "local-model")
(def ^:private initial-agent-prompt
  (str "You are an absurdly overqualified coding agent with a brain the size "
       "of a planet, assigned to repair a Jolt Ring telemetry viewer. A "
       "browser reconnect can miss live SSE updates when a notification "
       "arrives between reading the durable sequence cursor and registering "
       "its waiter. Propose a minimal patch and a regression test. Be "
       "concrete and concise."))
(def ^:private controller-revision
  (str "Controller intervention: review your first answer against the user's "
       "exact request. Identify the most important correctness risk or "
       "missing verification step, then return a revised answer that fixes "
       "it. Preserve the original task and be concrete."))
(def ^:private max-captured-response-length 2000)
(def ^:private json-headers {"Content-Type" "application/json; charset=UTF-8"
                             "Cache-Control" "no-store"})

(def ^:private html-headers
  {"Content-Type" "text/html; charset=UTF-8"
   "Cache-Control" "no-store"
   "Content-Security-Policy"
   "default-src 'none'; style-src 'unsafe-inline'; script-src 'self'; connect-src 'self'; form-action 'self'; base-uri 'none'"
   "X-Content-Type-Options" "nosniff"})

(def ^:private javascript-headers
  {"Content-Type" "text/javascript; charset=UTF-8"
   "Cache-Control" "no-cache"
   "X-Content-Type-Options" "nosniff"})

(defn- json-response
  ([value] (json-response 200 value))
  ([status value] {:status status :headers json-headers :body (json/write-str value)}))

(defn- error-response [status message]
  (json-response status {:error message}))

(defn- html-response [body]
  {:status 200 :headers html-headers :body body})

;; chDB normalizes result labels to lower case, including camel-case aliases.
(defn- value-of [row k]
  (get row (keyword (str/lower-case (name k)))))

(def ^:private max-span-events 64)
(def ^:private max-span-events-json-bytes 65536)

(defn- span-events [row]
  (let [raw (value-of row :EventsJSON)]
    (if (and (string? raw) (<= (count raw) max-span-events-json-bytes))
      (try
        (let [events (json/read-str raw :key-fn keyword)]
          (if (vector? events) (vec (take max-span-events events)) []))
        (catch Throwable _ []))
      [])))

(defn query-summary [conn]
  (let [spans (first (jdbc/fetch conn
                       "SELECT uniqExactIf(TraceId, TraceId != '') AS traceCount,
                               count() AS spanCount,
                               countIf(lower(StatusCode) = 'error') AS errorCount
                          FROM otel_traces"))
        log-count (value-of (first (jdbc/fetch conn "SELECT count() AS logCount FROM otel_logs")) :logCount)]
    {:traceCount (or (value-of spans :traceCount) 0)
     :spanCount (or (value-of spans :spanCount) 0)
     :logCount (or log-count 0)
     :errorCount (or (value-of spans :errorCount) 0)}))

(def ^:private trace-window-nanos
  {"15m" (* 15 60 1000000000)
   "1h" (* 60 60 1000000000)
   "24h" explorer/max-time-range-nanos})

(def ^:private trace-window-options
  [{:value "15m" :label "Last 15 minutes"}
   {:value "1h" :label "Last hour"}
   {:value "24h" :label "Last 24 hours"}])

(def ^:private max-query-string-length 4096)
(def ^:private max-query-value-length 200)
(def ^:private duration-pattern #"[0-9]+(?:\.[0-9]+)?")

(defn- bounded-query-value [value maximum]
  (let [value (str/trim (or value ""))]
    (subs value 0 (min maximum (count value)))))

(defn parse-query-params
  "Decode a bounded application/x-www-form-urlencoded query string. Only the
  first value for each key is retained; malformed or oversized input fails to
  an empty map rather than reaching a database callback."
  [query-string]
  (if (> (count (or query-string "")) max-query-string-length)
    {}
    (try
      (reduce
       (fn [params pair]
         (let [i (str/index-of pair "=")
               raw-key (if i (subs pair 0 i) pair)
               raw-value (if i (subs pair (inc i)) "")
               key (URLDecoder/decode raw-key "UTF-8")
               value (URLDecoder/decode raw-value "UTF-8")]
           (if (contains? params key)
             params
             (assoc params key (bounded-query-value value max-query-value-length)))))
       {}
       (remove str/blank? (str/split (or query-string "") #"&")))
      (catch Throwable _ {}))))

(defn trace-filter-selection
  "Return the bounded, allowlisted trace-filter selection from a Ring query."
  [query-string]
  (let [params (parse-query-params query-string)
        service (bounded-query-value (get params "service") 100)
        operation (bounded-query-value (get params "operation") 200)
        status (get params "status" "")
        duration (bounded-query-value (get params "min-duration-ms") 32)
        window (get params "window" "")]
    {:service service
     :operation operation
     :status (if (contains? #{"ok" "error"} status) status "")
     :min-duration-ms (if (re-matches duration-pattern duration) duration "")
     :window (if (contains? trace-window-nanos window) window "")}))

(defn- duration-nanos [text]
  (when-not (str/blank? text)
    (let [milliseconds (Double/parseDouble text)]
      (when (and (= milliseconds milliseconds)
                 (<= 0.0 milliseconds 9000000000000.0))
        (long (* milliseconds 1000000.0))))))

(defn- trace-query [selection now-unix-nano]
  (let [{:keys [service operation status min-duration-ms window]} selection
        minimum-duration (duration-nanos min-duration-ms)
        start (when-let [width (get trace-window-nanos window)]
                (max 0 (- now-unix-nano width)))
        where (cond-> ["TraceId != ''"]
                start (conj "Timestamp >= fromUnixTimestamp64Nano(?)"))
        where-params (cond-> [] start (conj start))
        having (cond-> []
                 (not (str/blank? service))
                 (conj "countIf(ServiceName = ?) > 0")
                 (not (str/blank? operation))
                 (conj "countIf(positionCaseInsensitiveUTF8(SpanName, ?) > 0) > 0")
                 (= status "error")
                 (conj "countIf(lower(StatusCode) = 'error') > 0")
                 (= status "ok")
                 (conj "countIf(lower(StatusCode) = 'error') = 0")
                 minimum-duration
                 (conj "max(Duration) >= ?"))
        having-params (cond-> []
                        (not (str/blank? service)) (conj service)
                        (not (str/blank? operation)) (conj operation)
                        minimum-duration (conj minimum-duration))
        sql (str "SELECT TraceId AS traceId, min(Timestamp) AS startedAt,
                  max(Duration) AS durationNs,
                  argMin(ServiceName, Timestamp) AS service,
                  argMin(SpanName, Timestamp) AS rootSpan,
                  count() AS spanCount,
                  if(countIf(lower(StatusCode) = 'error') > 0, 'error', 'ok') AS status
             FROM otel_traces WHERE " (str/join " AND " where) " GROUP BY TraceId"
                 (when (seq having) (str " HAVING " (str/join " AND " having)))
                 " ORDER BY startedAt DESC LIMIT 100")]
    (into [sql] (concat where-params having-params))))

(defn query-traces
  ([conn] (query-traces conn {} (* (System/currentTimeMillis) 1000000)))
  ([conn selection] (query-traces conn selection (* (System/currentTimeMillis) 1000000)))
  ([conn selection now-unix-nano]
   (mapv (fn [row]
           {:traceId (value-of row :traceId)
            :startedAt (value-of row :startedAt)
            :durationNs (value-of row :durationNs)
            :service (value-of row :service)
            :rootSpan (value-of row :rootSpan)
            :spanCount (value-of row :spanCount)
            :status (value-of row :status)})
         (jdbc/fetch conn (trace-query selection now-unix-nano)))))

(defn query-trace-filter-options [conn selection now-unix-nano]
  (let [start (max 0 (- now-unix-nano explorer/max-time-range-nanos))
        rows (explorer/top-values
              conn {:signal :spans :fields [:service-name]
                    :start-unix-nano start :end-unix-nano now-unix-nano
                    :limit 49})
        discovered (mapv (fn [{:keys [value count]}]
                           {:value value :label (str value " (" count ")")})
                         rows)
        selected (:service selection)
        services (if (or (str/blank? selected)
                         (some #(= selected (:value %)) discovered))
                   discovered
                   (into [{:value selected :label selected}] discovered))]
    {:selected selection
     :service-options services
     :status-options [{:value "ok" :label "OK"}
                      {:value "error" :label "Error"}]
     :window-options trace-window-options}))

(defn- span-json [row]
  {:timestamp (value-of row :Timestamp) :traceId (value-of row :TraceId)
   :timestampUnixNano (value-of row :TimestampUnixNano)
   :spanId (value-of row :SpanId) :parentSpanId (value-of row :ParentSpanId)
   :service (value-of row :ServiceName) :name (value-of row :SpanName)
   ;; ClickStack stores pdata enum strings in title case (Client, Error, ...).
   ;; Keep the demo/viewer API's existing lower-case presentation contract.
   :kind (some-> (value-of row :SpanKind) str/lower-case)
   :durationNs (value-of row :Duration)
   :status (some-> (value-of row :StatusCode) str/lower-case)
   :statusMessage (value-of row :StatusMessage)
   :attributes (value-of row :SpanAttributes)
   :events (span-events row)})

(defn- log-json [row]
  {:timestamp (value-of row :Timestamp) :severity (value-of row :SeverityText)
   :service (value-of row :ServiceName) :body (value-of row :Body)
   :traceId (value-of row :TraceId) :spanId (value-of row :SpanId)
   :attributes (value-of row :LogAttributes)})

(defn span-tree
  "Build a deterministic forest from eager trace-detail spans. Missing parents
  become roots; duplicate/cyclic references are bounded and never recurse
  forever. Each returned span gains a :children vector."
  [spans]
  (let [spans (vec spans)
        by-id (into {} (keep (fn [span]
                               (when-let [id (:spanId span)] [id span]))) spans)
        children (reduce (fn [m {:keys [spanId parentSpanId]}]
                           (if (and spanId parentSpanId
                                    (not= "" parentSpanId)
                                    (contains? by-id parentSpanId)
                                    (not= spanId parentSpanId))
                             (update m parentSpanId (fnil conj []) spanId)
                             m))
                         {} spans)
        root-ids (keep (fn [{:keys [spanId parentSpanId]}]
                         (when (and spanId
                                    (or (str/blank? parentSpanId)
                                        (not (contains? by-id parentSpanId))
                                        (= spanId parentSpanId)))
                           spanId))
                       spans)
        seen (atom #{})]
    (letfn [(walk [id path]
              (when (and id (not (contains? path id))
                         (not (contains? @seen id)))
                (swap! seen conj id)
                (assoc (get by-id id)
                       :children
                       (into [] (keep #(walk % (conj path id)))
                             (get children id [])))))]
      (let [roots (into [] (keep #(walk % #{})) root-ids)]
        (reduce (fn [forest {:keys [spanId]}]
                  (if-let [node (walk spanId #{})]
                    (conj forest node)
                    forest))
                roots spans)))))

(defn query-trace [conn trace-id]
  (let [spans (mapv span-json
                    (jdbc/fetch conn
                      ["SELECT Timestamp, toUnixTimestamp64Nano(Timestamp) AS TimestampUnixNano,
                               TraceId, SpanId, ParentSpanId, ServiceName,
                               SpanName, SpanKind, Duration, StatusCode, StatusMessage,
                               SpanAttributes, EventsJSON FROM otel_traces
                          WHERE TraceId = ? ORDER BY Timestamp, SpanId LIMIT 1000" trace-id]))]
    {:traceId trace-id
     :spans spans
     :spanTree (span-tree spans)
     :logs (mapv log-json
                 (jdbc/fetch conn
                   ["SELECT Timestamp, SeverityText, ServiceName, Body, TraceId,
                            SpanId, LogAttributes FROM otel_logs
                       WHERE TraceId = ? ORDER BY Timestamp, SpanId LIMIT 500" trace-id]))}))

(defn query-logs [conn]
  (mapv log-json
        (jdbc/fetch conn
          "SELECT Timestamp, SeverityText, ServiceName, Body, TraceId, SpanId,
                  LogAttributes FROM otel_logs ORDER BY Timestamp DESC LIMIT 100")))

(defn- trace-id-path [path]
  (second (re-matches #"/api/traces/([0-9a-f]{32})" path)))

(defn- viewer-trace-id-path [path]
  (second (re-matches #"/traces/([0-9a-f]{32})" path)))

(def ^:private editor-paths
  #{"/plotje-editor" "/plotje-editor/preview" "/assets/plotje-editor.js"
    "/hiccup-editor" "/hiccup-editor/preview" "/assets/hiccup-editor.js"})

(defn route-for
  ([path] (route-for path oscope-web/default-path))
  ([path oscope-path]
   (cond (= path "/") "/"
        (= path "/api/summary") "/api/summary"
        (= path "/api/traces") "/api/traces"
        (str/starts-with? path "/api/traces/") "/api/traces/:trace-id"
        (= path "/api/logs") "/api/logs"
        (= path "/assets/otel-viewer.js") "/assets/otel-viewer.js"
        (= path "/assets/workbench.js") "/assets/workbench.js"
        (= path oscope-path) oscope-path
        (= path (oscope-web/export-path oscope-path))
        (oscope-web/export-path oscope-path)
        (contains? editor-paths path) path
        (str/starts-with? path "/traces/") "/traces/:trace-id"
        (= path "/workbench") "/workbench"
        (= path "/work") "/work"
        (= path "/agent-work") "/agent-work"
        (= path "/agent-work-with-response") "/agent-work-with-response"
        (= path "/agent-work-intervention") "/agent-work-intervention"
        (= path "/upstream") "/upstream"
        (contains? otlp-receiver/receiver-paths path) path
        :else "/*")))

(defn- database-work! [connection tracer]
  (trace/with-span [span tracer "SELECT demo readiness"
                    {:kind :internal
                     :attributes {:db.system.name "clickhouse"
                                  :db.namespace "default"
                                  :db.operation.name "SELECT"}}]
    (let [ready (value-of (first (jdbc/fetch connection "SELECT 1 AS ready"))
                          :ready)]
      (trace/set-attribute! span :demo.db.ready ready)
      ready)))

(defn- queue-work! [tracer logger propagator]
  ;; The envelope is deliberately ordinary data. Injecting and extracting the
  ;; propagator proves the same handoff an fs-backed or cross-process queue
  ;; would perform without making this small demo own a queue lifecycle.
  (let [envelope
        (trace/with-span [_ tracer "demo.jobs publish"
                          {:kind :producer
                           :attributes {:messaging.system "demo.queue"
                                        :messaging.destination.name "demo.jobs"
                                        :messaging.operation.type "publish"}}]
          {:headers (propagation/inject-current propagator {})
           :body {:job "refresh-catalog"}})
        parent (propagation/extract propagator context/root (:headers envelope))]
    (trace/with-span [_ tracer "demo.jobs process"
                      {:kind :consumer
                       :parent parent
                       :attributes {:messaging.system "demo.queue"
                                    :messaging.destination.name "demo.jobs"
                                    :messaging.operation.type "process"
                                    :demo.job (get-in envelope [:body :job])}}]
      (logs/emit! logger {:body "processed propagated demo job"
                          :severity :info
                          :attributes {:messaging.destination.name "demo.jobs"}})
      (:body envelope))))

(defn- real-work! [{:keys [connection port tracer logger propagator]}]
  (logs/emit! logger {:body "calling loopback upstream" :severity :info
                      :attributes {:http.route "/work"}})
  (database-work! connection tracer)
  (queue-work! tracer logger propagator)
  (trace/with-span [client-span tracer "HTTP GET /upstream"
                    {:kind :client
                     :attributes {:http.request.method "GET"
                                  :http.route "/upstream"
                                  :server.address "127.0.0.1"
                                  :server.port port}}]
    (let [headers (propagation/inject-current propagator {})
          response (http-client/get (str "http://127.0.0.1:" port "/upstream")
                                    {:headers headers
                                     :conn-timeout 2000 :socket-timeout 5000
                                     :throw-exceptions false})]
      (trace/set-attribute! client-span :http.response.status_code (:status response))
      (if (= 200 (:status response))
        (do (trace/set-status! client-span :ok)
            (logs/emit! logger {:body "loopback upstream completed" :severity :info})
            {:upstream (json/read-str (:body response) :key-fn keyword)})
        (do
          (trace/set-status! client-span :error
                             (str "HTTP " (:status response)))
          (throw (ex-info "loopback upstream failed" {:status (:status response)})))))))

(defn- sanitized-captured-response [value]
  ;; Thinking is disabled by default, but strip the common delimited form too
  ;; so an endpoint that ignores the request option cannot publish it.
  (let [value (-> (or value "")
                  (str/replace #"(?is)<think\b[^>]*>.*?</think>" "")
                  str/trim)]
    (subs value 0 (min max-captured-response-length (count value)))))

(defn- captured-prompt [messages]
  (->> messages
       (map (fn [{:keys [role content]}]
              (str (str/capitalize (name role)) ": " content)))
       (str/join "\n\n")
       sanitized-captured-response))

(defn- lemonade-chat!
  [{:keys [tracer propagator lemonade-base-url lemonade-model
           lemonade-telemetry-address lemonade-disable-thinking?]}
   {:keys [capture-content? messages turn-number]}]
  (trace/with-span
    [generation tracer "chat"
     {:kind :client
      :attributes {:gen_ai.operation.name "chat"
                   :gen_ai.provider.name "lemonade"
                   :gen_ai.request.model lemonade-model
                   :gen_ai.request.max_tokens 192
                   :gen_ai.request.temperature 0
                   :gen_ai.request.stream false
                   :server.address lemonade-telemetry-address
                   :samizdat.turn.number turn-number
                   :samizdat.prompt.content_state
                   (if capture-content? "captured" "omitted")
                   :samizdat.response.content_state
                   (if capture-content? "captured" "omitted")}}]
    (when lemonade-disable-thinking?
      (trace/set-attribute! generation :gen_ai.request.reasoning_effort "none"))
    (when capture-content?
      (trace/set-attribute! generation :samizdat.prompt.sanitized
                            (captured-prompt messages)))
    (let [request-map
          (cond->
            {:model lemonade-model
             :messages messages
             :max_tokens 192
             :temperature 0
             :stream false}
            lemonade-disable-thinking?
            (assoc :chat_template_kwargs {:enable_thinking false}))
          payload (json/write-str request-map)
          response
          (trace/with-span
            [http-span tracer "HTTP POST /v1/chat/completions"
             {:kind :client
              :attributes {:http.request.method "POST"
                           :url.template "/v1/chat/completions"
                           :server.address lemonade-telemetry-address
                           :samizdat.turn.number turn-number}}]
            (let [response
                  (http-client/post
                   (str (str/replace lemonade-base-url #"/+$" "")
                        "/chat/completions")
                   {:headers (propagation/inject-current propagator {})
                    :body payload :content-type :json
                    :conn-timeout 5000 :socket-timeout 300000
                    :throw-exceptions false})]
              (trace/set-attribute! http-span :http.response.status_code
                                    (:status response))
              (when (>= (:status response) 400)
                (trace/set-status! http-span :error (str "HTTP " (:status response))))
              response))]
      (when (not= 200 (:status response))
        (throw (ex-info "Lemonade chat completion failed"
                        {:status (:status response)})))
      (let [decoded (json/read-str (:body response) :key-fn keyword)
            choice (first (:choices decoded))
            content (get-in choice [:message :content])
            usage (:usage decoded)
            finish-reason (:finish_reason choice)]
        (trace/set-attribute! generation :gen_ai.response.finish_reasons
                              (or finish-reason "unknown"))
        (doseq [[attribute value]
                [[:gen_ai.usage.input_tokens (:prompt_tokens usage)]
                 [:gen_ai.usage.output_tokens (:completion_tokens usage)]
                 [:gen_ai.usage.total_tokens (:total_tokens usage)]]]
          (when (number? value)
            (trace/set-attribute! generation attribute value)))
        (when capture-content?
          (trace/set-attribute! generation :samizdat.response.sanitized
                                (sanitized-captured-response content)))
        (trace/set-status! generation :ok)
        {:content content
         :finish-reason finish-reason
         :usage usage
         :model lemonade-model}))))

(defn- execute-response-tool! [tracer turn-number content]
  (trace/with-span
    [_ tracer "execute_tool response_length"
     {:kind :internal
      :attributes {:gen_ai.operation.name "execute_tool"
                   :gen_ai.tool.name "response_length"
                   :gen_ai.tool.type "function"
                   :samizdat.branch.id "B1"
                   :samizdat.turn.number turn-number}}]
    (count (or content ""))))

(defn- agent-turn!
  [{:keys [tracer] :as app}
   {:keys [capture-content? messages turn-number final?]}]
  (trace/with-span
    [turn tracer (str "samizdat.turn " turn-number)
     {:kind :internal
      :attributes {:samizdat.branch.id "B1"
                   :samizdat.turn.number turn-number
                   :samizdat.control.phase "advance-branch"}}]
    (let [{:keys [content] :as result}
          (lemonade-chat! app {:capture-content? capture-content?
                               :messages messages
                               :turn-number turn-number})]
      (trace/add-event! turn "samizdat.response.absorbed"
                        {:samizdat.parse.status "ok"
                         :samizdat.selected_tool
                         (if final? "response_length" "controller_review")})
      (if final?
        (let [characters (execute-response-tool! tracer turn-number content)]
          (trace/add-event! turn "samizdat.route.selected"
                            {:samizdat.route "stop"})
          (assoc result :response-characters characters))
        (do
          (trace/add-event! turn "samizdat.route.selected"
                            {:samizdat.route "controller-intervention"})
          result)))))

(defn- agent-run!
  [{:keys [tracer logger] :as app} prompt capture-content? intervention?]
  (trace/with-span
    [run tracer "samizdat.run"
     {:kind :internal
      :attributes {:gen_ai.operation.name "invoke_agent"
                   :gen_ai.provider.name "lemonade"
                   :gen_ai.request.model (:lemonade-model app)
                   :gen_ai.workflow.name "observability-demo"
                   :samizdat.run.id (str "demo-" (System/currentTimeMillis))
                   :samizdat.prompt.content_state
                   (if capture-content? "captured" "omitted")
                   :samizdat.response.content_state
                   (if capture-content? "captured" "omitted")}}]
    (trace/add-event! run "samizdat.run.started"
                      {:samizdat.control.driver "beam"})
    (trace/with-span
      [control tracer "samizdat.control-loop"
       {:kind :internal
        :attributes {:samizdat.control.driver "beam"
                     :samizdat.control.phase "advance-all"}}]
      (trace/add-event! control "samizdat.scheduler.tick"
                        {:samizdat.active_branches 1})
      (trace/with-span
        [_ tracer "samizdat.branch B1"
         {:kind :internal
          :attributes {:samizdat.branch.id "B1"}}]
        (let [first-messages [{:role "user" :content prompt}]
              first-turn (agent-turn! app {:capture-content? capture-content?
                                           :messages first-messages
                                           :turn-number 1
                                           :final? (not intervention?)})
              final-turn
              (if intervention?
                (do
                  (trace/with-span
                    [intervention tracer "controller intervention"
                     {:kind :internal
                      :attributes
                      {:samizdat.intervention.action "revise"
                       :samizdat.intervention.source "controller"
                       :samizdat.intervention.reason
                       "first draft required a concrete correctness review"
                       :samizdat.branch.id "B1"}}]
                    (trace/add-event! intervention "samizdat.controller.intervened"
                                      {:samizdat.route "revise"}))
                  (agent-turn!
                   app {:capture-content? capture-content?
                        :messages [{:role "user" :content prompt}
                                   {:role "assistant" :content (:content first-turn)}
                                   {:role "user" :content controller-revision}]
                        :turn-number 2 :final? true}))
                first-turn)
              usages (if intervention? [(:usage first-turn) (:usage final-turn)]
                         [(:usage final-turn)])
              input-tokens (reduce + 0 (keep :prompt_tokens usages))
              output-tokens (reduce + 0 (keep :completion_tokens usages))]
          (trace/set-attribute! run :gen_ai.usage.input_tokens
                                input-tokens)
          (trace/set-attribute! run :gen_ai.usage.output_tokens
                                output-tokens)
          (logs/emit! logger
                      {:body "completed Lemonade-backed Samizdat demo run"
                       :severity :info
                       :attributes {:gen_ai.provider.name "lemonade"
                                    :gen_ai.request.model (:model final-turn)
                                    :samizdat.branch.id "B1"
                                    :samizdat.turn.count (if intervention? 2 1)
                                    :samizdat.controller.intervened intervention?}})
          (trace/set-status! run :ok)
          {:model (:model final-turn)
           :response (:content final-turn)
           :response-captured capture-content?
           :controller-intervened intervention?
           :turns (if intervention? 2 1)
           :response-characters (:response-characters final-turn)})))))

(defn- agent-work! [app capture-content?]
  (agent-run! app initial-agent-prompt capture-content? false))

(defn- agent-intervention-work! [app]
  (agent-run! app initial-agent-prompt true true))

(defn app-context
  "Build the handler context. Query and work functions are injectable so pure
  Ring tests need neither native state nor a live socket."
  [{:keys [connection port tracer logger propagator flush-fn stream-state
           summary-fn traces-fn filtered-traces-fn trace-filter-options-fn
           now-nanos-fn trace-fn logs-fn work-fn agent-work-fn
           agent-intervention-work-fn otlp-handler
           oscope-source oscope-handler oscope-path
           lemonade-base-url lemonade-model lemonade-telemetry-address
           lemonade-disable-thinking? workbench-state workbench-adapter
           workbench-kind]
    :or {port 8080}}]
  (let [now-nanos-fn (or now-nanos-fn #(* (System/currentTimeMillis) 1000000))
        oscope-path (or oscope-path oscope-web/default-path)
        oscope-handler
        (or oscope-handler
            (if oscope-source
              (oscope-web/handler oscope-source {:path oscope-path})
              (oscope-web/handler
               {:load-command (fn [_ selection]
                                (oscope-sample/screen-for-selection selection))}
               {:path oscope-path})))
        configured-lemonade-url
        (or lemonade-base-url (System/getenv "DEMO_LEMONADE_BASE_URL"))
        traces-fn (or traces-fn #(query-traces connection))
        filtered-traces-fn
        (or filtered-traces-fn
            (if connection
              (fn [selection now] (query-traces connection selection now))
              (fn [_ _] (traces-fn))))
        trace-filter-options-fn
        (or trace-filter-options-fn
            (if connection
              (fn [selection now]
                (query-trace-filter-options connection selection now))
              (fn [selection _]
                {:selected selection
                 :service-options []
                 :status-options [{:value "ok" :label "OK"}
                                  {:value "error" :label "Error"}]
                 :window-options trace-window-options})))
        workbench-state (or workbench-state (workbench/state))
        app
        {:connection connection :port port
         :tracer (or tracer (sdk/tracer "demo.http"))
         :logger (or logger (sdk/logger "demo.http"))
         :propagator (or propagator propagation/default-propagator)
         :stream-state (or stream-state (demo-datastar/stream-state))
         :flush-fn (or flush-fn (constantly true))
         :summary-fn (or summary-fn #(query-summary connection))
         :traces-fn traces-fn
         :filtered-traces-fn filtered-traces-fn
         :trace-filter-options-fn trace-filter-options-fn
         :now-nanos-fn now-nanos-fn
         :trace-fn (or trace-fn #(query-trace connection %))
         :logs-fn (or logs-fn #(query-logs connection))
         :oscope-source oscope-source
         :oscope-handler oscope-handler
         :oscope-path oscope-path
         :work-fn (or work-fn real-work!)
         :agent-work-fn (or agent-work-fn agent-work!)
         :agent-intervention-work-fn
         (or agent-intervention-work-fn agent-intervention-work!)
         :lemonade-base-url (or configured-lemonade-url default-lemonade-base-url)
         :lemonade-model (or lemonade-model
                             (System/getenv "DEMO_LEMONADE_MODEL")
                             default-lemonade-model)
         :lemonade-telemetry-address
         (or lemonade-telemetry-address
             (System/getenv "DEMO_LEMONADE_TELEMETRY_ADDRESS")
             "local-model-host")
         :lemonade-disable-thinking?
         (if (nil? lemonade-disable-thinking?)
           (not (contains? #{"false" "0"}
                           (some-> (System/getenv "DEMO_LEMONADE_DISABLE_THINKING")
                                   str/lower-case)))
           lemonade-disable-thinking?)
         :otlp-handler (or otlp-handler
                           (fn [_] (error-response 503 "OTLP receiver unavailable")))
         :workbench-state
         (assoc workbench-state :adapter-kind
                (or workbench-kind
                    (if workbench-adapter :custom :fixture)))}]
    (assoc app :workbench-adapter
           (or workbench-adapter (workbench-fixture/adapter)))))

(defn raw-handler [{:keys [summary-fn traces-fn filtered-traces-fn
                           trace-filter-options-fn now-nanos-fn trace-fn logs-fn
                           work-fn agent-work-fn agent-intervention-work-fn
                           logger stream-state otlp-handler oscope-handler
                           oscope-path]
                    :as app}]
  (fn [{:keys [request-method uri query-string] :as request}]
    (cond
      (contains? editor-paths uri)
      (or (plotje-editor/handler request)
          (hiccup-editor/handler request))

      (contains? #{oscope-path (oscope-web/export-path oscope-path)} uri)
      (oscope-handler request)

      (otlp-receiver/receiver-request? request)
      (otlp-handler request)

      (and (= :post request-method) (= uri "/work"))
      (try
        (work-fn app)
        (assoc (if (= "fetch" (get-in request [:headers "x-otel-enhancement"]))
                 {:status 204 :headers {"Cache-Control" "no-store"} :body nil}
                 {:status 303 :headers (assoc html-headers "Location" "/") :body ""})
               ::flush? true)
        (catch Throwable e
          (logs/emit! logger {:body (str "work failed: " (ex-message e))
                              :severity :error})
          {:status 502 :headers html-headers
           :body (viewer/render-page {:title "Work failed"
                                      :summary {} :traces [] :logs []})}))

      (and (= :post request-method)
           (contains? #{"/agent-work" "/agent-work-with-response"
                        "/agent-work-intervention"} uri))
      (try
        (if (= uri "/agent-work-intervention")
          (agent-intervention-work-fn app)
          (agent-work-fn app (= uri "/agent-work-with-response")))
        (assoc (if (= "fetch" (get-in request [:headers "x-otel-enhancement"]))
                 {:status 204 :headers {"Cache-Control" "no-store"} :body nil}
                 {:status 303 :headers (assoc html-headers "Location" "/") :body ""})
               ::flush? true)
        (catch Throwable e
          (logs/emit! logger {:body (str "agent demo failed: " (ex-message e))
                              :severity :error})
          {:status 502 :headers html-headers
           :body (viewer/render-page {:title "Agent demo failed"
                                      :summary {} :traces [] :logs []})}))

      (and (= :post request-method) (= uri "/workbench"))
      (assoc (workbench/post-run! (:workbench-state app)
                                  (:workbench-adapter app) request)
             ::flush? true)

      (not= :get request-method) (error-response 405 "method not allowed")

      :else
      (cond
        (= uri "/")
        (let [selection (trace-filter-selection query-string)]
          (if (demo-datastar/sse-request? request)
            (demo-datastar/stream-response
             stream-state
             #(let [now (now-nanos-fn)]
                (viewer/render-live-content
                 {:eyebrow "OpenTelemetry · embedded chDB"
                  :enhancement-path "/assets/otel-viewer.js"
                  :summary (summary-fn)
                  :traces (filtered-traces-fn selection now)
                  :logs (logs-fn)})))
            (let [now (now-nanos-fn)]
              (html-response
               (viewer/render-page
                {:title "Jolt Observability"
                 :eyebrow "OpenTelemetry · embedded chDB"
                 :post-actions [{:path "/work" :label "Generate work"}
                                {:path "/agent-work"
                                 :label "Run model (metadata only)"}
                                {:path "/agent-work-with-response"
                                 :label "Run model (show exchange)"}
                                {:path "/agent-work-intervention"
                                 :label "Run multi-turn intervention"}]
                 :enhancement-path "/assets/otel-viewer.js?v=2"
                 :live-attributes (demo-datastar/init-attributes)
                 :trace-filters (trace-filter-options-fn selection now)
                 :summary (summary-fn)
                 :traces (filtered-traces-fn selection now)
                 :logs (logs-fn)})))))
        (str/starts-with? uri "/traces/")
        (if-let [trace-id (viewer-trace-id-path uri)]
          (html-response
            (viewer/render-page {:title "Trace detail"
                                 :eyebrow "OpenTelemetry · embedded chDB"
                                 :post-actions
                                 [{:path "/work" :label "Generate work"}
                                  {:path "/agent-work"
                                   :label "Run model (metadata only)"}
                                  {:path "/agent-work-with-response"
                                   :label "Run model (show exchange)"}
                                  {:path "/agent-work-intervention"
                                   :label "Run multi-turn intervention"}]
                                 :trace (samizdat-kindly/advise-trace
                                         (trace-fn trace-id))}))
          (error-response 400 "trace id must be 32 lowercase hex characters"))
        (= uri "/assets/otel-viewer.js")
        {:status 200 :headers javascript-headers :body (viewer/enhancement-script)}
        (= uri "/workbench")
        (workbench/get-page (:workbench-state app) request)
        (= uri "/assets/workbench.js") (workbench/asset-response)
        (= uri "/api/summary") (json-response (summary-fn))
        (= uri "/api/traces")
        (let [selection (trace-filter-selection query-string)]
          (json-response (filtered-traces-fn selection (now-nanos-fn))))
        (= uri "/api/logs") (json-response (logs-fn))
        (= uri "/upstream") (do
                                (logs/emit! logger {:body "upstream served" :severity :info})
                                (json-response {:ok true :source "loopback"}))
        (= uri "/work") (try
                           (json-response (merge {:ok true} (work-fn app)))
                           (catch Throwable e
                             (logs/emit! logger
                                         {:body (str "work failed: " (ex-message e))
                                          :severity :error})
                             (error-response 502 "upstream request failed")))
        (str/starts-with? uri "/api/traces/")
        (if-let [trace-id (trace-id-path uri)]
          (json-response (trace-fn trace-id))
          (error-response 400 "trace id must be 32 lowercase hex characters"))
        :else (error-response 404 "not found")))))

(defn handler [app]
  (let [dispatch (raw-handler app)
        tracer (:tracer app)
        propagator (:propagator app)]
    (otlp-receiver/wrap-suppress-receiver-telemetry
     (fn [{:keys [request-method uri] :as request}]
       (let [route (route-for uri (:oscope-path app))
             method (str/upper-case (name (or request-method :unknown)))
             untraced? (or (otlp-receiver/telemetry-suppressed? request)
                           (= route "/")
                           (= route "/traces/:trace-id")
                           (= route "/assets/otel-viewer.js")
                           (= route "/workbench")
                           (= route "/assets/workbench.js")
                           (contains? #{(:oscope-path app)
                                        (oscope-web/export-path
                                         (:oscope-path app))}
                                      route)
                           (contains? editor-paths route)
                           (contains? #{"/agent-work"
                                        "/agent-work-with-response"
                                        "/agent-work-intervention"} route)
                           (str/starts-with? route "/api/"))
             response (if untraced?
                        (dispatch request)
                        (let [parent (propagation/extract propagator context/root
                                                          (or (:headers request) {}))]
                          (trace/with-span [span tracer (str "HTTP " method " " route)
                                            {:kind :server
                                             :parent parent
                                             :attributes {:http.request.method method
                                                          :http.route route :url.path uri}}]
                            (let [response (dispatch request) status (:status response)]
                              (trace/set-attribute! span :http.response.status_code status)
                              (when (>= status 500)
                                (trace/set-status! span :error (str "HTTP " status)))
                              response))))]
           (when (::flush? response)
             ((:flush-fn app)))
           (dissoc response ::flush?))))))

(defn- env-port []
  (let [raw (System/getenv "DEMO_PORT")]
    (if (str/blank? raw) 8080 (parse-long raw))))

(defn start!
  "Start database, batched OTel SDK, and HTTP server. The returned map has an
  idempotent, retryable :stop! function which enforces server, workbench
  source, SDK, and connection shutdown in ownership order."
  ([] (start! {}))
  ([{:keys [port db-spec workbench-adapter workbench-kind
            workbench-source-close! oscope-path]
     :or {port (env-port)}}]
   (let [spec (or db-spec (System/getenv "DEMO_CHDB_SPEC") "chdb::memory:")
         conn (jdbc/connection spec)]
     (try
       (let [exporter (chdb-export/exporter {:connection conn
                                             :signals #{:spans :metrics :logs}})
             otel (sdk/init! {:service-name service-name :exporter exporter
                              :processor :batch :metrics? false :logs? true
                              :bridge-logging? false})
             oscope-source* (atom nil)]
         (try
           (let [oscope-source (oscope/open! {:connection conn})
                 _ (reset! oscope-source* oscope-source)
                 oscope-path (or oscope-path
                                 (System/getenv "DEMO_OSCOPE_PATH")
                                 oscope-web/default-path)
                 app (app-context {:connection conn :port port
                                   :propagator (:propagator otel)
                                   :flush-fn #(sdk/force-flush! otel)
                                   :otlp-handler (demo-otlp/handler exporter)
                                   :oscope-source oscope-source
                                   :oscope-path oscope-path
                                   :workbench-adapter workbench-adapter
                                   :workbench-kind workbench-kind})
                 server (http-server/run-server (handler app) :port port
                                                :server-name "127.0.0.1"
                                                :reuse-address? true)
                 lifecycle-state (atom :open)
                 front-stopped? (atom false)
                 stop-lock (Object.)
                 close-source! (or workbench-source-close!
                                   (fn [] {:status :closed}))]
             {:port port :connection conn :otel otel :server server :app app
              :oscope-source oscope-source
              :stop! (fn []
                       (locking stop-lock
                         (if (= :closed @lifecycle-state)
                           {:status :closed}
                           (do
                             (reset! lifecycle-state :closing)
                             ;; Stop ingress first: no new work may appear
                             ;; while the owned workbench source is joining.
                             ;; Publish success only after stop-server returns;
                             ;; a timed-out/throwing stop must be retried before
                             ;; the database can be closed beneath live work.
                             (when-not @front-stopped?
                               (demo-datastar/stop-streams! (:stream-state app))
                               (http-server/stop-server server)
                               (reset! front-stopped? true))
                             (let [workbench-result
                                   (try (workbench/stop! (:workbench-state app))
                                        (catch Throwable error
                                          {:status :closing :errors [error]}))
                                   source-result
                                   (try (close-source!)
                                        (catch Throwable error
                                          {:status :closing :errors [error]}))
                                   closing? (or (= :closing (:status workbench-result))
                                                (= :closing (:status source-result)))]
                               (if closing?
                                 {:status :closing
                                  :workbench workbench-result
                                  :source source-result}
                                 (let [oscope-result
                                       (try
                                         (oscope/close! oscope-source)
                                         {:status :closed}
                                         (catch Throwable error
                                           {:status :closing :errors [error]}))]
                                   (if (= :closing (:status oscope-result))
                                     {:status :closing
                                      :workbench workbench-result
                                      :source source-result
                                      :oscope oscope-result}
                                     (do
                                       (sdk/shutdown! otel)
                                       (.close conn)
                                       (reset! lifecycle-state :closed)
                                       {:status :closed})))))))))})
           (catch Throwable e
             (when workbench-source-close!
               (try (workbench-source-close!) (catch Throwable _ nil)))
             (when-let [source @oscope-source*]
               (try (oscope/close! source) (catch Throwable _ nil)))
             (sdk/shutdown! otel)
             (throw e))))
       (catch Throwable e
         (.close conn)
         (throw e))))))

(defn stop! [lifecycle]
  (when-let [f (:stop! lifecycle)] (f)))

(defn -main [& _]
  (let [lifecycle (start!)]
    (println (str "Jolt observability demo listening on http://127.0.0.1:" (:port lifecycle)))
    (try
      @(promise)
      (finally
        (stop! lifecycle)
        (System/exit 0)))))
