(ns demo.workbench-view
  "Bounded, escaped HTML rendering for the /workbench run demo. No templates —
  every dynamic value that reaches these strings is escaped and length-capped
  here, at the render boundary, independent of whatever bounds the state layer
  already applies. Reuses the existing OTel viewer's stylesheet and class
  vocabulary rather than inventing a new visual system."
  (:require [clojure.string :as str]
            [otel.viewer :as viewer]))

(def ^:private max-prompt-display 500)
(def ^:private max-detail-display 400)
(def ^:private max-response-display 2000)
(def ^:private max-capture-value-display 200)
(def ^:private max-events-rendered 32)
(def ^:private max-history-rendered 5)
(def ^:private max-prompt-input 2000)

(defn- escape-html [value]
  (-> (str value)
      (str/replace "&" "&amp;")
      (str/replace "<" "&lt;")
      (str/replace ">" "&gt;")
      (str/replace "\"" "&quot;")
      (str/replace "'" "&#39;")))

(defn- bounded [value limit]
  (let [s (str (or value ""))]
    (if (> (count s) limit)
      (str (subs s 0 (max 0 limit)) "…")
      s)))

(def ^:private stage-labels
  {:run-opened "Run opened" :turn-started "Turn started"
   :model-requested "Model requested" :tool-dispatched "Tool dispatched"
   :tool-completed "Tool completed" :controller-decided "Controller decided"
   :run-closed "Run closed"})

(defn- stage-label [stage]
  (get stage-labels stage (str (or stage "unknown"))))

(defn- event-item [{:keys [stage turn detail tool decision]}]
  (str "<li class=\"workbench-event\">"
       "<span class=\"otel-role\">" (escape-html (stage-label stage)) "</span> "
       (when turn (str "<span class=\"workbench-turn\">turn "
                       (escape-html turn) "</span> "))
       (when tool (str "<code>" (escape-html tool) "</code> "))
       (when decision (str "<em>" (escape-html decision) "</em> "))
       (escape-html (bounded detail max-detail-display))
       "</li>"))

(defn- events-list [events]
  (if (seq events)
    (str "<ol class=\"workbench-events\">"
         (apply str (map event-item (take max-events-rendered events)))
         "</ol>")
    "<p class=\"otel-empty\">No events yet.</p>"))

(defn- capture-rows [capture]
  (when (map? capture)
    (str "<dl class=\"otel-span-meta\">"
         (apply str
                (map (fn [[k v]]
                       (str "<div><dt>" (escape-html (name k)) "</dt>"
                            "<dd>" (escape-html
                                    (bounded v max-capture-value-display))
                            "</dd></div>"))
                     capture))
         "</dl>")))

(defn- run-section [run]
  (if run
    (str "<p><strong>Prompt</strong></p>"
         "<pre class=\"workbench-prompt\">"
         (escape-html (bounded (:prompt run) max-prompt-display))
         "</pre>"
         "<p><strong>Status:</strong> "
         (escape-html (name (or (:status run) "unknown"))) "</p>"
         (events-list (:events run))
         (if (= :closed (:status run))
           (str "<div class=\"otel-content\"><strong>Terminal response</strong>"
                "<pre>" (escape-html (bounded (:response run)
                                              max-response-display))
                "</pre></div>"
                (capture-rows (:capture run)))
           "<p class=\"otel-content-note\">Run in progress…</p>"))
    "<p class=\"otel-empty\">No run yet. Enter a prompt above to start one.</p>"))

(defn- history-item [run]
  (str "<li><strong>" (escape-html (bounded (:prompt run) 80)) "</strong>"
       "<small>"
       (if (= :closed (:status run))
         (escape-html (bounded (:response run) 160))
         "interrupted before completion")
       "</small></li>"))

(defn- history-section [history]
  (when (seq history)
    (str "<h2>Previous runs</h2>"
         "<ol class=\"otel-trace-list workbench-history\">"
         (apply str (map history-item (take max-history-rendered history)))
         "</ol>")))

(defn- observation-section [observations]
  (str "<section class=\"workbench-observations\">"
       "<h2>Aspect observation journal</h2>"
       "<p class=\"otel-content-note\">Optional, bounded, and content-free; "
       "it is never application state.</p>"
       (if (seq observations)
         (str "<ol class=\"workbench-events\">"
              (apply str
                     (map (fn [{:keys [seq role phase parent-operation-id]}]
                            (str "<li class=\"workbench-event\"><code>#"
                                 (escape-html seq) "</code> "
                                 (escape-html (name role)) " · "
                                 (escape-html (name phase))
                                 (when parent-operation-id
                                   (str " · parent #"
                                        (escape-html parent-operation-id)))
                                 "</li>"))
                          observations))
              "</ol>")
         "<p class=\"otel-empty\">Disabled in this plain build.</p>")
       "</section>"))

