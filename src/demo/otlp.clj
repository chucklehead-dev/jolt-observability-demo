(ns demo.otlp
  "Bounded host adapter for the transport-neutral OTLP/HTTP receiver."
  (:require [clojure.data.json :as json]
            [jolt.http.body :as http-body]
            [otel.otlp.http-receiver :as receiver]))

(def max-body-bytes
  "One MiB keeps the embedded demo's request and decoder allocation bounded."
  (* 1024 1024))

(def max-concurrency
  "One request may parse/export at a time because all signals share one
  application-owned embedded chDB connection."
  1)

(defn- concat-chunks [chunks total]
  (let [out (byte-array total)]
    (loop [remaining chunks offset 0]
      (if-let [chunk (first remaining)]
        (let [n (alength chunk)]
          (dotimes [i n]
            (aset out (+ offset i) (aget chunk i)))
          (recur (next remaining) (+ offset n)))
        out))))

(defn- bounded-stream-bytes [body limit]
  (loop [chunks [] total 0]
    (if-let [chunk (http-body/body-recv body)]
      (let [actual (+ total (alength chunk))]
        (when (> actual limit)
          (throw (receiver/body-too-large limit actual)))
        (recur (conj chunks chunk) actual))
      (concat-chunks chunks total))))

(defn- bounded-bytes [body limit]
  (let [encoded
        (cond
          (string? body) (.getBytes body "UTF-8")
          (bytes? body) body
          (satisfies? http-body/RequestBody body)
          (bounded-stream-bytes body limit)
          :else (throw (ex-info "unsupported OTLP Ring request body"
                                {:body-type (type body)})))
        actual (alength encoded)]
    (when (> actual limit)
      (throw (receiver/body-too-large limit actual)))
    encoded))

(defn parse-json-body
  "Parse one Ring request body with data.json and report the actual consumed
  UTF-8 byte count required by `otel.otlp.http-receiver`. Streaming request
  bodies stop at the first chunk that crosses `limit`."
  [request limit]
  (let [encoded (bounded-bytes (:body request) limit)]
    {:value (json/read-str (String. encoded "UTF-8"))
     :encoded-bytes (alength encoded)}))

(defn wrap-close-rejected-bodies
  "Close every rejected OTLP request so jolt-http releases any unread bounded
  request-body producer. Successful OTLP responses remain reusable."
  [receive]
  (fn [request]
    (let [{:keys [status] :as response} (receive request)]
      ;; jolt-http's bounded request-body producer is released when the
      ;; connection closes. Receiver failures can return before consuming the
      ;; body (Content-Length policy and concurrency rejection) or while bytes
      ;; remain (the measured streaming cap), so such connections must not be
      ;; reused. Closing also prevents the producer from remaining parked on
      ;; its bounded channel after the handler has returned.
      (if (<= 200 status 299)
        response
        (assoc-in response [:headers "Connection"] "close")))))

(defn handler [exporter]
  (wrap-close-rejected-bodies
   (receiver/handler {:parse-body parse-json-body
                      :exporter exporter
                      :max-body-bytes max-body-bytes
                      :max-concurrency max-concurrency})))
