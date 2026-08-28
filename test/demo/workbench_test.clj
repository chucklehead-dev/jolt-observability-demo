(ns demo.workbench-test
  (:require [clojure.data.json :as json]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [demo.main :as demo]
            [demo.workbench :as workbench]
            [demo.workbench-fixture :as fixture]
            [demo.workbench-view :as view]
            [jolt.http-client :as http-client]
            [jolt.http.body :as http-body]
            [otel.sdk :as sdk]))

(defn- decode [response]
  (json/read-str (:body response) :key-fn keyword))

(defn- response-header [response name]
  (some (fn [[k v]] (when (= (str/lower-case name) (str/lower-case (str k))) v))
        (:headers response)))

(deftest workbench-routes-are-recognized-and-mounted
  (is (= "/workbench" (demo/route-for "/workbench")))
  (is (= "/assets/workbench.js" (demo/route-for "/assets/workbench.js"))))

(deftest workbench-state-machine-progresses-deterministically-to-a-terminal-state
  (let [adapter (fixture/adapter)
        run (workbench/new-run 1 "  diagnose the stale dashboard  " adapter)
        started (workbench/apply-start {:current nil :history []} run)
        stages (fn [sv] (mapv :stage (:events (:current sv))))]
    (is (= "diagnose the stale dashboard" (:prompt (:current started)))
        "the prompt is trimmed")
    (is (= :running (:status (:current started))))
    (is (= [] (stages started)))
    (let [final (loop [sv started]
                  (if (seq (:pending (:current sv)))
                    (recur (workbench/apply-reveal sv 1))
                    sv))]
      (is (= [:run-opened :turn-started :model-requested :tool-dispatched
              :tool-completed :controller-decided :turn-started
              :model-requested :tool-dispatched :tool-completed :run-closed]
             (stages final))
          "events reveal in the same fixed order the adapter returned them")
      (is (= :closed (:status (:current final))))
      (is (= [] (:pending (:current final))))
      (is (str/includes? (:response (:current final)) "square root of -1"))
      (is (= 2 (:turns (:capture (:current final)))))
      (testing "a run at its terminal state ignores further reveals"
        (is (= final (workbench/apply-reveal final 1))))
      (testing "a reveal for a superseded run id is a no-op"
        (is (= started (workbench/apply-reveal started 999)))))))

(deftest workbench-history-is-bounded-and-archives-superseded-runs
  (let [runs (map #(hash-map :id % :prompt (str "p" %) :status :closed
                             :response "r" :events [] :pending [])
                  (range 1 9))
        final (reduce workbench/apply-start {:current nil :history []} runs)]
    (is (= 8 (:id (:current final))))
    (is (= [7 6 5 4 3] (map :id (:history final)))
        "history keeps the 5 most recently superseded runs, newest first")))

(deftest workbench-fixture-tells-the-stale-dashboard-story-without-a-hostname
  (let [{:keys [events response capture]}
        (fixture/run-script (fixture/adapter) "why is the dashboard stale?")]
    (is (= 11 (count events)))
    (is (= :run-opened (:stage (first events))))
    (is (= :run-closed (:stage (last events))))
    (is (str/includes? response "square root of -1"))
    (is (= 2 (:turns capture)))
    (is (true? (:controller-intervened capture)))
    (doseq [needle ["model-host" "127.0.0.1" "http://" "https://" ".example"
                    ":8000" ":11434"]]
      (is (not (str/includes? (pr-str events) needle))
          (str "events must not name a physical host (" needle ")"))
      (is (not (str/includes? response needle))
          (str "the response must not name a physical host (" needle ")")))))

