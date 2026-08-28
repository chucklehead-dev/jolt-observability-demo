(ns demo.samizdat-adapter
  "Async workbench adapter over Samizdat's no-HTTP embedded facade.

  The lossy event subscription is only a wakeup. Every visible event is read
  back through Samizdat's durable journal cursor, so a slow browser or dropped
  notification cannot create holes in the run story."
  (:require [clojure.core.async :as async]
            [clojure.string :as str]
            [demo.samizdat-aspect-provider :as aspect-provider]
            [demo.workbench-fixture :as workbench-fixture]
            [otel.context :as context]
            [otel.sdk :as sdk]
            [otel.trace :as trace]
            [samizdat.embed :as embed]))

(def ^:private journal-page-size 200)
(def ^:private idle-poll-ms 1000)
(def ^:private instrumentation-scope "io.github.casselc/jolt-observability-demo.samizdat-adapter")

(defn- debug-failure! [error]
  (when (contains? #{"1" "true" "yes"}
                   (some-> (System/getenv "DEMO_DEBUG") str/trim str/lower-case))
    (binding [*out* *err*]
      (loop [cause error depth 0]
        (when (and cause (< depth 8))
          (prn {:demo/samizdat-failure depth
                :type (.getName (class cause))
                :message (ex-message cause)
                :data (ex-data cause)})
          (recur (ex-cause cause) (inc depth)))))))

(defn- display-value [value]
  (let [value (str (or value ""))]
    (subs value 0 (min 240 (count value)))))

(defn- event-detail [{:keys [kind branch_id branch-id turn data]}]
  (let [kind (keyword kind)
        branch (or branch-id branch_id)]
    (case kind
      :run-started "Samizdat opened the run."
      :run-finished (str "Samizdat closed the run with status "
                         (display-value (:status data)) ".")
      :branch-opened (str "Branch " (display-value branch) " opened.")
      :branch-closed (str "Branch " (display-value branch) " closed: "
                          (display-value (:status data)) ".")
      :turn (str "Branch " (display-value branch) " completed turn " turn
                 " with " (display-value (:tool data)) ".")
      :intervention-submitted "A controller intervention was queued."
      :intervention-resolved "A controller intervention was applied."
      (str "Samizdat recorded " (name kind) "."))))

(defn- workbench-event [{:keys [kind turn data] :as event}]
  (let [kind (keyword kind)]
    (cond->
    {:stage (case kind
              :run-started :run-opened
              :run-finished :run-closed
              :turn :tool-completed
              :intervention-submitted :controller-decided
              :intervention-resolved :controller-decided
              :event)
     :detail (event-detail event)}
    turn (assoc :turn turn)
    (and (= :turn kind) (:tool data)) (assoc :tool (display-value (:tool data)))
    (contains? #{:intervention-submitted :intervention-resolved} kind)
    (assoc :decision (name kind)))))

(defn- terminal? [event]
  (= :run-finished (keyword (:kind event))))

(defn- final-response [embedded run-id result redact]
  (redact
   (or (:answer result)
       (get-in (context/with-instrumentation-suppressed
                 (embed/get-run embedded run-id))
               [:run :final_answer])
       (when (= :aborted (:status result)) "Run aborted.")
       "Samizdat finished without a final response.")))

(defn- capture-summary [embedded run-id turn-count]
  (let [run (:run (context/with-instrumentation-suppressed
                    (embed/get-run embedded run-id)))]
    {:model (or (:model run) "unknown")
     :turns turn-count
     :status (or (:status run) "unknown")
     :content-state "final response shown; telemetry content follows provider policy"
     :source "embedded Samizdat durable journal"}))

(defn- drain-page! [embedded run-id cursor event!]
  (let [{:keys [events next]}
        (context/with-instrumentation-suppressed
          (embed/journal-tail embedded run-id cursor journal-page-size))]
    (doseq [event events] (event! (workbench-event event)))
    {:cursor next
     :events events
     :terminal (some terminal? events)
     :turns (count (filter #(= :turn (keyword (:kind %))) events))}))

(defn- abort-run! [embedded handle parent]
  (trace/with-span
    [_ (sdk/tracer instrumentation-scope {:version "0.1.0"})
     "samizdat.control abort"
     {:kind :internal
      :parent (or parent context/root)
      :attributes {:samizdat.run.id (str (:run-id handle))}}]
    (embed/abort! embedded handle)))

(defn- watch-run! [embedded run-id run-future wakeups callbacks cancelled? redact]
  (loop [cursor 0
         turns 0]
    (let [{next-cursor :cursor events :events terminal :terminal page-turns :turns}
          (drain-page! embedded run-id cursor (:event! callbacks))
          turns (+ turns page-turns)]
      (cond
        terminal
        (let [result @run-future]
          ((:complete! callbacks)
           (final-response embedded run-id result redact)
           (capture-summary embedded run-id turns)))

        @cancelled?
        (do
          ;; abort! journals a terminal row; keep following the durable cursor
          ;; until that row is visible rather than inventing a local ending.
          (async/alts!! [wakeups (async/timeout idle-poll-ms)])
          (recur next-cursor turns))

        :else
        (if (= journal-page-size (count events))
          ;; A full page may have more durable rows immediately available;
          ;; skip the wait and drain the next page.
          (recur next-cursor turns)
          (do
            (async/alts!! [wakeups (async/timeout idle-poll-ms)])
            (recur next-cursor turns)))))))

(defrecord SamizdatAdapter [embedded run-options content-policy display-redact]
  workbench-fixture/AsyncRunAdapter
  (start-async! [_ prompt callbacks]
    (let [cancelled? (atom false)
          run-handle (atom nil)
          run-context (atom nil)
          runner
          (binding [aspect-provider/*content-policy* content-policy]
            (future
              (let [wakeups (embed/subscribe embedded)]
                (try
                  (let [handle (embed/start-run!
                                embedded
                                (merge run-options
                                       {:problem prompt
                                        :on-start
                                        (fn [run-id]
                                          (reset! run-context (context/current))
                                          ((:started! callbacks) run-id
                                           {:source "embedded Samizdat"}))}))]
                    (reset! run-handle handle)
                    (when @cancelled?
                      (abort-run! embedded handle @run-context))
                    (watch-run! embedded (:run-id handle) (:future handle)
                                wakeups callbacks cancelled? display-redact))
                  (catch Throwable error
                    (debug-failure! error)
                    ((:failed! callbacks) error))
                  (finally
                    (embed/unsubscribe! wakeups))))))]
      {:abort! (fn []
                 (reset! cancelled? true)
                 (when-let [handle @run-handle]
                   (abort-run! embedded handle @run-context)))
       :join! (fn [timeout-ms]
                (not= ::join-timeout
                      (deref runner timeout-ms ::join-timeout)))})))

(defn adapter
  "Create a real Samizdat adapter. `run-options` must include a positive
  `:start-timeout-ms`. Demo-only `:otel-content-policy` and `:display-redact`
  configure bounded telemetry capture and UI redaction; remaining entries are
  forwarded to beam/run!."
  [embedded run-options]
  (let [content-policy (aspect-provider/content-policy
                        (or (:otel-content-policy run-options) {}))
        display-redact (or (:display-redact run-options) identity)]
    (when-not (fn? display-redact)
      (throw (ex-info ":display-redact must be a function" {})))
    (->SamizdatAdapter embedded
                       (dissoc run-options :otel-content-policy :display-redact)
                       content-policy display-redact)))
