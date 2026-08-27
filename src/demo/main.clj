(ns demo.main
  (:require [clojure.data.json :as json]
            [clojure.string :as str]
            [db.jdbc]
            [jdbc.chdb]
            [jdbc.core :as jdbc]
            [jolt.http-client :as http-client]
            [jolt.http.server :as http-server]
            [otel.exporter.chdb :as chdb-export]
            [otel.logs :as logs]
            [otel.sdk :as sdk]
            [otel.trace :as trace]))

(def ^:private service-name "jolt-observability-demo")
(def ^:private json-headers {"Content-Type" "application/json; charset=UTF-8"
                             "Cache-Control" "no-store"})

(def ^:private dashboard
  "<!doctype html>
<html lang=\"en\"><head><meta charset=\"utf-8\"><meta name=\"viewport\" content=\"width=device-width,initial-scale=1\"><title>Jolt Observability</title>
<style>:root{color-scheme:dark;--bg:#0b1020;--panel:#151d31;--ink:#e8eefc;--muted:#92a0bd;--accent:#75e6c4;--bad:#ff7b86}*{box-sizing:border-box}body{margin:0;background:radial-gradient(circle at top,#172444,var(--bg) 45%);color:var(--ink);font:15px/1.5 system-ui,sans-serif}main{max-width:1100px;margin:auto;padding:42px 22px}h1{margin:0;font-size:clamp(2rem,5vw,4rem)}p{color:var(--muted)}button{background:var(--accent);border:0;border-radius:8px;padding:10px 16px;font-weight:700;cursor:pointer}.cards{display:grid;grid-template-columns:repeat(4,1fr);gap:12px;margin:28px 0}.card,section{background:var(--panel);border:1px solid #283552;border-radius:12px;padding:16px}.card b{display:block;font-size:2rem;color:var(--accent)}section{margin:14px 0;overflow:auto}table{border-collapse:collapse;width:100%;min-width:650px}th,td{text-align:left;padding:9px;border-bottom:1px solid #27324a}th{color:var(--muted)}.empty{color:var(--muted);padding:18px 4px}.error{color:var(--bad)}@media(max-width:650px){.cards{grid-template-columns:repeat(2,1fr)}}</style></head>
<body><main><h1>Jolt, observed.</h1><p>OpenTelemetry exported directly into embedded chDB. <button id=\"work\">Generate work</button></p><div class=\"cards\"><div class=\"card\"><span>Traces</span><b id=\"traceCount\">0</b></div><div class=\"card\"><span>Spans</span><b id=\"spanCount\">0</b></div><div class=\"card\"><span>Logs</span><b id=\"logCount\">0</b></div><div class=\"card\"><span>Errors</span><b id=\"errorCount\">0</b></div></div><section><h2>Recent traces</h2><div id=\"traces\" class=\"empty\">No traces yet. Generate work to begin.</div></section><section><h2>Recent logs</h2><div id=\"logs\" class=\"empty\">No logs yet. Generate work to begin.</div></section></main>
<script>const $=id=>document.getElementById(id);const text=v=>v==null?'':String(v);function table(rows,cols){if(!rows.length)return null;const t=document.createElement('table'),h=document.createElement('tr');for(const [k,label] of cols){const th=document.createElement('th');th.textContent=label;h.appendChild(th)}const head=document.createElement('thead');head.appendChild(h);t.appendChild(head);const body=document.createElement('tbody');for(const row of rows){const tr=document.createElement('tr');for(const [k] of cols){const td=document.createElement('td');td.textContent=text(row[k]);tr.appendChild(td)}body.appendChild(tr)}t.appendChild(body);return t}function show(id,rows,cols,empty){const el=$(id);el.replaceChildren();const t=table(rows,cols);if(t){el.className='';el.appendChild(t)}else{el.className='empty';el.textContent=empty}}async function get(path){const r=await fetch(path,{cache:'no-store'});if(!r.ok)throw new Error(path+' returned '+r.status);return r.json()}async function refresh(){try{const [s,t,l]=await Promise.all([get('/api/summary'),get('/api/traces'),get('/api/logs')]);for(const k of ['traceCount','spanCount','logCount','errorCount'])$(k).textContent=text(s[k]||0);show('traces',t,[['startedAt','Started'],['service','Service'],['rootSpan','Root span'],['spanCount','Spans'],['status','Status']],'No traces yet. Generate work to begin.');show('logs',l,[['timestamp','Time'],['severity','Severity'],['service','Service'],['body','Body'],['traceId','Trace']],'No logs yet. Generate work to begin.')}catch(e){$('logs').className='error';$('logs').textContent=e.message}}$('work').addEventListener('click',async()=>{try{await get('/work')}finally{await refresh()}});refresh();setInterval(refresh,2000);</script></body></html>")

(defn- json-response
  ([value] (json-response 200 value))
  ([status value] {:status status :headers json-headers :body (json/write-str value)}))

(defn- error-response [status message]
  (json-response status {:error message}))

;; chDB normalizes result labels to lower case, including camel-case aliases.
(defn- value-of [row k]
  (get row (keyword (str/lower-case (name k)))))

(defn query-summary [conn]
  (let [spans (first (jdbc/fetch conn
                       "SELECT uniqExactIf(TraceId, TraceId != '') AS traceCount,
                               count() AS spanCount,
                               countIf(StatusCode = 'error') AS errorCount
                          FROM otel_traces"))
        log-count (value-of (first (jdbc/fetch conn "SELECT count() AS logCount FROM otel_logs")) :logCount)]
    {:traceCount (or (value-of spans :traceCount) 0)
     :spanCount (or (value-of spans :spanCount) 0)
     :logCount (or log-count 0)
     :errorCount (or (value-of spans :errorCount) 0)}))

(defn query-traces [conn]
  (mapv (fn [row]
          {:traceId (value-of row :traceId)
           :startedAt (value-of row :startedAt)
           :durationNs (value-of row :durationNs)
           :service (value-of row :service)
           :rootSpan (value-of row :rootSpan)
           :spanCount (value-of row :spanCount)
           :status (value-of row :status)})
        (jdbc/fetch conn
          "SELECT TraceId AS traceId, min(Timestamp) AS startedAt,
                  max(Duration) AS durationNs,
                  argMin(ServiceName, Timestamp) AS service,
                  argMinIf(SpanName, Timestamp, ParentSpanId = '') AS rootSpan,
                  count() AS spanCount,
                  if(countIf(StatusCode = 'error') > 0, 'error', 'ok') AS status
             FROM otel_traces WHERE TraceId != '' GROUP BY TraceId
            ORDER BY startedAt DESC LIMIT 100")))

(defn- span-json [row]
  {:timestamp (value-of row :Timestamp) :traceId (value-of row :TraceId)
   :spanId (value-of row :SpanId) :parentSpanId (value-of row :ParentSpanId)
   :service (value-of row :ServiceName) :name (value-of row :SpanName)
   :kind (value-of row :SpanKind) :durationNs (value-of row :Duration)
   :status (value-of row :StatusCode) :statusMessage (value-of row :StatusMessage)
   :attributes (value-of row :SpanAttributes)})

(defn- log-json [row]
  {:timestamp (value-of row :Timestamp) :severity (value-of row :SeverityText)
   :service (value-of row :ServiceName) :body (value-of row :Body)
   :traceId (value-of row :TraceId) :spanId (value-of row :SpanId)
   :attributes (value-of row :LogAttributes)})

(defn query-trace [conn trace-id]
  {:traceId trace-id
   :spans (mapv span-json
                (jdbc/fetch conn
                  ["SELECT Timestamp, TraceId, SpanId, ParentSpanId, ServiceName,
                           SpanName, SpanKind, Duration, StatusCode, StatusMessage,
                           SpanAttributes FROM otel_traces
                      WHERE TraceId = ? ORDER BY Timestamp, SpanId" trace-id]))
   :logs (mapv log-json
               (jdbc/fetch conn
                 ["SELECT Timestamp, SeverityText, ServiceName, Body, TraceId,
                          SpanId, LogAttributes FROM otel_logs
                     WHERE TraceId = ? ORDER BY Timestamp, SpanId" trace-id]))})

(defn query-logs [conn]
  (mapv log-json
        (jdbc/fetch conn
          "SELECT Timestamp, SeverityText, ServiceName, Body, TraceId, SpanId,
                  LogAttributes FROM otel_logs ORDER BY Timestamp DESC LIMIT 100")))

(defn- trace-id-path [path]
  (second (re-matches #"/api/traces/([0-9a-f]{32})" path)))

(defn route-for [path]
  (cond (= path "/") "/"
        (= path "/api/summary") "/api/summary"
        (= path "/api/traces") "/api/traces"
        (str/starts-with? path "/api/traces/") "/api/traces/:trace-id"
        (= path "/api/logs") "/api/logs"
        (= path "/work") "/work"
        (= path "/upstream") "/upstream"
        :else "/*"))

(defn- real-work! [{:keys [port tracer logger]}]
  (logs/emit! logger {:body "calling loopback upstream" :severity :info
                      :attributes {:http.route "/work"}})
  (trace/with-span [client-span tracer "HTTP GET /upstream"
                    {:kind :client
                     :attributes {:http.request.method "GET"
                                  :http.route "/upstream"
                                  :server.address "127.0.0.1"
                                  :server.port port}}]
    (let [response (http-client/get (str "http://127.0.0.1:" port "/upstream")
                                    {:conn-timeout 2000 :socket-timeout 5000
                                     :throw-exceptions false})]
      (trace/set-attribute! client-span :http.response.status_code (:status response))
      (if (= 200 (:status response))
        (do (trace/set-status! client-span :ok)
            (logs/emit! logger {:body "loopback upstream completed" :severity :info})
            {:upstream (json/read-str (:body response) :key-fn keyword)})
        (throw (ex-info "loopback upstream failed" {:status (:status response)}))))))

(defn app-context
  "Build the handler context. Query and work functions are injectable so pure
  Ring tests need neither native state nor a live socket."
  [{:keys [connection port tracer logger summary-fn traces-fn trace-fn logs-fn work-fn]
    :or {port 8080}}]
  {:connection connection :port port
   :tracer (or tracer (sdk/tracer "demo.http"))
   :logger (or logger (sdk/logger "demo.http"))
   :summary-fn (or summary-fn #(query-summary connection))
   :traces-fn (or traces-fn #(query-traces connection))
   :trace-fn (or trace-fn #(query-trace connection %))
   :logs-fn (or logs-fn #(query-logs connection))
   :work-fn (or work-fn real-work!)})

(defn raw-handler [{:keys [summary-fn traces-fn trace-fn logs-fn work-fn logger] :as app}]
  (fn [{:keys [request-method uri]}]
    (if (not= :get request-method)
      (error-response 405 "method not allowed")
      (cond
        (= uri "/") {:status 200
                     :headers {"Content-Type" "text/html; charset=UTF-8"
                               "Cache-Control" "no-store"}
                     :body dashboard}
        (= uri "/api/summary") (json-response (summary-fn))
        (= uri "/api/traces") (json-response (traces-fn))
        (= uri "/api/logs") (json-response (logs-fn))
        (= uri "/upstream") (do
                                (logs/emit! logger {:body "upstream served" :severity :info})
                                (json-response {:ok true :source "loopback"}))
        (= uri "/work") (try
                           (json-response (merge {:ok true} (work-fn app)))
                           (catch Throwable e
                             (logs/emit! logger {:body (str "work failed: " (ex-message e))
                                                :severity :error})
                             (error-response 502 "upstream request failed")))
        (str/starts-with? uri "/api/traces/")
        (if-let [trace-id (trace-id-path uri)]
          (json-response (trace-fn trace-id))
          (error-response 400 "trace id must be 32 lowercase hex characters"))
        :else (error-response 404 "not found")))))

(defn handler [app]
  (let [dispatch (raw-handler app) tracer (:tracer app)]
    (fn [{:keys [request-method uri] :as request}]
      (let [route (route-for uri)
            method (str/upper-case (name (or request-method :unknown)))]
        (trace/with-span [span tracer (str "HTTP " method " " route)
                          {:kind :server
                           :attributes {:http.request.method method
                                        :http.route route :url.path uri}}]
          (let [response (dispatch request) status (:status response)]
            (trace/set-attribute! span :http.response.status_code status)
            (when (>= status 500)
              (trace/set-status! span :error (str "HTTP " status)))
            response))))))

(defn- env-port []
  (let [raw (System/getenv "DEMO_PORT")]
    (if (str/blank? raw) 8080 (parse-long raw))))

(defn start!
  "Start database, batched OTel SDK, and HTTP server. The returned map has an
  idempotent :stop! function which enforces server, SDK, connection shutdown."
  ([] (start! {}))
  ([{:keys [port db-spec] :or {port (env-port)}}]
   (let [spec (or db-spec (System/getenv "DEMO_CHDB_SPEC") "chdb::memory:")
         conn (jdbc/connection spec)]
     (try
       (let [exporter (chdb-export/exporter {:connection conn
                                             :signals #{:spans :metrics :logs}})
             otel (sdk/init! {:service-name service-name :exporter exporter
                              :processor :batch :metrics? false :logs? true
                              :bridge-logging? false})]
         (try
           (let [app (app-context {:connection conn :port port})
                 server (http-server/run-server (handler app) :port port
                                                :server-name "127.0.0.1"
                                                :reuse-address? true)
                 stopped? (atom false)]
             {:port port :connection conn :otel otel :server server :app app
              :stop! (fn []
                       (when (compare-and-set! stopped? false true)
                         (http-server/stop-server server)
                         (sdk/shutdown! otel)
                         (.close conn)))})
           (catch Throwable e
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
