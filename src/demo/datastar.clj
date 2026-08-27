(ns demo.datastar
  "Bounded Datastar SSE streaming over jolt-http. The stream periodically
  renders a durable snapshot, so it also converges after external chDB writes
  or missed notifications."
  (:require [clojure.string :as str]
            [jolt.datastar.core :as datastar]
            [jolt.http.body :as http-body]))

(def default-options
  {:interval-ms 750
   ;; Keep this below jolt-http's default 5s stop deadline so an unchanged
   ;; stream observes a disconnected peer before shutdown expires.
   :heartbeat-ms 2000
   :max-streams 8})

(defn stream-state
  ([] (stream-state {}))
  ([options]
   {:options (merge default-options options)
    :active (atom 0)
    :stopped? (atom false)}))

(defn stop-streams!
  "Stop admitting streams and ask active streams to finish at their next
  bounded polling interval so HTTP server shutdown can drain."
  [state]
  (reset! (:stopped? state) true)
  nil)

(defn init-attributes
  "CSP-safe marker consumed by the external viewer enhancement script."
  []
  {"data-otel-live" "true"})

(defn sse-request?
  "True only for the exact Datastar SSE query flag. Selector and patch mode are
  deliberately not accepted from the request; the server owns both."
  [{:keys [query-string]}]
  (boolean
   (some #(= "datastar-sse=true" %)
         (str/split (or query-string "") #"&"))))

(defn- acquire! [{:keys [active stopped? options]}]
  (let [limit (:max-streams options)]
    (loop []
      (let [n @active]
        (cond
          @stopped? false
          (>= n limit) false
          (compare-and-set! active n (inc n)) true
          :else (recur))))))

(defn- write-text! [sink text]
  (let [bytes (.getBytes (str text) "UTF-8")]
    (http-body/sink-write! sink bytes 0 (alength bytes))))

(defrecord LiveBody [render state]
  http-body/StreamableBody
  (write-body-to-sink [_ _response sink]
    (let [{:keys [interval-ms heartbeat-ms]} (:options state)]
      (try
        (loop [previous nil heartbeat-at (System/currentTimeMillis)]
          (when-not @(:stopped? state)
            (let [html (render)
                  now (System/currentTimeMillis)
                  changed? (not= html previous)
                  heartbeat? (>= (- now heartbeat-at) heartbeat-ms)]
              (cond
                changed?
                (write-text! sink
                             (datastar/patch-elements-event html "#otel-live" "inner"))

                heartbeat?
                (write-text! sink ": keepalive\n\n"))
              (Thread/sleep interval-ms)
              (recur html (if (or changed? heartbeat?) now heartbeat-at)))))
        (finally
          (swap! (:active state) dec))))))

(defn stream-response
  "Return a fixed-selector Datastar event stream, or 503 when the bounded SSE
  capacity is full. `render` must return bounded, already-escaped HTML."
  [state render]
  (if (acquire! state)
    {:status 200
     :headers {"Content-Type" "text/event-stream; charset=utf-8"
               "Cache-Control" "no-store"
               "X-Accel-Buffering" "no"}
     :body (->LiveBody render state)}
    {:status 503
     :headers {"Content-Type" "text/plain; charset=UTF-8"
               "Cache-Control" "no-store"
               "Retry-After" "2"}
     :body "live viewer capacity reached"}))
