(ns demo.plotje-portable-editor
  "Mountable Jolt editor surface backed by the portable Plotje contract."
  (:require [demo.editor-http :as http] [demo.plotje-portable :as renderer]
            [demo.plotje-spec :as spec]))
(defn preview [text]
  (try (str "<section id=\"plotje-preview\" aria-live=\"polite\">" (renderer/spec->svg (spec/parse-spec text)) "</section>")
       (catch Throwable e (str "<section id=\"plotje-preview\" aria-live=\"polite\"><strong>Spec error</strong><p>" (http/esc (ex-message e)) "</p></section>"))))
(def script (str "(()=>{const f=document.querySelector('[data-editor]'),i=f?.querySelector('textarea');let t,c;if(!i)return;"
                 "i.addEventListener('input',()=>{clearTimeout(t);t=setTimeout(async()=>{c?.abort();c=new AbortController();try{"
                 "const r=await fetch('/plotje-editor/preview',{method:'POST',body:new URLSearchParams({spec:i.value}),signal:c.signal});"
                 "const d=new DOMParser().parseFromString(await r.text(),'text/html'),n=d.querySelector('#plotje-preview');"
                 "if(n)document.querySelector('#plotje-preview')?.replaceWith(n)}catch(e){if(e.name!=='AbortError')console.error(e)}},300)})})()"))
(defn page [text]
  (str "<!doctype html><html lang=\"en\"><head><meta charset=\"utf-8\"><meta name=\"viewport\" content=\"width=device-width,initial-scale=1\"><title>Plotje editor</title><style>" http/page-style "</style></head><body>"
       "<header><nav aria-label=\"Utilities\"><a href=\"/\">Telemetry</a><a href=\"/workbench\">Run workbench</a><a href=\"/hiccup-editor\">Hiccup editor</a></nav><h1>Plotje editor</h1><p>Edit a bounded grammar-of-graphics EDN value. Rendering works without JavaScript.</p></header>"
       "<main><form method=\"post\" action=\"/plotje-editor\" data-editor><label for=\"plotje-spec\">Chart specification</label><textarea id=\"plotje-spec\" name=\"spec\" maxlength=\"32768\">"
       (http/esc text) "</textarea><button>Render</button></form>" (preview text) "</main><script src=\"/assets/plotje-editor.js\" defer></script></body></html>"))
(defn handler [{:keys [request-method uri body]}]
  (when (contains? #{"/plotje-editor" "/plotje-editor/preview" "/assets/plotje-editor.js"} uri)
    (cond
      (and (= :get request-method) (= uri "/plotje-editor")) {:status 200 :headers http/html-headers :body (page spec/default-spec-text)}
      (and (= :post request-method) (= uri "/plotje-editor")) (let [x (or (http/form-value body "spec") "")] {:status 200 :headers http/html-headers :body (page x)})
      (and (= :post request-method) (= uri "/plotje-editor/preview")) {:status 200 :headers http/html-headers :body (preview (or (http/form-value body "spec") ""))}
      (and (= :get request-method) (= uri "/assets/plotje-editor.js")) {:status 200 :headers {"Content-Type" "text/javascript; charset=UTF-8"} :body script}
      :else {:status 405 :headers http/html-headers :body "method not allowed"})))
