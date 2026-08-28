(ns demo.workbench
  "The /workbench route: a user prompt evolves through one run's ordered
  semantic-stage events (run-opened, turn-started, model-requested,
  tool-dispatched, tool-completed, controller-decided, run-closed) and a
  terminal response. State lives in a `glimmer.ratom` cell; the SSE stream is
  `jolt.datastar.core/wrap-datastar` scoped to this route alone, so a
  swap!/reset! on the ratom re-renders every open workbench stream without
  touching the rest of the application's routes or its OTel export path.

  This namespace owns no model, no Samizdat dependency, and no OTel
  instrumentation — the run's actual content comes from an injected
  `demo.workbench-fixture/RunAdapter` (a deterministic local fixture by
  default)."
  (:require [clojure.core.async :as async]
            [clojure.string :as str]
            [demo.workbench-fixture :as fixture]
            [demo.workbench-view :as view]
            [glimmer.ratom :as ratom]
            [jolt.datastar.core :as datastar]
            [jolt.http.body :as http-body])
  (:import [java.net URLDecoder]))

(def ^:private max-prompt-bytes 2048)
(def ^:private max-response-bytes 4000)
(def ^:private max-request-body-bytes 8192)
(def ^:private max-history 5)
(def ^:private default-reveal-interval-ms 350)
(def ^:private heartbeat-ms 2000)

(def ^:private page-headers
  {"Content-Type" "text/html; charset=UTF-8"
   "Cache-Control" "no-store"
   "Content-Security-Policy"
   "default-src 'none'; style-src 'unsafe-inline'; script-src 'self'; connect-src 'self'; form-action 'self'; base-uri 'none'"
   "X-Content-Type-Options" "nosniff"})

(def ^:private script-headers
  {"Content-Type" "text/javascript; charset=UTF-8"
   "Cache-Control" "no-cache"
   "X-Content-Type-Options" "nosniff"})

(def ^:private redirect-headers
  {"Location" "/workbench" "Cache-Control" "no-store"})

;; --- state -------------------------------------------------------------

(defn state
  "Construct fresh, injectable /workbench state. Never shared between
  app-context instances unless a caller passes one explicitly, so tests get
  an isolated ratom per app."
  ([] (state {}))
  ([{:keys [reveal-interval-ms]}]
   {:ratom (ratom/atom {:current nil :history []})
    :counter (atom 0)
    :stopped? (atom false)
    :reveal-interval-ms (or reveal-interval-ms default-reveal-interval-ms)}))

(defn stop!
  "Ask any in-flight reveal loop to stop at its next tick, and any open SSE
  stream to stop at its next heartbeat, so server shutdown does not hang."
  [state]
  (reset! (:stopped? state) true)
  nil)

;; --- pure state transitions (unit-tested directly) ----------------------

(defn- bounded-text [value limit]
  (let [s (str/trim (str (or value "")))]
    (subs s 0 (min limit (count s)))))

(defn new-run
  "Build a fresh run from `prompt` by consulting `adapter`. Pure given a pure
  adapter: the same id/prompt/adapter always produce the same run."
  [id prompt adapter]
  (let [prompt (bounded-text prompt max-prompt-bytes)
        {:keys [events response capture]}
        (fixture/run-script adapter prompt)]
    {:id id
     :prompt prompt
     :status :running
     :events []
     :pending (vec events)
     :response (bounded-text response max-response-bytes)
     :capture capture}))

(defn apply-start
  "Reducer: install `run` as the current run, archiving whatever was current
  (regardless of whether it had finished revealing) into bounded history."
  [state-value run]
  (let [{:keys [current history]} state-value
        history (if current (into [current] history) history)]
    {:current run :history (vec (take max-history history))}))

(defn apply-reveal
  "Reducer: move one pending event of the run identified by `id` into its
  revealed events, closing the run and publishing its response when the
  revealed event is :run-closed. A no-op once `id` is no longer current or has
  nothing left pending, so a superseded reveal loop harmlessly idles out."
  [state-value id]
  (let [{:keys [current] :as state-value} state-value]
    (if (and current (= id (:id current)) (seq (:pending current)))
      (let [next-event (first (:pending current))
            pending (vec (rest (:pending current)))
            events (conj (:events current) next-event)
            closed? (= :run-closed (:stage next-event))]
        (assoc state-value :current
               (cond-> (assoc current :events events :pending pending)
                 closed? (assoc :status :closed))))
      state-value)))

;; --- production reveal driver --------------------------------------------

(defn- reveal-loop! [state id]
  (future
    (loop []
      (when-not @(:stopped? state)
        (Thread/sleep (:reveal-interval-ms state))
        (let [next-state (swap! (:ratom state) apply-reveal id)
              current (:current next-state)]
          (when (and current (= id (:id current)) (seq (:pending current)))
            (recur)))))))

