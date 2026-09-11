(ns pressureequip.registry-test
  (:require [clojure.test :refer [deftest is]]
            [pressureequip.registry :as r]))

;; ----------------------------- unit-test-pressure-out-of-range? -----------------------------

(deftest not-out-of-range-when-within-bounds
  (is (not (r/unit-test-pressure-out-of-range? {:test-pressure-actual 13.5 :test-pressure-min 13.0 :test-pressure-max 15.0})))
  (is (not (r/unit-test-pressure-out-of-range? {:test-pressure-actual 13.0 :test-pressure-min 13.0 :test-pressure-max 15.0})))
  (is (not (r/unit-test-pressure-out-of-range? {:test-pressure-actual 15.0 :test-pressure-min 13.0 :test-pressure-max 15.0}))))

(deftest out-of-range-when-below-minimum-or-above-maximum
  (is (r/unit-test-pressure-out-of-range? {:test-pressure-actual 10.0 :test-pressure-min 13.0 :test-pressure-max 15.0}))
  (is (r/unit-test-pressure-out-of-range? {:test-pressure-actual 18.0 :test-pressure-min 13.0 :test-pressure-max 15.0})))

(deftest out-of-range-is-false-on-missing-fields
  (is (not (r/unit-test-pressure-out-of-range? {})))
  (is (not (r/unit-test-pressure-out-of-range? {:test-pressure-actual 18.0}))))

;; ----------------------------- register-unit-dispatch -----------------------------

(deftest dispatch-is-a-draft-not-a-real-dispatch
  (let [result (r/register-unit-dispatch "unit-1" "JPN" 0)]
    (is (nil? (get-in result ["certificate" "proof"])))
    (is (= (get-in result ["certificate" "issued_by_registry"]) false))
    (is (= (get-in result ["certificate" "status"]) "draft-unsigned"))))

(deftest dispatch-assigns-dispatch-number
  (let [result (r/register-unit-dispatch "unit-1" "JPN" 7)]
    (is (= (get result "dispatch_number") "JPN-PEQ-000007"))
    (is (= (get-in result ["record" "unit_id"]) "unit-1"))
    (is (= (get-in result ["record" "kind"]) "unit-dispatch-draft"))
    (is (= (get-in result ["record" "immutable"]) true))))

(deftest dispatch-validation-rules
  (is (thrown? Exception (r/register-unit-dispatch "" "JPN" 0)))
  (is (thrown? Exception (r/register-unit-dispatch "unit-1" "" 0)))
  (is (thrown? Exception (r/register-unit-dispatch "unit-1" "JPN" -1))))

;; ----------------------------- register-pressure-test-certificate -----------------------------

(deftest certificate-is-a-draft-not-real-certification
  (let [result (r/register-pressure-test-certificate "unit-1" "JPN" 0)]
    (is (nil? (get-in result ["certificate" "proof"])))
    (is (= (get-in result ["certificate" "issued_by_registry"]) false))
    (is (= (get-in result ["certificate" "status"]) "draft-unsigned"))))

(deftest certificate-assigns-evidence-number
  (let [result (r/register-pressure-test-certificate "unit-1" "JPN" 3)]
    (is (= (get result "evidence_number") "JPN-PTC-000003"))
    (is (= (get-in result ["record" "unit_id"]) "unit-1"))
    (is (= (get-in result ["record" "kind"]) "pressure-test-certificate-draft"))
    (is (= (get-in result ["record" "immutable"]) true))))

(deftest certificate-validation-rules
  (is (thrown? Exception (r/register-pressure-test-certificate "" "JPN" 0)))
  (is (thrown? Exception (r/register-pressure-test-certificate "unit-1" "" 0)))
  (is (thrown? Exception (r/register-pressure-test-certificate "unit-1" "JPN" -1))))

;; ----------------------------- register-maintenance-notice -----------------------------

(deftest maintenance-notice-is-a-draft-not-a-real-notice
  (let [result (r/register-maintenance-notice "unit-1" "JPN-PEQ-000000" "JPN" 0)]
    (is (nil? (get-in result ["certificate" "proof"])))
    (is (= (get-in result ["certificate" "issued_by_registry"]) false))
    (is (= (get-in result ["certificate" "status"]) "draft-unsigned"))))

(deftest maintenance-notice-assigns-notice-number-and-carries-dispatch-ref
  (let [result (r/register-maintenance-notice "unit-1" "JPN-PEQ-000000" "JPN" 5)]
    (is (= (get result "notice_number") "JPN-PMN-000005"))
    (is (= (get-in result ["record" "unit_id"]) "unit-1"))
    (is (= (get-in result ["record" "dispatch_ref"]) "JPN-PEQ-000000"))
    (is (= (get-in result ["record" "kind"]) "maintenance-notice-draft"))
    (is (= (get-in result ["record" "immutable"]) true))))

(deftest maintenance-notice-validation-rules
  (is (thrown? Exception (r/register-maintenance-notice "" "JPN-PEQ-000000" "JPN" 0)))
  (is (thrown? Exception (r/register-maintenance-notice "unit-1" "" "JPN" 0)))
  (is (thrown? Exception (r/register-maintenance-notice "unit-1" "JPN-PEQ-000000" "" 0)))
  (is (thrown? Exception (r/register-maintenance-notice "unit-1" "JPN-PEQ-000000" "JPN" -1))))

(deftest history-is-append-only
  (let [c1 (r/register-unit-dispatch "unit-1" "JPN" 0)
        hist (r/append [] c1)
        c2 (r/register-unit-dispatch "unit-2" "JPN" 1)
        hist2 (r/append hist c2)]
    (is (= 2 (count hist2)))
    (is (= "JPN-PEQ-000000" (get-in hist2 [0 "record_id"])))
    (is (= "JPN-PEQ-000001" (get-in hist2 [1 "record_id"])))))
