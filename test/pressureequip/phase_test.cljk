(ns pressureequip.phase-test
  "The phase table as executable tests. The invariant this repo cannot
  regress on: `:actuation/dispatch-unit`/`:actuation/issue-pressure-
  test-certificate` must NEVER be a member of any phase's `:auto` set."
  (:require [clojure.test :refer [deftest is testing]]
            [pressureequip.phase :as phase]))

(deftest dispatch-unit-never-auto-at-any-phase
  (testing "structural invariant: no phase, now or in the future entries, auto-commits a real robot unit dispatch"
    (doseq [[n {:keys [auto]}] phase/phases]
      (is (not (contains? auto :actuation/dispatch-unit))
          (str "phase " n " must not auto-commit :actuation/dispatch-unit")))))

(deftest issue-pressure-test-certificate-never-auto-at-any-phase
  (testing "structural invariant: no phase auto-commits a real pressure-test certificate"
    (doseq [[n {:keys [auto]}] phase/phases]
      (is (not (contains? auto :actuation/issue-pressure-test-certificate))
          (str "phase " n " must not auto-commit :actuation/issue-pressure-test-certificate")))))

(deftest issue-maintenance-notice-never-auto-at-any-phase
  (testing "a maintenance/recall notice about equipment already in the field is never auto-eligible, same posture as the two actuation ops"
    (doseq [[n {:keys [auto]}] phase/phases]
      (is (not (contains? auto :issue-maintenance-notice))
          (str "phase " n " must not auto-commit :issue-maintenance-notice")))))

(deftest pressure-test-screen-never-auto-at-any-phase
  (testing "screening carries no direct capital risk, but is still never auto-eligible, matching every sibling screening op in this fleet"
    (doseq [[n {:keys [auto]}] phase/phases]
      (is (not (contains? auto :pressure-test/screen))
          (str "phase " n " must not auto-commit :pressure-test/screen")))))

(deftest register-equipment-asset-never-auto-at-any-phase
  (testing "isic-2813's RECEIVE side of the superproject equipment-asset shared shape is never auto-eligible either, even though it is not in governor/high-stakes -- phase 3's :auto set has only ever had :unit/intake"
    (doseq [[n {:keys [auto]}] phase/phases]
      (is (not (contains? auto :register-equipment-asset))
          (str "phase " n " must not auto-commit :register-equipment-asset")))))

(deftest register-part-receipt-never-auto-at-any-phase
  (testing "isic-2813's RECEIVE side of the superproject part-supplier-linkage shape (ADR-2800000500) is never auto-eligible either, even though it is not in governor/high-stakes -- phase 3's :auto set has only ever had :unit/intake"
    (doseq [[n {:keys [auto]}] phase/phases]
      (is (not (contains? auto :register-part-receipt))
          (str "phase " n " must not auto-commit :register-part-receipt")))))

(deftest phase-0-is-fully-read-only
  (is (empty? (:writes (get phase/phases 0)))))

(deftest phase-3-auto-commits-only-no-capital-risk-ops
  (testing ":unit/intake carries no direct capital risk -- auto-eligible; it is the ONLY auto-eligible op in this domain"
    (is (= #{:unit/intake} (:auto (get phase/phases 3))))))

(deftest gate-hold-always-wins
  (is (= :hold (:disposition (phase/gate 3 {:op :unit/intake} :hold)))))

(deftest gate-escalates-a-clean-non-auto-write
  (is (= :escalate (:disposition (phase/gate 3 {:op :actuation/dispatch-unit} :commit))))
  (is (= :escalate (:disposition (phase/gate 3 {:op :actuation/issue-pressure-test-certificate} :commit))))
  (is (= :escalate (:disposition (phase/gate 3 {:op :issue-maintenance-notice} :commit))))
  (is (= :escalate (:disposition (phase/gate 3 {:op :register-equipment-asset} :commit))))
  (is (= :escalate (:disposition (phase/gate 3 {:op :register-part-receipt} :commit)))))

(deftest gate-holds-a-write-disabled-in-this-phase
  (is (= :hold (:disposition (phase/gate 0 {:op :unit/intake} :commit)))))

(deftest register-part-receipt-disabled-before-phase-3
  (testing "the SAME phase-membership posture as :register-equipment-asset -- only :write-ops-eligible from phase 3"
    (is (not (contains? (:writes (get phase/phases 1)) :register-part-receipt)))
    (is (not (contains? (:writes (get phase/phases 2)) :register-part-receipt)))
    (is (contains? (:writes (get phase/phases 3)) :register-part-receipt))))
