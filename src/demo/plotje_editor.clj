(ns demo.plotje-editor
  "A standalone, bounded JVM Plotje chart editor surface.

  The accepted EDN is deliberately a small data vocabulary rather than
  executable Clojure. Plotje still owns the grammar-of-graphics construction
  and server-side SVG rendering. `handler` is Ring-shaped and can be mounted
  by the demo without sharing mutable state with the workbench. This namespace
  is JVM-only because upstream Plotje loads Tablecloth and Membrane; the
  Jolt-compatible subset renderer lives in `demo.plotje-portable`."
  (:require [clojure.edn :as edn]
            [clojure.string :as str]
            [demo.plotje-spec :as plotje-spec]
            [scicloj.plotje.api :as pj]
            [scicloj.plotje.render.svg :as plotje-svg])
  (:import [java.io ByteArrayOutputStream InputStream]
           [java.net URLDecoder]))

(def ^:private max-body-bytes 40000)
(def ^:private max-spec-chars plotje-spec/max-spec-chars)
(def ^:private max-rows 512)
(def ^:private max-columns 16)
(def ^:private max-layers 4)
(def ^:private max-text 160)
(def ^:private allowed-top-keys #{:title :x-label :y-label :width :height
                                  :data :layers})
(def ^:private allowed-layer-keys #{:mark :x :y :color})
(def ^:private allowed-marks #{:line :point :bar})

(def telemetry-specs
  "Small, editable examples using the same spec vocabulary accepted by the
  endpoint. They are values, not a scripted playback."
  {:latency
   {:title "Checkout latency by percentile"
    :x-label "Minute" :y-label "Latency (ms)"
    :width 760 :height 420
    :data [{:minute 0 :latency-ms 31 :series "p50"}
           {:minute 1 :latency-ms 34 :series "p50"}
           {:minute 2 :latency-ms 29 :series "p50"}
           {:minute 3 :latency-ms 37 :series "p50"}
           {:minute 4 :latency-ms 35 :series "p50"}
           {:minute 5 :latency-ms 39 :series "p50"}
           {:minute 0 :latency-ms 94 :series "p95"}
           {:minute 1 :latency-ms 102 :series "p95"}
           {:minute 2 :latency-ms 91 :series "p95"}
           {:minute 3 :latency-ms 128 :series "p95"}
           {:minute 4 :latency-ms 117 :series "p95"}
           {:minute 5 :latency-ms 121 :series "p95"}]
    :layers [{:mark :line :x :minute :y :latency-ms :color :series}
             {:mark :point :x :minute :y :latency-ms :color :series}]}

   :errors
   {:title "Errors by service"
    :x-label "Service" :y-label "Errors"
    :width 700 :height 380
    :data [{:service "gateway" :errors 7}
           {:service "checkout" :errors 3}
           {:service "inventory" :errors 5}
           {:service "payments" :errors 2}]
    :layers [{:mark :bar :x :service :y :errors}]}})

(def default-spec-text
  (binding [*print-length* nil *print-level* nil]
    (pr-str (:latency telemetry-specs))))

;; Parsing and validation are portable and authoritative in demo.plotje-spec.
(def validate-spec plotje-spec/validate-spec)
(def parse-spec plotje-spec/parse-spec)
(defn- fail! [message]
  (throw (ex-info message {:plotje-editor/error true})))

(defn- add-layer [pose {:keys [mark x y color]}]
  (let [opts (cond-> {} color (assoc :color color))]
    (case mark
      :line (pj/lay-line pose x y opts)
      :point (pj/lay-point pose x y opts)
      :bar (pj/lay-bar pose x y opts))))

(defn spec->svg
  "Render a validated editor spec through Plotje's public pose/plot pipeline.
  Returns serialized SVG, never hand-authored chart markup."
  [spec]
  (let [{:keys [data layers width height title x-label y-label]}
        (validate-spec spec)
        pose (reduce add-layer data layers)
        options (cond-> {:format :svg :width width :height height}
                  title (assoc :title title)
                  x-label (assoc :x-label x-label)
                  y-label (assoc :y-label y-label))]
    (-> pose (pj/options options) pj/plot plotje-svg/hiccup->svg-str)))

(defn- escape-html [value]
  (-> (str (or value ""))
      (str/replace "&" "&amp;")
      (str/replace "<" "&lt;")
      (str/replace ">" "&gt;")
      (str/replace "\"" "&quot;")
      (str/replace "'" "&#39;")))

(defn- public-error [error]
  (let [message (if (:plotje-editor/error (ex-data error))
                  (ex-message error)
                  "Plotje could not render this bounded spec")]
    (subs (str message) 0 (min 240 (count (str message))))))

(defn render-preview
  "Render the replaceable preview fragment. Errors are escaped and contained."
  [spec-text]
  (try
    (str "<section id=\"plotje-preview\" class=\"plotje-preview\" aria-live=\"polite\">"
         "<figure>" (spec->svg (parse-spec spec-text))
         "<figcaption>Rendered server-side by Plotje</figcaption></figure></section>")
    (catch Throwable error
      (str "<section id=\"plotje-preview\" class=\"plotje-preview plotje-error\" "
           "aria-live=\"polite\"><h2>Spec error</h2><p>"
           (escape-html (public-error error)) "</p></section>"))))

(def ^:private styles
  (str "body{margin:0;background:#0b1020;color:#e8edf8;font:16px system-ui,sans-serif}"
       "main{max-width:1200px;margin:auto;padding:1.5rem}a{color:#8ec5ff}"
       ".plotje-grid{display:grid;grid-template-columns:minmax(20rem,.8fr) minmax(22rem,1.2fr);gap:1rem}"
       ".plotje-panel{background:#151c2e;border:1px solid #33405d;border-radius:.7rem;padding:1rem}"
       "textarea{box-sizing:border-box;width:100%;min-height:34rem;resize:vertical;background:#090e1b;"
       "color:#dbe7ff;border:1px solid #506080;border-radius:.5rem;padding:.8rem;font:14px ui-monospace,monospace}"
       "button{margin-top:.7rem;padding:.55rem 1rem}figure{margin:0;overflow:auto;background:white;border-radius:.4rem}"
       "figure svg{display:block;max-width:100%;height:auto;margin:auto}figcaption{padding:.5rem;color:#25304a}"
       ".plotje-error{border-left:4px solid #ff6b6b}.plotje-note{color:#aab6cf}"
       "@media(max-width:850px){.plotje-grid{grid-template-columns:1fr}textarea{min-height:25rem}}"))

(defn render-page [spec-text]
  (let [spec-text (subs (str (or spec-text default-spec-text))
                        0 (min max-spec-chars
                               (count (str (or spec-text default-spec-text)))))]
    (str "<!doctype html><html lang=\"en\"><head><meta charset=\"utf-8\">"
         "<meta name=\"viewport\" content=\"width=device-width,initial-scale=1\">"
         "<title>Realtime Plotje editor</title><style>" styles "</style></head><body><main>"
         "<p><a href=\"/\">← Observability</a></p><h1>Realtime Plotje chart editor</h1>"
         "<p class=\"plotje-note\">Edit bounded EDN. Submit works without JavaScript; "
         "with JavaScript the same server renderer updates after a short pause.</p>"
         "<div class=\"plotje-grid\"><section class=\"plotje-panel\">"
         "<form method=\"post\" action=\"/plotje-editor\" data-plotje-form>"
         "<label for=\"plotje-spec\">Plotje grammar spec</label>"
         "<textarea id=\"plotje-spec\" name=\"spec\" maxlength=\"" max-spec-chars
         "\" spellcheck=\"false\">" (escape-html spec-text) "</textarea>"
         "<button type=\"submit\">Render chart</button></form></section><section class=\"plotje-panel\">"
         (render-preview spec-text) "</section></div></main>"
         "<script src=\"/assets/plotje-editor.js\" defer></script></body></html>")))

(def enhancement-script
  "Debounced progressive enhancement. The ordinary POST remains authoritative."
  (str "(() => {const form=document.querySelector('[data-plotje-form]');"
       "const input=form?.querySelector('textarea[name=spec]');let timer,request;"
       "if(!form||!input)return;const render=async()=>{request?.abort();request=new AbortController();"
       "try{const response=await fetch('/plotje-editor/preview',{method:'POST',"
       "headers:{'Content-Type':'application/x-www-form-urlencoded;charset=UTF-8'},"
       "body:new URLSearchParams({spec:input.value}),signal:request.signal});"
       "const html=await response.text();const doc=new DOMParser().parseFromString(html,'text/html');"
       "const next=doc.querySelector('#plotje-preview');const current=document.querySelector('#plotje-preview');"
       "if(next&&current)current.replaceWith(next);}catch(error){if(error.name!=='AbortError')console.warn(error);}};"
       "input.addEventListener('input',()=>{clearTimeout(timer);timer=setTimeout(render,300);});})();"))

(defn- bounded-body [body]
  (cond
    (nil? body) ""
    (string? body) (if (> (alength (.getBytes ^String body "UTF-8")) max-body-bytes)
                     (fail! "request body is too large") body)
    (bytes? body) (if (> (alength body) max-body-bytes)
                    (fail! "request body is too large") (String. body "UTF-8"))
    (instance? InputStream body)
    (let [out (ByteArrayOutputStream.) buffer (byte-array 4096)]
      (loop [total 0]
        (let [n (.read ^InputStream body buffer)]
          (if (neg? n)
            (.toString out "UTF-8")
            (let [next-total (+ total n)]
              (when (> next-total max-body-bytes) (fail! "request body is too large"))
              (.write out buffer 0 n)
              (recur next-total))))))
    :else (fail! "unsupported request body")))

(defn- decode [text]
  (try (URLDecoder/decode (str text) "UTF-8")
       (catch Throwable _ (fail! "form body is malformed"))))

(defn- form-spec [body]
  (some (fn [pair]
          (let [i (str/index-of pair "=")]
            (when (and i (= "spec" (decode (subs pair 0 i))))
              (decode (subs pair (inc i))))))
        (str/split (bounded-body body) #"&")))

(def ^:private html-headers
  {"Content-Type" "text/html; charset=UTF-8"
   "Cache-Control" "no-store"
   "Content-Security-Policy" "default-src 'none'; style-src 'unsafe-inline'; script-src 'self'; connect-src 'self'; form-action 'self'; base-uri 'none'"
   "X-Content-Type-Options" "nosniff"})

(defn route? [uri]
  (contains? #{"/plotje-editor" "/plotje-editor/preview"
               "/assets/plotje-editor.js"} uri))

(defn handler
  "Standalone Ring-shaped handler. Returns nil for routes it does not own."
  [{:keys [request-method uri body]}]
  (when (route? uri)
    (try
      (cond
        (and (= :get request-method) (= uri "/plotje-editor"))
        {:status 200 :headers html-headers :body (render-page default-spec-text)}

        (and (= :post request-method) (= uri "/plotje-editor"))
        (let [spec-text (or (form-spec body) "")]
          {:status 200 :headers html-headers :body (render-page spec-text)})

        (and (= :post request-method) (= uri "/plotje-editor/preview"))
        {:status 200 :headers html-headers :body (render-preview (or (form-spec body) ""))}

        (and (= :get request-method) (= uri "/assets/plotje-editor.js"))
        {:status 200
         :headers {"Content-Type" "text/javascript; charset=UTF-8"
                   "Cache-Control" "no-cache"
                   "X-Content-Type-Options" "nosniff"}
         :body enhancement-script}

        :else {:status 405 :headers html-headers :body "method not allowed"})
      (catch Throwable error
        {:status (if (= "request body is too large" (ex-message error)) 413 400)
         :headers html-headers
         :body (render-page (str "{:error \"" (escape-html (public-error error)) "\"}"))}))))
