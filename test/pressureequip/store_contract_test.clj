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
      (is (= [] (store/maintenance-notice-history s)))
      (is (zero? (store/next-dispatch-sequence s "JPN")))
      (is (zero? (store/next-evidence-sequence s "JPN")))
      (is (zero? (store/next-maintenance-notice-sequence s "JPN")))
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
      (testing "maintenance notice drafts a record, advances the sequence, and allows more than one per unit"
        (store/commit-record! s {:effect :maintenance-notice/issue :path ["unit-1"]
                                 :value {:unit-id "unit-1" :dispatch-ref "JPN-PEQ-000000"}})
        (is (= "JPN-PMN-000000" (get (first (store/maintenance-notice-history s)) "record_id")))
        (is (= "maintenance-notice-draft" (get (first (store/maintenance-notice-history s)) "kind")))
        (is (= "JPN-PEQ-000000" (get (first (store/maintenance-notice-history s)) "dispatch_ref")))
        (is (= 1 (count (store/maintenance-notice-history s))))
        (is (= 1 (store/next-maintenance-notice-sequence s "JPN")))
        (store/commit-record! s {:effect :maintenance-notice/issue :path ["unit-1"]
                                 :value {:unit-id "unit-1" :dispatch-ref "JPN-PEQ-000000"}})
        (is (= 2 (count (store/maintenance-notice-history s)))
            "a unit may receive more than one maintenance notice, unlike dispatch/certificate")
        (is (= "JPN-PMN-000001" (get (second (store/maintenance-notice-history s)) "record_id"))))
      (testing "ledger is append-only and order-preserving"
        (store/append-ledger! s {:op :a :disposition :commit})
        (store/append-ledger! s {:op :b :disposition :hold})
        (is (= [:commit :hold] (mapv :disposition (store/ledger s))))))))

(deftest equipment-asset-store-parity
  (testing "equipment-asset registration -- isic-2813's RECEIVE side of the superproject :equipment-asset shared shape, toward an upstream manufacturer actor e.g. cloud-itonami-isic-2822"
    (doseq [[label s] (backends)]
      (testing label
        (is (nil? (store/equipment-asset s "eqa-1")))
        (is (false? (store/equipment-asset-already-registered? s "eqa-1")))
        (is (= [] (store/all-equipment-assets s)))
        (store/commit-record! s {:effect :equipment-asset/register
                                 :value {:equipment-asset/id "eqa-1"
                                         :equipment-asset/unit-type-id :unit/industrial-welding-cell
                                         :equipment-asset/source-actor "cloud-itonami-isic-2822"
                                         :equipment-asset/dispatch-ref "JPN-MTL-000000"
                                         :equipment-asset/installed-at-iso "2026-07-18T00:00:00Z"
                                         :equipment-asset/station-cell "cell:weld-1"}})
        (is (true? (store/equipment-asset-already-registered? s "eqa-1")))
        (is (= "cloud-itonami-isic-2822" (:equipment-asset/source-actor (store/equipment-asset s "eqa-1"))))
        (is (= "JPN-MTL-000000" (:equipment-asset/dispatch-ref (store/equipment-asset s "eqa-1"))))
        (is (= "cell:weld-1" (:equipment-asset/station-cell (store/equipment-asset s "eqa-1"))))
        (is (= 1 (count (store/all-equipment-assets s))))
        (is (false? (store/equipment-asset-already-registered? s "eqa-2"))
            "a different id is unaffected")))))

(deftest unit-type-id-read-parity
  (doseq [[label s] (backends)]
    (testing label
      (is (= :unit/industrial-refrigeration-compressor (:unit-type-id (store/unit s "unit-1")))
          "the demo unit's optional unit-types catalog reference round-trips on both backends")
      (is (nil? (:unit-type-id (store/unit s "unit-2")))
          "a unit with no declared unit-type-id stays nil, not fabricated"))))

(deftest datomic-empty-store-is-usable
  (let [s (store/datomic-store)]
    (is (nil? (store/unit s "nope")))
    (is (= [] (store/all-units s)))
    (is (= [] (store/ledger s)))
    (is (= [] (store/dispatch-history s)))
    (is (= [] (store/evidence-history s)))
    (is (= [] (store/maintenance-notice-history s)))
    (is (zero? (store/next-dispatch-sequence s "JPN")))
    (is (zero? (store/next-evidence-sequence s "JPN")))
    (is (zero? (store/next-maintenance-notice-sequence s "JPN")))
    (store/with-units s {"x" {:id "x" :unit-name "n" :test-pressure-actual 13.5
                                   :test-pressure-min 13.0 :test-pressure-max 15.0
                                   :pressure-test-defect-unresolved? false
                                   :unit-dispatched? false :pressure-test-certified? false
                                   :jurisdiction "JPN" :status :intake}})
    (is (= "n" (:unit-name (store/unit s "x"))))))
