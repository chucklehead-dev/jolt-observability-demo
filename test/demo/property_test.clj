(ns demo.property-test
  (:require [clojure.data.json :as json]
            [clojure.string :as str]
            [demo.main :as demo]
            [hegel.core :as h]
            [hegel.generator :as g]
            [jdbc.core :as jdbc]
            [otel.viewer :as viewer]))

(defn- fail! [origin message data]
  (throw (ex-info message (assoc data :hegel/origin origin))))

(defn- check! [condition origin message data]
  (when-not condition (fail! origin message data)))

(defn- app-with [overrides]
  (demo/app-context
   (merge {:summary-fn (constantly {:traceCount 0 :spanCount 0
                                    :logCount 0 :errorCount 0})
           :traces-fn (constantly [])
           :trace-fn (fn [id] {:traceId id :spans [] :logs []})
           :logs-fn (constantly [])
           :work-fn (fn [_] {:upstream {:ok true}})}
          overrides)))

(defn- decode [response]
  (json/read-str (:body response) :key-fn keyword))

(defn- trace-id-boundary-property []
  (h/run-test!
   {:name "demo trace-id route boundary"
    :database "" :verbosity :quiet :derandomize? true :test-cases 180}
   (fn [_]
     (let [valid-id (h/draw! (g/string {:min-size 32 :max-size 32
                                        :alphabet "0123456789abcdef"}))
           arbitrary (h/draw! (g/string {:max-size 48}))
           seen (atom [])
           handler (demo/raw-handler
                    (app-with {:trace-fn (fn [id]
                                           (swap! seen conj id)
                                           {:traceId id :spans [] :logs []})}))
           valid-response (handler {:request-method :get
                                    :uri (str "/api/traces/" valid-id)})
           arbitrary-response (handler {:request-method :get
                                        :uri (str "/api/traces/" arbitrary)})
           arbitrary-valid? (boolean (re-matches #"[0-9a-f]{32}" arbitrary))]
       (check! (= 200 (:status valid-response))
               "demo/valid-trace-id" "valid trace id was rejected"
               {:trace-id valid-id})
       (check! (= valid-id (:traceId (decode valid-response)))
               "demo/trace-id-roundtrip" "validated trace id changed"
               {:trace-id valid-id})
       (check! (= (if arbitrary-valid? 200 400) (:status arbitrary-response))
               "demo/invalid-trace-id" "trace-id validator disagreed with contract"
               {:candidate arbitrary})
       (check! (= (cond-> [valid-id] arbitrary-valid? (conj arbitrary)) @seen)
               "demo/trace-query-boundary"
               "invalid trace id crossed the query callback boundary"
               {:candidate arbitrary :seen @seen})))))

(defn- route-method-property []
  (h/run-test!
   {:name "demo route and method status matrix"
    :database "" :verbosity :quiet :derandomize? true :test-cases 120}
   (fn [_]
     (let [method (h/draw! (g/sampled-from [:get :post :put :delete :patch]))
           [path get-status]
           (h/draw! (g/sampled-from [["/" 200]
                                     ["/traces/0123456789abcdef0123456789abcdef" 200]
                                     ["/api/summary" 200]
                                     ["/api/traces" 200]
                                     ["/api/logs" 200]
                                     ["/work" 200]
                                     ["/upstream" 200]
                                     ["/missing" 404]
                                     ["/api/traces/not-hex" 400]]))
           response ((demo/raw-handler (app-with {}))
                     {:request-method method :uri path})
           expected (cond
                      (= method :get) get-status
                      (and (= method :post) (= path "/work")) 303
                      :else 405)]
       (check! (= expected (:status response))
               "demo/route-method-status" "route status matrix changed"
               {:method method :path path :expected expected
                :actual (:status response)})
       (check! (= "no-store" (get-in response [:headers "Cache-Control"]))
               "demo/cache-control" "response became cacheable"
               {:method method :path path})
       (let [html? (or (and (= method :get) (= path "/"))
                       (and (= method :get) (str/starts-with? path "/traces/"))
                       (and (= method :post) (= path "/work")))]
         (check! (= (if html? "text/html; charset=UTF-8"
                        "application/json; charset=UTF-8")
                    (get-in response [:headers "Content-Type"]))
                 "demo/content-type" "route returned the wrong representation"
                 {:method method :path path :html? html?}))))))

(defn- escaped-viewer-property []
  (h/run-test!
   {:name "demo viewer escapes telemetry"
    :database "" :verbosity :quiet :derandomize? true :test-cases 120}
   (fn [_]
     (let [suffix (h/draw! (g/string {:max-size 64}))
           hostile (str "</span><script>" suffix "</script>")
           html (viewer/render-fragment
                  {:summary {} :traces []
                   :logs [{:timestamp "now" :severity "WARN" :body hostile}]})]
       (check! (not (str/includes? html "<script>"))
               "demo/viewer-script-injection"
               "telemetry became active viewer markup" {:suffix suffix})
       (check! (str/includes? html "&lt;script&gt;")
               "demo/viewer-escaped-sentinel"
               "escaped telemetry sentinel disappeared" {:suffix suffix})))))

(defn- bounded-json-property []
  (h/run-test!
   {:name "demo bounded JSON response"
    :database "" :verbosity :quiet :derandomize? true :test-cases 140}
   (fn [_]
     (let [values (h/draw! (g/vector {:max-size 100}
                                     (g/string {:max-size 64})))
           rows (mapv (fn [value] {:traceId value}) values)
           response ((demo/raw-handler
                      (app-with {:traces-fn (constantly rows)}))
                     {:request-method :get :uri "/api/traces"})
           body (:body response)]
       (check! (= rows (decode response))
               "demo/json-roundtrip" "generated API data did not round-trip" {})
       (check! (<= (count rows) 100)
               "demo/row-bound" "API fixture exceeded the SQL response bound"
               {:rows (count rows)})
       (check! (< (count body) 150000)
               "demo/body-bound" "bounded rows produced an unexpectedly large body"
               {:rows (count rows) :characters (count body)})
       (check! (= "no-store" (get-in response [:headers "Cache-Control"]))
               "demo/bounded-cache-control" "generated response became cacheable"
               {})))))

(defn- bounded-query-property []
  (h/run-test!
   {:name "demo bounded parameterized queries"
    :database "" :verbosity :quiet :derandomize? true :test-cases 100}
   (fn [_]
     (let [trace-id (h/draw! (g/string {:min-size 32 :max-size 32
                                        :alphabet "0123456789abcdef"}))
           queries (atom [])]
       (with-redefs [jdbc/fetch (fn [_ query]
                                  (swap! queries conj query)
                                  [])]
         (demo/query-traces {})
         (demo/query-logs {})
         (demo/query-trace {} trace-id))
       (let [[traces-query logs-query span-query log-query] @queries]
         (check! (and (str/includes? traces-query "LIMIT 100")
                      (str/includes? logs-query "LIMIT 100"))
                 "demo/query-row-limit" "list query lost its fixed row limit"
                 {})
         (check! (and (str/includes? (first span-query) "LIMIT 1000")
                      (str/includes? (first log-query) "LIMIT 500"))
                 "demo/detail-query-row-limit"
                 "trace detail query lost its fixed row limit" {})
         (check! (= [trace-id trace-id]
                    [(second span-query) (second log-query)])
                 "demo/query-parameter" "trace id was not bound as a parameter"
                 {:trace-id trace-id :queries [span-query log-query]})
         (check! (and (not (str/includes? (first span-query) trace-id))
                      (not (str/includes? (first log-query) trace-id)))
                 "demo/query-interpolation" "trace id was interpolated into SQL"
                 {:trace-id trace-id}))))))

(defn run-properties! []
  [{:label "trace-id boundary" :result (trace-id-boundary-property)}
   {:label "route/method matrix" :result (route-method-property)}
   {:label "viewer escaping" :result (escaped-viewer-property)}
   {:label "bounded JSON" :result (bounded-json-property)}
   {:label "bounded parameterized queries" :result (bounded-query-property)}])
