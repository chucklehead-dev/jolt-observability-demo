(ns demo.safe-hiccup
  "Strict data-only Hiccup contract and escaped serializer for previews."
  (:require [clojure.edn :as edn] [clojure.string :as str]))
(def ^:private tags #{:div :section :article :header :footer :main :h1 :h2 :h3 :p :pre :code
                      :ul :ol :li :dl :dt :dd :table :thead :tbody :tr :th :td :span :strong :em :small :br})
(def ^:private attrs #{:id :class :title :aria-label :role :colspan :rowspan})
(def ^:private reserved-ids #{"hiccup-preview" "plotje-preview" "otel-live"
                              "workbench-live"})
(def max-chars 16384)
(defn- fail! [s] (throw (ex-info s {:safe-hiccup/error true})))
(defn- esc [x] (-> (str x) (str/replace "&" "&amp;") (str/replace "<" "&lt;")
                   (str/replace ">" "&gt;") (str/replace "\"" "&quot;") (str/replace "'" "&#39;")))
(defn validate [root]
  (let [nodes (atom 0) text-size (atom 0)]
    (letfn [(walk [x depth]
              (when (> depth 12) (fail! "Hiccup exceeds depth 12"))
              (swap! nodes inc) (when (> @nodes 256) (fail! "Hiccup exceeds 256 nodes"))
              (cond
                (string? x) (do (swap! text-size + (count x))
                                (when (> (count x) 1000) (fail! "text node exceeds 1000 characters")) x)
                (number? x) x
                (vector? x)
                (let [tag (first x) with-attrs? (map? (second x))
                      amap (if with-attrs? (second x) {}) children (if with-attrs? (drop 2 x) (rest x))]
                  (when-not (tags tag) (fail! (str "tag " (pr-str tag) " is not allowed")))
                  (doseq [[k v] amap]
                    (when-not (attrs k) (fail! (str "attribute " (pr-str k) " is not allowed")))
                    (when-not (or (string? v) (number? v)) (fail! "attribute values must be strings or numbers"))
                    (when (> (count (str v)) 160) (fail! "attribute value is too long"))
                    (when (and (= :id k)
                               (or (reserved-ids (str v))
                                   (not (re-matches #"[A-Za-z][A-Za-z0-9_-]{0,63}"
                                                    (str v)))))
                      (fail! "id must be a short non-reserved HTML identifier")))
                  (into [tag amap] (map #(walk % (inc depth)) children)))
                :else (fail! "Hiccup nodes must be vectors, strings, or numbers")))]
      (let [out (walk root 0)] (when (> @text-size 12000) (fail! "total text is too large")) out))))
(defn parse [text]
  (let [text (str (or text ""))]
    (when (> (count text) max-chars) (fail! "Hiccup input is too large"))
    (try (validate (edn/read-string {:readers {} :default (fn [_ _] (fail! "tagged literals are not allowed"))} text))
         (catch clojure.lang.ExceptionInfo e (throw e)) (catch Throwable _ (fail! "Hiccup is not valid EDN")))))
(declare render)
(defn render [node]
  (cond
    (string? node) (esc node) (number? node) (str node)
    (vector? node) (let [[tag amap & children] node
                         as (apply str (for [[k v] (sort-by (comp name key) amap)] (str " " (name k) "=\"" (esc v) "\"")))]
                     (if (= tag :br) (str "<br" as ">")
                         (str "<" (name tag) as ">" (apply str (map render children)) "</" (name tag) ">")))))
(defn text->html [text] (render (parse text)))
