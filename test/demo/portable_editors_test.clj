(ns demo.portable-editors-test
  (:require [clojure.test :refer [deftest is]] [clojure.string :as str]
            [demo.plotje-spec :as spec] [demo.plotje-portable-editor :as plot]
            [demo.safe-hiccup :as safe] [demo.safe-hiccup-editor :as hiccup]))
(deftest plotje-contract-and-handler
  (is (= :line (get-in (spec/parse-spec spec/default-spec-text) [:layers 0 :mark])))
  (is (thrown? clojure.lang.ExceptionInfo
               (spec/validate-spec {:title "" :data [{:x 1 :y 2}]
                                    :layers [{:mark :line :x :x :y :y}]})))
  (is (str/includes? (:body (plot/handler {:request-method :get :uri "/plotje-editor"})) "<svg"))
  (is (str/includes? (:body (plot/handler {:request-method :post :uri "/plotje-editor/preview" :body "spec=%7B%7D"})) "Spec error")))
(deftest safe-hiccup-contract
  (is (= "<p class=\"x\">&lt;b&gt;</p>" (safe/text->html "[:p {:class \"x\"} \"<b>\"]")))
  (doseq [x ["[:script {} \"x\"]" "[:p {:onclick \"x\"} \"x\"]" "[:a {:href \"https://x\"} \"x\"]" "[:p {} #=(System/exit 0)]"]]
    (is (thrown? clojure.lang.ExceptionInfo (safe/parse x))))
  (is (thrown? clojure.lang.ExceptionInfo
               (safe/parse "[:div {:id \"hiccup-preview\"} \"x\"]")))
  (is (str/includes? (:body (hiccup/handler {:request-method :get :uri "/hiccup-editor"})) "Telemetry note")))
