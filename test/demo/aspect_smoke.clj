(ns demo.aspect-smoke
  (:require [demo.aspect-provider :as provider]
            [demo.workbench :as workbench]
            [demo.workbench-fixture :as fixture]))

(defn -main [& _]
  (let [prompt "secret prompt must not enter the observation journal"
        run (workbench/new-run 1 prompt (fixture/adapter))
        events (provider/snapshot)]
    (println {:run-status (:status run)
              :event-count (count events)
              :phases (mapv :phase events)
              :contains-prompt? (boolean (some #(= prompt %) (tree-seq coll? seq events)))})
    (when-not (and (= :running (:status run))
                   (= [:enter :return] (mapv :phase events))
                   (not (some #(= prompt %) (tree-seq coll? seq events))))
      (System/exit 1))))
