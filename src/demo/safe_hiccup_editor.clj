(ns demo.safe-hiccup-editor
  (:require [demo.editor-http :as http] [demo.safe-hiccup :as safe]))
(def default-text "[:section {:class \"card\"} [:h2 {} \"Telemetry note\"] [:p {} \"Edit this safe, data-only Hiccup.\"]]")
(defn preview [text]
  (try (str "<section id=\"hiccup-preview\" aria-live=\"polite\">" (safe/text->html text) "</section>")
       (catch Throwable e (str "<section id=\"hiccup-preview\"><strong>Spec error</strong><p>" (http/esc (ex-message e)) "</p></section>"))))
(def script "(()=>{const i=document.querySelector('[data-editor] textarea');let t,c;i?.addEventListener('input',()=>{clearTimeout(t);t=setTimeout(async()=>{c?.abort();c=new AbortController();try{const r=await fetch('/hiccup-editor/preview',{method:'POST',body:new URLSearchParams({spec:i.value}),signal:c.signal});const d=new DOMParser().parseFromString(await r.text(),'text/html'),n=d.querySelector('#hiccup-preview');if(n)document.querySelector('#hiccup-preview')?.replaceWith(n)}catch(e){if(e.name!=='AbortError')console.error(e)}},300)})})()")
(defn page [text]
  (str "<!doctype html><html lang=\"en\"><head><meta charset=\"utf-8\"><meta name=\"viewport\" content=\"width=device-width,initial-scale=1\"><title>Safe Hiccup editor</title><style>" http/page-style "</style></head><body>"
       "<header><nav aria-label=\"Utilities\"><a href=\"/\">Telemetry</a><a href=\"/workbench\">Run workbench</a><a href=\"/plotje-editor\">Plotje editor</a></nav><h1>Safe Hiccup editor</h1><p>Edit bounded, data-only presentation markup. Active content and URLs are rejected.</p></header>"
       "<main><form method=\"post\" action=\"/hiccup-editor\" data-editor><label for=\"hiccup-spec\">Hiccup value</label><textarea id=\"hiccup-spec\" name=\"spec\" maxlength=\"16384\">"
       (http/esc text) "</textarea><button>Render</button></form>" (preview text)
       "</main><script src=\"/assets/hiccup-editor.js\" defer></script></body></html>"))
(defn handler [{:keys [request-method uri body]}]
  (when (contains? #{"/hiccup-editor" "/hiccup-editor/preview" "/assets/hiccup-editor.js"} uri)
    (cond
      (and (= :get request-method) (= uri "/hiccup-editor")) {:status 200 :headers http/html-headers :body (page default-text)}
      (and (= :post request-method) (= uri "/hiccup-editor")) (let [x (or (http/form-value body "spec") "")] {:status 200 :headers http/html-headers :body (page x)})
      (and (= :post request-method) (= uri "/hiccup-editor/preview")) {:status 200 :headers http/html-headers :body (preview (or (http/form-value body "spec") ""))}
      (and (= :get request-method) (= uri "/assets/hiccup-editor.js")) {:status 200 :headers {"Content-Type" "text/javascript; charset=UTF-8"} :body script}
      :else {:status 405 :headers http/html-headers :body "method not allowed"})))
