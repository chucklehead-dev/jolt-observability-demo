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

;; Mirrors test/browser/model-fixture-server.js's canned findings so the
;; workbench and the model-backed demo tell the same paranoid-android story.
(def ^:private initial-finding
  (str "The dashboard is not stale; it is merely waiting for the server to "
       "finish calculating the square root of -1, a process that will "
       "conclude in approximately four billion years."))

(def ^:private revised-finding
  (str "The live patch missed its wake transition and left the viewer on an "
       "old cursor; the controller has reassigned the square root of -1 to "
       "the thread that thought waiting counted as progress."))

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
             "Requested a diagnostic finding from the fixture model.")
      (event :tool-dispatched 1
             "Dispatched the first answer for controller review."
             "controller_review" nil)
      (event :tool-completed 1
             (str "Controller review received: " initial-finding)
             "controller_review" nil)
      (event :controller-decided 1
             "The answer is evocative but names no concrete mechanism."
             nil "revise")
      (event :turn-started 2 "Turn 2 started after controller intervention.")
      (event :model-requested 2
             "Requested a revised diagnostic finding grounded in the controller's guidance.")
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

(defn adapter
  "The default injected run adapter: a deterministic local fixture."
  []
  (->LocalFixtureAdapter))
