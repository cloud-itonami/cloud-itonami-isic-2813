(ns pressureequip.sim
  "Demo driver -- `clojure -M:dev:run`. Walks a clean unit through
  intake -> design-rules requirements verification -> hydrostatic/
  pneumatic pressure-test screening -> unit-dispatch proposal (always
  escalates) -> human approval -> commit, then through pressure-
  test-certificate proposal (always escalates) -> human approval ->
  commit, then shows five HARD holds (a jurisdiction with no
  spec-basis, an out-of-window test pressure, an unresolved
  pressure-test defect screened directly via `:pressure-test/screen`
  [never via an actuation op against an unscreened unit -- see this
  actor's own governor ns docstring / the lesson `parksafety`'s
  ADR-2607071922 Decision 5, `turbine`'s and `automotive`'s and
  `machinetool`'s ADR-0001s already recorded], and a double unit-
  dispatch/certificate-issuance of an already-processed unit) that
  never reach a human at all, and prints the audit ledger + the draft
  unit-dispatch and pressure-test-certificate records."
  (:require [langgraph.graph :as g]
            [pressureequip.export :as export]
            [pressureequip.store :as store]
            [pressureequip.operation :as op]))

(def operator {:actor-id "op-1" :actor-role :pressure-equipment-engineer :phase 3})

(defn- exec! [actor tid request context]
  (g/run* actor {:request request :context context} {:thread-id tid}))

(defn- approve! [actor tid]
  (g/run* actor {:approval {:status :approved :by "op-1"}} {:thread-id tid :resume? true}))

(defn -main [& _]
  (let [db (store/seed-db)
        actor (op/build db)]
    (println "== unit/intake unit-1 (JPN, clean; test pressure within spec, no pressure-test defect) ==")
    (println (exec! actor "t1" {:op :unit/intake :subject "unit-1"
                                :patch {:id "unit-1" :unit-name "Sakura API 610 Centrifugal Pump CP-04"}} operator))

    (println "== design-rules/verify unit-1 (escalates -- human approves) ==")
    (println (exec! actor "t2" {:op :design-rules/verify :subject "unit-1"} operator))
    (println (approve! actor "t2"))

    (println "== pressure-test/screen unit-1 (clean; escalates -- human approves) ==")
    (println (exec! actor "t3" {:op :pressure-test/screen :subject "unit-1"} operator))
    (println (approve! actor "t3"))

    (println "== actuation/dispatch-unit unit-1 (always escalates -- actuation/dispatch-unit) ==")
    (let [r (exec! actor "t4" {:op :actuation/dispatch-unit :subject "unit-1"} operator)]
      (println r)
      (println "-- human pressure-equipment engineer approves --")
      (println (approve! actor "t4")))

    (println "== actuation/issue-pressure-test-certificate unit-1 (always escalates -- actuation/issue-pressure-test-certificate) ==")
    (let [r (exec! actor "t5" {:op :actuation/issue-pressure-test-certificate :subject "unit-1"} operator)]
      (println r)
      (println "-- human pressure-equipment engineer approves --")
      (println (approve! actor "t5")))

    (println "== design-rules/verify unit-2 (no spec-basis -> HARD hold) ==")
    (println (exec! actor "t6" {:op :design-rules/verify :subject "unit-2" :no-spec? true} operator))

    (println "== design-rules/verify unit-3 (escalates -- human approves; sets up the out-of-window test) ==")
    (println (exec! actor "t7" {:op :design-rules/verify :subject "unit-3"} operator))
    (println (approve! actor "t7"))

    (println "== actuation/dispatch-unit unit-3 (18.0 outside [13.0,15.0] test-pressure window -> HARD hold) ==")
    (println (exec! actor "t8" {:op :actuation/dispatch-unit :subject "unit-3"} operator))

    (println "== pressure-test/screen unit-4 (unresolved -> HARD hold, never reaches a human) ==")
    (println (exec! actor "t9" {:op :pressure-test/screen :subject "unit-4"} operator))

    (println "== actuation/dispatch-unit unit-1 AGAIN (double-dispatch -> HARD hold) ==")
    (println (exec! actor "t10" {:op :actuation/dispatch-unit :subject "unit-1"} operator))

    (println "== actuation/issue-pressure-test-certificate unit-1 AGAIN (double-issuance -> HARD hold) ==")
    (println (exec! actor "t11" {:op :actuation/issue-pressure-test-certificate :subject "unit-1"} operator))

    (println "== audit ledger ==")
    (doseq [f (store/ledger db)] (println f))

    (println "== draft unit-dispatch records ==")
    (doseq [r (store/dispatch-history db)] (println r))

    (println "== draft pressure-test-certificate records ==")
    (doseq [r (store/evidence-history db)] (println r))

    (println "== social hand-off: audit package counts ==")
    (println (:counts (export/audit-package db)))
    (println "== social hand-off: CSV bundle keys ==")
    (println (keys (export/package->csv-bundle db)))))