(deftest workbench-view-escapes-and-bounds-untrusted-content
  (let [evil "<script>alert(1)</script>"
        long-prompt (str evil (apply str (repeat 3000 "x")))
        long-response (str evil (apply str (repeat 3000 "y")))
        run {:id 1 :prompt long-prompt :status :closed
             :events [{:stage :run-opened :detail evil}
                      {:stage :run-closed :detail "closed"}]
             :response long-response
             :capture {:model evil}}
        fragment (view/render-live {:current run :history []})
        page (view/render-page {:current run :history []})]
    (is (str/includes? fragment "&lt;script&gt;alert(1)&lt;/script&gt;"))
    (is (not (str/includes? fragment "<script>alert(1)</script>")))
    (is (str/includes? fragment "…") "oversized content is truncated")
    (is (<= (count fragment) 20000)
        "rendered fragment stays bounded despite oversized input")
    (is (= 1 (count (re-seq #"<script" page)))
        "the only real <script> tag is the static asset include")))

(deftest workbench-history-hides-the-response-of-an-interrupted-run
  (let [interrupted {:prompt "first" :status :running :response "should not leak"}
        html (view/render-live {:current nil :history [interrupted]})]
    (is (str/includes? html "interrupted before completion"))
    (is (not (str/includes? html "should not leak")))))

(deftest workbench-post-ignores-blank-prompts-and-bounds-oversized-bodies
  (let [state (workbench/state)
        adapter (fixture/adapter)
        blank-response (workbench/post-run! state adapter {:body "prompt=%20%20"})]
    (is (= 303 (:status blank-response)))
    (is (= "/workbench" (get-in blank-response [:headers "Location"])))
    (is (nil? (:current @(:ratom state))) "a blank prompt never starts a run")
    (let [huge (apply str (repeat 20000 "z"))
          response (workbench/post-run! state adapter {:body (str "prompt=" huge)})]
      (is (= 303 (:status response)))
      (is (some? (:current @(:ratom state))))
      (is (<= (count (:prompt (:current @(:ratom state)))) 2048)
          "an oversized body still yields a bounded stored prompt"))))

(deftest workbench-sse-stream-is-untraced-and-stops-cleanly-on-disconnect
  (let [app (demo/app-context {:workbench-state (workbench/state)})
        h (demo/handler app)
        response (h {:request-method :get :uri "/workbench"
                     :query-string "datastar-sse=true&datastar-selector=%23workbench-live"
                     :headers {}})
        sink (reify http-body/Sink
               (sink-write! [_ _ _ _]
                 (throw (ex-info "peer left" {:jolt.net/kind :connection-reset})))
               (sink-close! [_] nil))]
    (is (= 200 (:status response)))
    (is (= "text/event-stream; charset=utf-8"
           (get-in response [:headers "Content-Type"])))
    (is (nil? (http-body/write-body-to-sink (:body response) response sink))
        "a disconnected peer ends the stream instead of throwing")))

(def ^:private live-test-port (atom (+ 29200 (rand-int 400))))

(defn- next-live-test-port [] (swap! live-test-port inc))

(deftest workbench-route-isolation-creates-no-wrapper-spans
  (let [port (next-live-test-port)
        lifecycle (demo/start! {:port port :db-spec "chdb::memory:"})
        base (str "http://127.0.0.1:" port)]
    (try
      (let [page (http-client/get (str base "/workbench")
                                  {:conn-timeout 2000 :socket-timeout 5000
                                   :throw-exceptions false})
            post (http-client/post
                  (str base "/workbench")
                  {:headers {"Content-Type" "application/x-www-form-urlencoded"}
                   :body "prompt=diagnose+the+stale+dashboard"
                   :conn-timeout 2000 :socket-timeout 5000
                   :throw-exceptions false})
            asset (http-client/get (str base "/assets/workbench.js")
                                   {:conn-timeout 2000 :socket-timeout 5000
                                    :throw-exceptions false})]
        (is (= 200 (:status page)))
        (is (str/includes? (:body page) "Run workbench"))
        (is (= 303 (:status post)))
        (is (= "/workbench" (response-header post "Location")))
        (is (= 200 (:status asset)))
        (is (str/includes? (:body asset) "EventSource")))
      (Thread/sleep 500)
      (is (true? (sdk/force-flush! (:otel lifecycle))))
      (let [summary (decode (http-client/get (str base "/api/summary")
                                             {:conn-timeout 2000
                                              :socket-timeout 5000}))]
        (is (= 0 (:traceCount summary))
            "workbench GET/POST traffic creates no wrapper HTTP spans"))
      (finally (demo/stop! lifecycle)))))
