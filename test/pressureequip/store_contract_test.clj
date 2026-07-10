(ns pressureequip.store-contract-test
  "The Store contract, run against BOTH backends. Proving MemStore and
  the Datomic-backed (langchain.db) store satisfy the same contract is
  what makes 'swap the SSoT for Datomic / kotoba-server' a configuration
  change, not a rewrite -- see `cloud-itonami-isic-6511`'s
  `underwriting.store-contract-test` for the same pattern on the sibling
  actor."
  (:require [clojure.test :refer [deftest is testing]]
            [pressureequip.store :as store]))

(defn- backends []
  [["MemStore" (store/seed-db)] ["DatomicStore" (store/datomic-seed-db)]])

(deftest read-parity
  (doseq [[label s] (backends)]
    (testing label
      (is (= "Sakura API 610 Centrifugal Pump CP-04" (:unit-name (store/unit s "unit-1"))))
      (is (= "JPN" (:jurisdiction (store/unit s "unit-1"))))
      (is (= 13.5 (:test-pressure-actual (store/unit s "unit-1"))))
      (is (= 13.0 (:test-pressure-min (store/unit s "unit-1"))))
      (is (= 15.0 (:test-pressure-max (store/unit s "unit-1"))))
      (is (false? (:pressure-test-defect-unresolved? (store/unit s "unit-1"))))
      (is (= 18.0 (:test-pressure-actual (store/unit s "unit-3"))))
      (is (true? (:pressure-test-defect-unresolved? (store/unit s "unit-4"))))
      (is (false? (:unit-dispatched? (store/unit s "unit-1"))))
      (is (false? (:pressure-test-certified? (store/unit s "unit-1"))))
      (is (= ["unit-1" "unit-2" "unit-3" "unit-4"]
             (mapv :id (store/all-units s))))
      (is (nil? (store/pressure-screen-of s "unit-1")))
      (is (nil? (store/requirements-verification-of s "unit-1")))
      (is (= [] (store/ledger s)))
      (is (= [] (store/dispatch-history s)))
      (is (= [] (store/evidence-history s)))
      (is (zero? (store/next-dispatch-sequence s "JPN")))
      (is (zero? (store/next-evidence-sequence s "JPN")))
      (is (false? (store/unit-already-dispatched? s "unit-1")))
      (is (false? (store/unit-already-certified? s "unit-1"))))))

(deftest write-and-ledger-parity
  (doseq [[label s] (backends)]
    (testing label
      (testing "partial upsert merges, preserving untouched fields"
        (store/commit-record! s {:effect :unit/upsert
                                 :value {:id "unit-1" :unit-name "Sakura API 610 Centrifugal Pump CP-04"}})
        (is (= "Sakura API 610 Centrifugal Pump CP-04" (:unit-name (store/unit s "unit-1"))))
        (is (= 13.5 (:test-pressure-actual (store/unit s "unit-1"))) "unrelated field preserved"))
      (testing "verification / pressure-screen payloads commit and read back"
        (store/commit-record! s {:effect :verification/set :path ["unit-1"]
                                 :payload {:jurisdiction "JPN" :checklist ["a" "b"]}})
        (is (= {:jurisdiction "JPN" :checklist ["a" "b"]} (store/requirements-verification-of s "unit-1")))
        (store/commit-record! s {:effect :pressure-test-screen/set :path ["unit-1"]
                                 :payload {:unit-id "unit-1" :verdict :resolved}})
        (is (= {:unit-id "unit-1" :verdict :resolved} (store/pressure-screen-of s "unit-1"))))
      (testing "unit dispatch drafts a record and advances the sequence"
        (store/commit-record! s {:effect :unit/mark-dispatched :path ["unit-1"]})
        (is (= "JPN-PEQ-000000" (get (first (store/dispatch-history s)) "record_id")))
        (is (= "unit-dispatch-draft" (get (first (store/dispatch-history s)) "kind")))
        (is (true? (:unit-dispatched? (store/unit s "unit-1"))))
        (is (= 1 (count (store/dispatch-history s))))
        (is (= 1 (store/next-dispatch-sequence s "JPN")))
        (is (true? (store/unit-already-dispatched? s "unit-1")))
        (is (false? (store/unit-already-dispatched? s "unit-2"))))
      (testing "pressure-test certificate drafts a record and advances the sequence"
        (store/commit-record! s {:effect :unit/mark-certified :path ["unit-1"]})
        (is (= "JPN-PTC-000000" (get (first (store/evidence-history s)) "record_id")))
        (is (= "pressure-test-certificate-draft" (get (first (store/evidence-history s)) "kind")))
        (is (true? (:pressure-test-certified? (store/unit s "unit-1"))))
        (is (= 1 (count (store/evidence-history s))))
        (is (= 1 (store/next-evidence-sequence s "JPN")))
        (is (true? (store/unit-already-certified? s "unit-1")))
        (is (false? (store/unit-already-certified? s "unit-2"))))
      (testing "ledger is append-only and order-preserving"
        (store/append-ledger! s {:op :a :disposition :commit})
        (store/append-ledger! s {:op :b :disposition :hold})
        (is (= [:commit :hold] (mapv :disposition (store/ledger s))))))))

(deftest datomic-empty-store-is-usable
  (let [s (store/datomic-store)]
    (is (nil? (store/unit s "nope")))
    (is (= [] (store/all-units s)))
    (is (= [] (store/ledger s)))
    (is (= [] (store/dispatch-history s)))
    (is (= [] (store/evidence-history s)))
    (is (zero? (store/next-dispatch-sequence s "JPN")))
    (is (zero? (store/next-evidence-sequence s "JPN")))
    (store/with-units s {"x" {:id "x" :unit-name "n" :test-pressure-actual 13.5
                                   :test-pressure-min 13.0 :test-pressure-max 15.0
                                   :pressure-test-defect-unresolved? false
                                   :unit-dispatched? false :pressure-test-certified? false
                                   :jurisdiction "JPN" :status :intake}})
    (is (= "n" (:unit-name (store/unit s "x"))))))
