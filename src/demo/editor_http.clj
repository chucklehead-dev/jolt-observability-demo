(ns demo.editor-http
  "Jolt-portable bounded form transport and response helpers for editors."
  (:require [clojure.string :as str]
            [jolt.http.body :as http-body])
  (:import [java.net URLDecoder]))
(def max-body-bytes 40000)
(defn esc [x] (-> (str (or x "")) (str/replace "&" "&amp;") (str/replace "<" "&lt;")
                  (str/replace ">" "&gt;") (str/replace "\"" "&quot;") (str/replace "'" "&#39;")))
(defn- concat-chunks [chunks total]
  (let [out (byte-array total)]
    (loop [chunks chunks offset 0]
      (if-let [chunk (first chunks)]
        (let [n (alength chunk)]
          (dotimes [i n] (aset out (+ offset i) (aget chunk i)))
          (recur (next chunks) (+ offset n)))
        out))))

(defn- body-bytes [body]
  (let [bytes
        (cond
          (nil? body) (byte-array 0)
          (string? body) (.getBytes ^String body "UTF-8")
          (bytes? body) body
          (satisfies? http-body/RequestBody body)
          (loop [chunks [] total 0]
            (if-let [chunk (http-body/body-recv body)]
              (let [next-total (+ total (alength chunk))]
                (when (> next-total max-body-bytes)
                  (throw (ex-info "request body is too large" {:status 413})))
                (recur (conj chunks chunk) next-total))
              (concat-chunks chunks total)))
          :else (byte-array 0))]
    (when (> (alength bytes) max-body-bytes)
      (throw (ex-info "request body is too large" {:status 413})))
    bytes))

(defn form-value [body field]
  (let [body (String. (body-bytes body) "UTF-8")]
    (some (fn [pair] (let [i (str/index-of pair "=")
                           dec #(try (URLDecoder/decode % "UTF-8") (catch Throwable _ ""))]
                       (when (and i (= field (dec (subs pair 0 i)))) (dec (subs pair (inc i))))))
          (str/split body #"&"))))
(def html-headers {"Content-Type" "text/html; charset=UTF-8" "Cache-Control" "no-store"
                   "Content-Security-Policy" "default-src 'none'; style-src 'unsafe-inline'; script-src 'self'; connect-src 'self'; form-action 'self'; base-uri 'none'"
                   "Referrer-Policy" "no-referrer"
                   "X-Content-Type-Options" "nosniff"})

(def page-style
  (str ":root{color-scheme:dark;--bg:#10141c;--panel:#18202b;--text:#f4f7fb;"
       "--muted:#b7c1cf;--line:#405065;--accent:#7cc7ff}*{box-sizing:border-box}"
       "body{margin:0;background:var(--bg);color:var(--text);font:16px/1.5 system-ui,sans-serif}"
       "body>header,body>main{width:min(1200px,calc(100% - 2rem));margin:auto}"
       "body>header{padding:1.25rem 0 .5rem}nav{display:flex;gap:1rem;flex-wrap:wrap}"
       "a{color:var(--accent)}main{display:grid;grid-template-columns:minmax(18rem,.9fr) minmax(20rem,1.1fr);gap:1rem;padding:1rem 0 2rem}"
       "form,#plotje-preview,#hiccup-preview{min-width:0;background:var(--panel);border:1px solid var(--line);border-radius:.7rem;padding:1rem}"
       "textarea{display:block;width:100%;min-height:32rem;resize:vertical;background:#0c1118;color:var(--text);border:1px solid #62748b;border-radius:.45rem;padding:.75rem;font:14px/1.45 ui-monospace,monospace}"
       "button{margin-top:.75rem;background:#d7efff;color:#081018;border:0;border-radius:.4rem;padding:.55rem .9rem;font-weight:700}"
       "svg{display:block;max-width:100%;height:auto;background:white;border-radius:.35rem}"
       "@media(max-width:760px){main{grid-template-columns:1fr}textarea{min-height:20rem}}"))
