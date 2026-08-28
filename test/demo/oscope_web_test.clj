(ns demo.oscope-web-test
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is]]
            [demo.main :as demo]
            [oscope.sample :as sample]
            [oscope.ui.web :as web]))

(deftest demo-uses-the-published-oscope-adapter-without-a-copy
  (let [app (demo/app-context {})
        response ((demo/raw-handler app)
                  {:request-method :get :uri "/oscope"
                   :query-string "signal=logs&field=severity-text&window=6h&limit=5"})]
    (is (= 200 (:status response)))
    (is (str/includes? (:body response) "Severity Text in Logs"))
    (is (str/includes? (:body response) "Raw export is unavailable in sample mode"))
    (is (= 404 (:status ((demo/raw-handler app)
                         {:request-method :get :uri "/oscope/export"}))))
    (is (= "/oscope" (demo/route-for "/oscope")))
    (is (= "/oscope/export" (demo/route-for "/oscope/export")))))

(deftest configurable-mount-delegates-both-owned-routes-and-falls-through
  (let [seen (atom [])
        source {:load-command (fn [_ selection]
                                (sample/screen-for-selection selection))}
        mounted (web/handler source {:path "/admin/telemetry"})
        app (demo/app-context
             {:oscope-path "/admin/telemetry"
              :oscope-handler (fn [request]
                                (swap! seen conj (:uri request))
                                (mounted request))})
        h (demo/raw-handler app)]
    (is (= 200 (:status (h {:request-method :get
                            :uri "/admin/telemetry"}))))
    (is (= 404 (:status (h {:request-method :get
                            :uri "/admin/telemetry/export"}))))
    (is (= 404 (:status (h {:request-method :get :uri "/oscope"}))))
    (is (= ["/admin/telemetry" "/admin/telemetry/export"] @seen))
    (is (= "/admin/telemetry"
           (demo/route-for "/admin/telemetry" "/admin/telemetry")))
    (is (= "/admin/telemetry/export"
           (demo/route-for "/admin/telemetry/export" "/admin/telemetry")))))
