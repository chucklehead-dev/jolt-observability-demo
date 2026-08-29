(ns demo.aspect-journal
  "A bounded, dependency-light observation sink for compiler aspects.

  This is deliberately not application state and not an authoritative audit
  log. It records deterministic semantic ordering for local inspection and
  tests; application correctness must be identical when no journal is bound."
  (:refer-clojure :exclude [reset!]))

(def default-capacity 256)

(defrecord ObservationJournal [capacity state next-operation notify])

(defn journal
  ([] (journal default-capacity nil))
  ([capacity] (journal capacity nil))
  ([capacity notify]
   (when-not (and (integer? capacity) (pos? capacity))
     (throw (ex-info "observation journal capacity must be a positive integer"
                     {:capacity capacity})))
   (->ObservationJournal capacity
                         (atom {:next-seq 0 :events []})
                         (atom 0)
                         notify)))

(def ^:dynamic *journal* nil)
(def ^:dynamic *operation* nil)

(defn snapshot
  "Return the retained events in observation order."
  ([] (snapshot *journal*))
  ([j]
   (if j (:events @(:state j)) [])))

(defn reset!
  "Clear a journal. Intended for bounded tests and explicit demo resets."
  ([] (reset! *journal*))
  ([j]
   (when j
     (clojure.core/reset! (:state j) {:next-seq 0 :events []})
     (clojure.core/reset! (:next-operation j) 0))
   nil))

(defn- retain [capacity events event]
  (let [events (conj events event)
        excess (- (count events) capacity)]
    (if (pos? excess) (subvec events excess) events)))

(defn- append! [j event]
  (when j
    (swap! (:state j)
           (fn [{:keys [next-seq events]}]
             (let [seq-no (inc next-seq)]
               {:next-seq seq-no
                :events (retain (:capacity j) events (assoc event :seq seq-no))})))
    (when-let [notify (:notify j)] (notify)))
  nil)

(defn- static-context [join-point operation-id]
  {:operation-id operation-id
   :parent-operation-id *operation*
   :aspect (:id join-point)
   :role (:advice-role join-point)
   :library (:library join-point)})

(defn around
  "Record enter and terminal events around one synchronous semantic operation.

  Only static join-point metadata and exception type are retained. Arguments,
  results, exception messages, prompts, responses, and hostnames are never
  captured. The exact application value or exception is returned/rethrown."
  [join-point proceed]
  (let [j *journal*
        operation-id (when j (swap! (:next-operation j) inc))
        context (static-context join-point operation-id)]
    (append! j (assoc context :phase :enter))
    (binding [*operation* operation-id]
      (try
        (let [value (proceed)]
          (append! j (assoc context :phase :return))
          value)
        (catch :default error
          (append! j (assoc context :phase :throw
                                    :exception-type (.getName (class error))))
          (throw error))))))
