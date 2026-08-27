(ns otel.viewer
  "OpenTelemetry HTML renderer with a zero-JavaScript baseline.

  Hosts supply already-bounded data and may render either a complete document
  or an embeddable fragment. The renderer owns no database, server, SDK, or
  route lifecycle."
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            ;; Selmer eagerly touches java.time and MessageDigest. These
            ;; side-effect requires install Jolt's host shims before it loads.
            [jolt.crypto]
            [jolt.time]
            [selmer.parser :as selmer]
            [selmer.util :as selmer-util]))

(def ^:private max-render-depth 64)
(def ^:private trace-id-pattern #"[0-9a-f]{32}")
(def ^:private fragment-template
  (delay (slurp (io/resource "otel/viewer/fragment.html"))))
(def ^:private page-template
  (delay (slurp (io/resource "otel/viewer/page.html"))))
(def ^:private live-template
  (delay (slurp (io/resource "otel/viewer/live.html"))))
(def ^:private stylesheet
  (delay (slurp (io/resource "otel/viewer/viewer.css"))))
(def ^:private enhancement
  (delay (slurp (io/resource "otel/viewer/viewer.js"))))
(def ^:private datastar-client
  (delay (slurp (io/resource "otel/viewer/datastar.js"))))

(defn styles
  "Scoped viewer CSS for a host that embeds `render-fragment`."
  []
  @stylesheet)

(defn enhancement-script
  "Optional progressive enhancement for opening trace links in a native dialog.
  Hosts may serve this as a static JavaScript response; fragments work without it."
  []
  @enhancement)

(defn datastar-client-script
  "Vendored Datastar client used by the optional live-region enhancement."
  []
  @datastar-client)

(defn- text [value]
  (if (nil? value) "" (str value)))

(defn- status-class [status]
  (if (= "error" (str/lower-case (text status))) "error" "ok"))

(defn- normalize-base-path [path]
  (let [path (str/replace (text path) #"/+$" "")]
    (cond
      (str/blank? path) ""
      (str/starts-with? path "/") path
      :else (str "/" path))))

(defn- mounted-path [base suffix]
  (str (normalize-base-path base) suffix))

(defn- duration-label [nanoseconds]
  (let [n (double (or nanoseconds 0))]
    (cond
      (< n 1000.0) (format "%.0f ns" n)
      (< n 1000000.0) (format "%.1f µs" (/ n 1000.0))
      (< n 1000000000.0) (format "%.2f ms" (/ n 1000000.0))
      :else (format "%.2f s" (/ n 1000000000.0)))))

(defn- attribute-rows [attributes]
  (cond
    (map? attributes)
    (mapv (fn [[k v]] {:name (text k) :value (text v)})
          (sort-by (comp str key) attributes))

    (str/blank? (text attributes)) []
    :else [{:name "Attributes" :value (text attributes)}]))

(defn- flatten-tree
  ([forest] (flatten-tree forest 0))
  ([forest depth]
   (if (> depth max-render-depth)
     []
     (into []
           (mapcat (fn [span]
                     (cons (assoc span :depth depth)
                           (flatten-tree (:children span) (inc depth)))))
           forest))))

(defn- with-timeline [spans]
  (let [start-of (fn [span]
                   (let [n (:timestampUnixNano span)]
                     (if (number? n) n 0)))
        starts (map start-of spans)
        origin (if (seq starts) (apply min starts) 0)
        finish (reduce (fn [end span]
                         (max end (+ (start-of span)
                                     (max 0 (or (:durationNs span) 0)))))
                       (inc origin) spans)
        extent (double (max 1 (- finish origin)))]
    (mapv (fn [span]
            (let [start (double (- (start-of span) origin))
                  duration (double (max 0 (or (:durationNs span) 0)))]
              (assoc span
                     :startPercent (format "%.4f" (* 100.0 (/ start extent)))
                     :widthPercent (format "%.4f" (* 100.0 (/ duration extent))))))
          spans)))

(defn- span-view [span]
  {:id (text (:spanId span))
   :parentId (text (:parentSpanId span))
   :name (text (or (:name span) "(unnamed span)"))
   :kind (text (:kind span))
   :depth (min max-render-depth (max 0 (or (:depth span) 0)))
   :duration (duration-label (:durationNs span))
   :startPercent (:startPercent span)
   :widthPercent (:widthPercent span)
   :error (= "error" (str/lower-case (text (:status span))))
   :statusMessage (text (:statusMessage span))
   :open (zero? (or (:depth span) 0))
   :attributes (attribute-rows (:attributes span))})

(defn- log-view [log]
  {:timestamp (text (:timestamp log))
   :severity (text (:severity log))
   :body (text (:body log))})

(defn- index-model [{:keys [title base-path work-path enhancement-path live-attributes
                            summary traces logs]}]
  (let [base (normalize-base-path base-path)
        trace-views
        (into []
              (comp
               (filter #(re-matches trace-id-pattern (text (:traceId %))))
               (map (fn [trace]
                      {:href (mounted-path base (str "/traces/" (text (:traceId trace))))
                       :rootName (text (or (:rootSpan trace) "(root span)"))
                       :service (text (:service trace))
                       :startedAt (text (:startedAt trace))
                       :spanCount (or (:spanCount trace) 0)
                       :status (text (:status trace))
                       :statusClass (status-class (:status trace))})))
              traces)
        log-views (mapv log-view logs)]
    {:title (text (or title "Jolt Observability"))
     :homePath (if (str/blank? base) "/" base)
     :workPath (when work-path (mounted-path base work-path))
     :enhanced (boolean enhancement-path)
     :enhancementPath (when enhancement-path
                        (mounted-path base enhancement-path))
     :streaming (boolean live-attributes)
     :datastarSignals (text (:data-signals live-attributes))
     :datastarInit (text (:data-init live-attributes))
     :datastarOnline (text (get live-attributes "data-on:online__window"))
     :stats [{:label "Traces" :value (or (:traceCount summary) 0)}
             {:label "Spans" :value (or (:spanCount summary) 0)}
             {:label "Logs" :value (or (:logCount summary) 0)}
             {:label "Errors" :value (or (:errorCount summary) 0)}]
     :hasTraces (boolean (seq trace-views))
     :traces trace-views
     :hasLogs (boolean (seq log-views))
     :logs log-views}))

(defn- trace-model [{:keys [title base-path work-path trace]}]
  (let [flat (-> (:spanTree trace) flatten-tree with-timeline)
        root (first flat)
        status (if (some #(= "error" (str/lower-case (text (:status %)))) flat)
                 "error" "ok")
        base (normalize-base-path base-path)]
    {:title (text (or title "Trace detail"))
     :traceView true
     :homePath (if (str/blank? base) "/" base)
     :workPath (when work-path (mounted-path base work-path))
     :trace {:id (text (:traceId trace))
             :rootName (text (or (:name root) "Trace detail"))
             :status status
             :statusClass (status-class status)
             :hasSpans (boolean (seq flat))
             :spans (mapv span-view flat)
             :hasLogs (boolean (seq (:logs trace)))
             :logs (mapv log-view (:logs trace))}}))

(defn render-fragment
  "Render embeddable viewer markup. Supply either index data or `:trace` detail.
  `:base-path` defaults to the site root; `:work-path` is optional."
  [model]
  (selmer-util/with-escaping
    (if (:trace model)
      (selmer/render @fragment-template (trace-model model))
      (let [page-model (index-model model)
            live-content (selmer/render @live-template page-model)]
        (selmer/render @fragment-template
                       (assoc page-model :liveContent [:safe live-content]))))))

(defn render-live-content
  "Render the bounded stats, traces, and logs inside `#otel-live`. This is the
  server-rendered payload used for Datastar patches and contains no scripts."
  [model]
  (selmer-util/with-escaping
    (selmer/render @live-template (index-model model))))

(defn render-page
  "Render a complete standalone HTML document from the same model accepted by
  `render-fragment`."
  [model]
  (let [title (text (or (:title model) "Jolt Observability"))
        fragment (render-fragment model)
        base (normalize-base-path (:base-path model))]
    (selmer-util/with-escaping
      (selmer/render @page-template
                     {:title title
                      :styles [:safe @stylesheet]
                      :content [:safe fragment]
                      :datastarPath (when-let [path (:datastar-path model)]
                                      (mounted-path base path))
                      :enhancementPath (when-let [path (:enhancement-path model)]
                                         (mounted-path base path))}))))