(defn render-live
  "The `#workbench-live` fragment: the current run plus bounded run history.
  Used both to seed the initial page and as the Datastar SSE patch payload."
  [{:keys [current history observations]}]
  (str "<section aria-live=\"polite\" class=\"workbench-run\">"
       "<h2>Current run</h2>"
       (run-section current)
       "</section>"
       (or (history-section history) "")
       (observation-section observations)))

(def ^:private page-styles
  (str ".workbench-events{list-style:none;margin:.75rem 0;padding:0}"
       ".workbench-event{padding:.4rem 0;border-top:1px solid var(--otel-border)}"
       ".workbench-event:first-child{border-top:0}"
       ".workbench-turn{color:var(--otel-muted)}"
       ".workbench-prompt{white-space:pre-wrap;overflow-wrap:anywhere;"
       "background:var(--otel-panel);border:1px solid var(--otel-border);"
       "border-radius:.5rem;padding:.7rem;margin:.3rem 0 1rem}"
       ".workbench-form{display:grid;gap:.6rem;margin-top:.75rem}"
       ".workbench-form textarea{width:100%;min-height:6rem;"
       "background:var(--otel-bg);color:var(--otel-ink);"
       "border:1px solid var(--otel-border);border-radius:.5rem;"
       "padding:.6rem;font:inherit}"
       ".workbench-history li{padding:.5rem 0}"
       ".workbench-history li+li{border-top:1px solid var(--otel-border)}"))

(defn- prompt-form []
  (str "<form method=\"post\" action=\"/workbench\" class=\"workbench-form\">"
       "<label for=\"workbench-prompt\">Prompt</label>"
       "<textarea id=\"workbench-prompt\" name=\"prompt\" maxlength=\""
       max-prompt-input "\" required>"
       "Diagnose why the embedded telemetry dashboard stays stale."
       "</textarea>"
       "<button type=\"submit\">Run</button>"
       "</form>"
       "<p class=\"otel-content-note\">This runs a deterministic local "
       "fixture shaped like Samizdat's event vocabulary. It does not call a "
       "real model or a real Samizdat run.</p>"))

(defn render-page
  "The complete standalone /workbench document. `state-value` is the plain map
  read from the workbench glimmer ratom (`{:current run-or-nil :history [...]}`),
  the same shape `render-live` accepts."
  [state-value]
  (str "<!doctype html>"
       "<html class=\"otel-page\" lang=\"en\"><head><meta charset=\"utf-8\">"
       "<meta name=\"viewport\" content=\"width=device-width, initial-scale=1\">"
       "<title>Run workbench · Jolt Observability</title>"
       "<style>" (viewer/styles) page-styles "</style></head><body>"
       "<main class=\"otel-viewer\">"
       "<header class=\"otel-header\"><div>"
       "<p class=\"otel-eyebrow\">Samizdat-shaped fixture · local, offline</p>"
       "<h1>Run workbench</h1></div>"
       "<nav><a class=\"otel-back-link\" href=\"/\">← All traces</a></nav>"
       "</header>"
       "<section><h2>Start a run</h2>" (prompt-form) "</section>"
       "<div id=\"workbench-live\" data-workbench-live=\"true\">"
       (render-live state-value)
       "</div>"
       "</main>"
       "<script src=\"/assets/workbench.js\" defer></script>"
       "</body></html>"))

(def enhancement-script
  "Progressive enhancement only: opens an EventSource against this same page's
  URL with the Datastar SSE query flags and patches `#workbench-live` on each
  `datastar-patch-elements` event. The base page and the POST form both work
  without this script; it only makes updates appear without a reload."
  (str "(() => {"
       "const live = document.querySelector('#workbench-live[data-workbench-live]');"
       "let source;"
       "const openLive = () => {"
       "if (!live || source || document.hidden) return;"
       "const url = new URL(location.href);"
       "url.searchParams.set('datastar-sse', 'true');"
       "url.searchParams.set('datastar-selector', '#workbench-live');"
       "source = new EventSource(url);"
       "source.addEventListener('datastar-patch-elements', (event) => {"
       "const lines = event.data.split('\\n');"
       "const selector = lines.find((line) => line.startsWith('selector '))?.slice(9);"
       "const elements = lines.filter((line) => line.startsWith('elements '))"
       ".map((line) => line.slice(9)).join('\\n');"
       "if (selector !== '#workbench-live') return;"
       "const parsed = new DOMParser().parseFromString(elements, 'text/html');"
       "live.replaceChildren(...Array.from(parsed.body.childNodes));"
       "});"
       "};"
       "document.addEventListener('visibilitychange', () => {"
       "if (document.hidden) { source?.close(); source = undefined; } else openLive();"
       "});"
       "window.addEventListener('online', openLive);"
       "openLive();"
       "})();"))
