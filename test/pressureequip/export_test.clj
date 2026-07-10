(ns pressureequip.export-test
  "Audit-package export contract -- social/regulatory hand-off shape."
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is]]
            [langgraph.graph :as g]
            [pressureequip.export :as export]
            [pressureequip.operation :as op]
            [pressureequip.store :as store]))

(def operator {:actor-id "op-1" :actor-role :pressure-equipment-engineer :phase 3})

(defn- exec! [actor tid request]
  (g/run* actor {:request request :context operator} {:thread-id tid}))

(defn- approve! [actor tid]
  (g/run* actor {:approval {:status :approved :by "op-1"}}
          {:thread-id tid :resume? true}))

(defn- seed-with-one-dispatch []
  (let [db (store/seed-db)
        actor (op/build db)]
    (exec! actor "v" {:op :design-rules/verify :subject "unit-1"})
    (approve! actor "v")
    (exec! actor "d" {:op :actuation/dispatch-unit :subject "unit-1"})
    (approve! actor "d")
    db))

(deftest audit-package-shape
  (let [db (seed-with-one-dispatch)
        pkg (export/audit-package db)]
    (is (= "2813" (:isic pkg)))
    (is (= "cloud-itonami-isic-2813" (:business-id pkg)))
    (is (= :edn-maps (:format pkg)))
    (is (pos? (get-in pkg [:counts :ledger])))
    (is (= 1 (get-in pkg [:counts :dispatches])))
    (is (some #(= "unit-1" (:id %)) (:units pkg)))
    (is (true? (:unit-dispatched?
                (first (filter #(= "unit-1" (:id %)) (:units pkg))))))))

(deftest csv-bundle-has-headers-and-rows
  (let [db (seed-with-one-dispatch)
        bundle (export/package->csv-bundle db)]
    (is (every? bundle ["units.csv" "ledger.csv" "dispatches.csv" "pressure-test-certificates.csv"]))
    (is (str/starts-with? (get bundle "units.csv") "id,unit-name,"))
    (is (re-find #"unit-1" (get bundle "units.csv")))
    (is (re-find #"JPN-PEQ-000000" (get bundle "dispatches.csv")))
    (is (re-find #":actuation/dispatch-unit" (get bundle "ledger.csv")))))

(deftest empty-store-export-is-usable
  (let [db (store/seed-db)
        pkg (export/audit-package db)
        bundle (export/package->csv-bundle db)]
    (is (= 0 (get-in pkg [:counts :dispatches])))
    (is (= 4 (get-in pkg [:counts :units])))
    (is (str/includes? (get bundle "ledger.csv") "seq,t,op"))))