(defn start-run!
  "Start a new run for `prompt` against `adapter`, installing it as current and
  kicking off its background reveal. Returns the new run's id."
  [state adapter prompt]
  (let [id (swap! (:counter state) inc)
        run (new-run id prompt adapter)]
    (swap! (:ratom state) apply-start run)
    (reveal-loop! state id)
    id))

;; --- HTTP -----------------------------------------------------------------

(defn- sse-request? [{:keys [query-string]}]
  (boolean (some #(= "datastar-sse=true" %)
                 (str/split (or query-string "") #"&"))))

(defn- write-text! [sink text]
  (let [bytes (.getBytes (str text) "UTF-8")]
    (http-body/sink-write! sink bytes 0 (alength bytes))))

(defn- peer-disconnect? [error]
  (loop [error error]
    (when error
      (let [data (ex-data error)]
        (or (= :connection-reset (:jolt.net/kind data))
            (= :teensyp.server/socket-closed (:err data))
            (recur (ex-cause error)))))))

;; Bridges the core.async channel jolt.datastar.core/wrap-datastar hands back
;; for an SSE request into jolt-http's synchronous Sink-writing StreamableBody
;; contract, with a bounded heartbeat so shutdown (stopped?) is observed
;; promptly instead of blocking forever on an idle channel read.
(defrecord ChannelBody [ch stopped?]
  http-body/StreamableBody
  (write-body-to-sink [_ _response sink]
    (try
      (loop []
        (when-not @stopped?
          (let [timeout (async/timeout heartbeat-ms)
                [event port] (async/alts!! [ch timeout])]
            (cond
              (= port timeout)
              (do (write-text! sink ": keepalive\n\n")
                  (http-body/sink-flush! sink)
                  (recur))

              (some? event)
              (do (write-text! sink event)
                  (http-body/sink-flush! sink)
                  (recur))

              :else nil))))
      (catch Throwable error
        (when-not (peer-disconnect? error) (throw error)))
      (finally (async/close! ch)))))

(defn- fragment-handler [state]
  (fn [_request] {:status 200 :body (view/render-live @(:ratom state))}))

(defn- sse-response [state request]
  (let [handler (datastar/wrap-datastar (fragment-handler state)
                                        {:rate-limit-ms 100})
        response (handler request)]
    (update response :body ->ChannelBody (:stopped? state))))

(defn get-page
  "GET /workbench: the full page for ordinary navigation, or (when the
  Datastar SSE query flag is present) a live-updating stream scoped to
  `#workbench-live` and driven entirely by this route's own ratom."
  [state request]
  (if (sse-request? request)
    (sse-response state request)
    {:status 200 :headers page-headers
     :body (view/render-page @(:ratom state))}))

(defn- concat-chunks [chunks total]
  (let [out (byte-array total)]
    (loop [remaining chunks offset 0]
      (if-let [chunk (first remaining)]
        (let [n (alength chunk)]
          (dotimes [i n] (aset out (+ offset i) (aget chunk i)))
          (recur (next remaining) (+ offset n)))
        out))))

(defn- bounded-body-bytes
  "Read `body` up to `limit` bytes without slurping an unbounded stream."
  [body limit]
  (cond
    (nil? body) (byte-array 0)
    (string? body) (.getBytes ^String body "UTF-8")
    (bytes? body) body
    (satisfies? http-body/RequestBody body)
    (loop [chunks [] total 0]
      (if-let [chunk (http-body/body-recv body)]
        (let [total (+ total (alength chunk))]
          (if (> total limit)
            (concat-chunks chunks (- total (alength chunk)))
            (recur (conj chunks chunk) total)))
        (concat-chunks chunks total)))
    :else (byte-array 0)))

(defn- decode-form-value [s]
  (try (URLDecoder/decode (str s) "UTF-8") (catch Throwable _ "")))

(defn- prompt-from-form-body [body-string]
  (some (fn [pair]
          (let [i (str/index-of pair "=")]
            (when (and i (= "prompt" (decode-form-value (subs pair 0 i))))
              (decode-form-value (subs pair (inc i))))))
        (str/split (or body-string "") #"&")))

(defn post-run!
  "POST /workbench: read a bounded form body, start a run for its `prompt`
  field against `adapter` (a no-op when blank), and redirect back to the page.
  Never throws on a malformed or oversized body — the run simply does not
  start."
  [state adapter request]
  (try
    (let [bytes (bounded-body-bytes (:body request) max-request-body-bytes)
          prompt (prompt-from-form-body (String. bytes "UTF-8"))]
      (when-not (str/blank? prompt)
        (start-run! state adapter prompt)))
    (catch Throwable _ nil))
  {:status 303 :headers redirect-headers :body ""})

(defn asset-response []
  {:status 200 :headers script-headers :body view/enhancement-script})
