(ns demo.aspect-smoke
  (:require [demo.aspect-journal :as journal]
            [demo.workbench :as workbench]
            [demo.workbench-fixture :as fixture]))

(defn -main [& _]
  (let [prompt "secret prompt must not enter the observation journal"
        observations (journal/journal)
        run (binding [journal/*journal* observations]
              (workbench/new-run 1 prompt (fixture/adapter)))
        events (journal/snapshot observations)]
    (println {:run-status (:status run)
              :event-count (count events)
              :phases (mapv :phase events)
              :contains-prompt? (boolean (some #(= prompt %) (tree-seq coll? seq events)))})
    (when-not (and (= :running (:status run))
                   (= [:enter :return] (mapv :phase events))
                   (not (some #(= prompt %) (tree-seq coll? seq events))))
      (System/exit 1))))
