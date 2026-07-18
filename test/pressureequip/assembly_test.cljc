(ns pressureequip.assembly-test
  (:require [clojure.set :as set]
            [clojure.test :refer [deftest is testing]]
            [pressureequip.assembly :as assembly]))

(deftest seq-contiguous-test
  (is (assembly/seq-contiguous? (assembly/load-prod-order)))
  (testing "a gap breaks contiguity"
    (is (not (assembly/seq-contiguous?
              {:prod-order/stations [{:station/seq 1} {:station/seq 3}]})))))

(deftest step-parts-consistent-test
  (let [bom (assembly/load-bom)
        prod-order (assembly/load-prod-order)]
    (is (assembly/step-parts-consistent? prod-order (assembly/part-ids bom)))
    (is (empty? (assembly/unresolved-parts prod-order (assembly/part-ids bom))))
    (testing "an unknown part id referenced by a station is caught"
      (is (not (assembly/step-parts-consistent? prod-order #{})))
      (is (seq (assembly/unresolved-parts prod-order #{}))))))

(deftest bom-parts-all-used-test
  (let [bom (assembly/load-bom)
        prod-order (assembly/load-prod-order)]
    (is (assembly/bom-parts-all-used? bom prod-order)
        "every BOM line is installed by at least one station -- no orphan parts")
    (is (empty? (assembly/unused-bom-parts bom prod-order)))
    (testing "a BOM part no station references is caught"
      (is (not (assembly/bom-parts-all-used?
                {:bom/parts [{:part/id "part:orphan"}]}
                prod-order))))))

(deftest ten-bom-lines-mirror-the-task-minimum
  ;; NOTE: relaxed from an exact `(= 10 (count ...))` / exact-set `=` to an
  ;; at-least/subset check when fastener-level BOM detail was added
  ;; (compressor-mount-bolt-set/motor-mount-bolt-set/condenser-coil-bolt-
  ;; set/fan-assembly-bolt-set/control-panel-fastener-set/wiring-harness-
  ;; clamp-set) -- the ORIGINAL 10 subassembly-level part ids below are
  ;; still every one present, unchanged; this test's own literal exact-
  ;; count/exact-set assertions were the one thing that could not survive
  ;; that change and still pass, so they were loosened here rather than
  ;; left broken. See `fastener-parts-are-present-and-consistent` below
  ;; for the new fastener-specific coverage this test doesn't assert.
  (let [bom (assembly/load-bom)]
    (is (<= 10 (count (assembly/bom-parts bom))))
    (is (every? string? (map :part/id (assembly/bom-parts bom))))
    (is (every? string? (map :part/name (assembly/bom-parts bom))))
    ;; the task's minimum BOM coverage: compressor body, motor, condenser
    ;; coil, fan assembly, control panel, piping, refrigerant charge.
    (is (set/subset?
         #{"part:compressor-body" "part:electric-motor" "part:condenser-coil"
           "part:fan-assembly" "part:control-panel" "part:piping"
           "part:refrigerant-charge" "part:frame" "part:vibration-isolators"
           "part:wiring-harness"}
         (assembly/part-ids bom)))))

(deftest fastener-parts-are-present-and-consistent
  (testing "fastener-level BOM detail (bolts/nuts/washers/screws for each subassembly's own fastening)"
    (let [bom (assembly/load-bom)
          prod-order (assembly/load-prod-order)
          fastener-ids #{"part:compressor-mount-bolt-set" "part:motor-mount-bolt-set"
                         "part:condenser-coil-bolt-set" "part:fan-assembly-bolt-set"
                         "part:control-panel-fastener-set" "part:wiring-harness-clamp-set"}]
      (testing "every fastener id is its own BOM line, on top of the original 10"
        (is (set/subset? fastener-ids (assembly/part-ids bom)))
        (is (= 16 (count (assembly/bom-parts bom)))
            "10 original subassembly lines + 6 new fastener lines"))
      (testing "every fastener line declares a positive qty and a name"
        (doseq [{:keys [part/id part/name part/qty]} (assembly/bom-parts bom)
                :when (contains? fastener-ids id)]
          (is (pos-int? qty) (str id " qty"))
          (is (string? name) (str id " name"))))
      (testing "every fastener id is actually referenced by at least one station -- no orphan fastener line"
        (is (set/subset? fastener-ids (assembly/used-part-ids prod-order))))
      (testing "the full BOM<->process-graph consistency checks still pass with fasteners included"
        (is (assembly/step-parts-consistent? prod-order (assembly/part-ids bom)))
        (is (assembly/bom-parts-all-used? bom prod-order))
        (is (empty? (assembly/unresolved-parts prod-order (assembly/part-ids bom))))
        (is (empty? (assembly/unused-bom-parts bom prod-order)))))))

(deftest production-line-covers-receive-through-ship-decision
  (let [prod-order (assembly/load-prod-order)]
    (is (= "receive-inspect" (:station/op (first (assembly/stations prod-order)))))
    (is (= "ship-decision" (:station/op (last (assembly/stations prod-order)))))
    (is (seq (assembly/stations-with-op prod-order "refrigerant-charge"))
        "refrigerant charge is its own station")
    (is (seq (assembly/stations-with-op prod-order "pressure-test"))
        "hydrostatic/pneumatic pressure test is its own station")
    (is (pos? (assembly/programme-seconds prod-order)))))

(deftest pressure-test-station-cross-references-the-required-evidence-string
  (let [prod-order (assembly/load-prod-order)]
    ;; data/comment-level cross-reference only (see compressor-unit-
    ;; prod-order.edn's st:pressure-test comment) -- this pins that the
    ;; STRING matches pressureequip.facts/catalog's :required-evidence
    ;; entries so the two EDN tables don't silently drift apart, without
    ;; wiring any governor code to this resource.
    (is (contains? (assembly/evidence-produced prod-order)
                    "hydrostatic-pneumatic-pressure-test-report"))))
