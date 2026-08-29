(ns demo.workbench-fixture
  "The /workbench run adapter boundary.

  `RunAdapter` is the seam between the workbench UI (`demo.workbench`) and
  whatever actually produces a run's ordered semantic-stage events. The only
  implementation here, `LocalFixtureAdapter`, is a deterministic, offline,
  hand-scripted stand-in shaped like a Samizdat control-loop run — it is a
  FIXTURE, not a Samizdat integration. It never opens a socket, reads an
  environment variable, or names a physical host.

  Swapping in real Samizdat output means implementing `RunAdapter` against
  Samizdat's actual control-loop callbacks (or its OTel events) and injecting
  that implementation via `demo.workbench`'s app-context option, unchanged at
  every other layer."
  (:require [clojure.string :as str]))

(defprotocol RunAdapter
  (run-script [adapter prompt]
    "Given a bounded, already-trimmed user prompt string, return
    {:events [ordered semantic-stage event maps], :response terminal-response-string,
     :capture {bounded summary map}}.

    Each event is a map with at least :stage, one of :run-opened,
    :turn-started, :model-requested, :tool-dispatched, :tool-completed,
    :controller-decided, :run-closed; plus optional :turn, :tool, :decision,
    and a human-readable :detail string.

    Must be a pure, side-effect-free computation of `prompt` alone — no
    network calls, no environment reads — so the boundary stays trivially
    swappable for an adapter that instead streams a real run's events."))

(defprotocol AsyncRunAdapter
  (start-async! [adapter prompt callbacks]
    "Start a real run without blocking the request thread.

    `callbacks` contains :started!, :event!, :complete!, and :failed!. The
    adapter returns an ownership handle with bounded :abort! and :join!
    functions. Completion callbacks may race the return, so callers must not
    assume the handle is registered first."))

;; Mirrors test/browser/model-fixture-server.js's canned findings so the
;; workbench and model-backed demo tell the same concrete coding-review story.
(def ^:private initial-finding
  (str "Reset the stream cursor to zero whenever the browser reconnects, then "
       "poll after each wakeup; add a reconnect smoke test that checks the "
       "newest trace appears."))

(def ^:private revised-finding
  (str "Resume from Last-Event-ID, register the waiter before re-reading the "
       "durable maximum sequence, emit only records newer than the cursor, "
       "and advance it only after a successful write. Add a deterministic "
       "test that inserts a notification between the first read and waiter "
       "registration, then reconnects and asserts no gaps or duplicates."))

(def fixture-model-label
  "Non-identifying display label; the fixture never names a physical host."
  "workbench-local-fixture")

(defn- event
  ([stage detail] (event stage nil detail nil nil))
  ([stage turn detail] (event stage turn detail nil nil))
  ([stage turn detail tool decision]
   (cond-> {:stage stage :detail detail}
     turn (assoc :turn turn)
     tool (assoc :tool tool)
     decision (assoc :decision decision))))

(defn- prompt-preview [prompt]
  (let [prompt (str/trim (or prompt ""))]
    (if (str/blank? prompt)
      "(no prompt given)"
      prompt)))

(defrecord LocalFixtureAdapter []
  RunAdapter
  (run-script [_ prompt]
    {:events
     [(event :run-opened
             (str "Opened a fixture run for: " (prompt-preview prompt)))
      (event :turn-started 1 "Turn 1 started.")
      (event :model-requested 1
             "Requested a minimal SSE reconnect patch and regression test.")
      (event :tool-dispatched 1
             "Dispatched the proposed patch for controller review."
             "controller_review" nil)
      (event :tool-completed 1
             (str "Controller review received: " initial-finding)
             "controller_review" nil)
      (event :controller-decided 1
             "Resetting the cursor would replay already delivered rows."
             nil "revise")
      (event :turn-started 2 "Turn 2 started after controller intervention.")
      (event :model-requested 2
             "Requested a race-safe revision grounded in the controller's invariants.")
      (event :tool-dispatched 2
             "Dispatched the revised answer for length scoring."
             "response_length" nil)
      (event :tool-completed 2
             (str "Response length scored: " (count revised-finding) " characters.")
             "response_length" nil)
      (event :run-closed nil "Run closed after 2 turns.")]
     :response revised-finding
     :capture {:model fixture-model-label
               :turns 2
               :controller-intervened true
               :content-state "captured"
               :source "local fixture (not Samizdat)"}}))

(defrecord FunctionAdapter [run-fn]
  RunAdapter
  (run-script [_ prompt]
    (run-fn prompt)))

(defrecord AsyncFunctionAdapter [start-fn]
  AsyncRunAdapter
  (start-async! [_ prompt callbacks]
    (start-fn prompt callbacks)))

(defn adapter
  "The default injected run adapter: a deterministic local fixture."
  []
  (->LocalFixtureAdapter))

(defn function-adapter
  "Adapt a prompt-consuming function to the workbench boundary. The function
  must return the same bounded semantic result map as `run-script`. This keeps
  the route independent of the model, telemetry, or agent implementation."
  [run-fn]
  (->FunctionAdapter run-fn))

(defn async-function-adapter
  "Adapt an asynchronous start function to the workbench boundary."
  [start-fn]
  (->AsyncFunctionAdapter start-fn))
