(ns pressureequip.facts-test
  (:require [clojure.test :refer [deftest is]]
            [pressureequip.facts :as facts]))

(deftest jpn-has-a-spec-basis
  (is (some? (facts/spec-basis "JPN")))
  (is (string? (:provenance (facts/spec-basis "JPN")))))

(deftest unknown-jurisdiction-has-no-fabricated-spec-basis
  (is (nil? (facts/spec-basis "ATL"))))

;; ──────────────── CHN (5th seed jurisdiction, TSG 07-2019) ────────────────

(deftest chn-has-a-spec-basis
  (is (some? (facts/spec-basis "CHN")))
  (is (string? (:provenance (facts/spec-basis "CHN")))))

(deftest chn-spec-basis-has-the-same-shape-as-the-other-four-jurisdictions
  (let [chn (facts/spec-basis "CHN")]
    (is (= "China" (:name chn)))
    (is (re-find #"SAMR" (:owner-authority chn))
        "owner-authority names the issuing authority (SAMR / 国家市场监督管理总局)")
    (is (re-find #"TSG 07-2019" (:legal-basis chn))
        "legal-basis cites the fetched-and-read production/filling licensing rules")
    (is (string? (:national-spec chn)))
    (is (= 4 (count (:required-evidence chn)))
        "same required-evidence cardinality as JPN/USA/GBR/DEU")
    (is (= #{:name :owner-authority :legal-basis :national-spec :provenance :required-evidence}
           (set (keys chn)))
        "CHN entry has exactly the same map shape/keys as the other four jurisdictions")))

(deftest chn-required-evidence-satisfied-needs-every-item
  (let [all (facts/evidence-checklist "CHN")]
    (is (= 4 (count all)))
    (is (facts/required-evidence-satisfied? "CHN" all))
    (is (not (facts/required-evidence-satisfied? "CHN" (rest all))))))

(deftest coverage-never-reports-a-missing-jurisdiction-as-covered
  (let [report (facts/coverage ["JPN" "ATL" "GBR"])]
    (is (= 2 (:covered report)))
    (is (= ["ATL"] (:missing-jurisdictions report)))
    (is (= ["GBR" "JPN"] (:covered-jurisdictions report)))))

(deftest coverage-with-no-args-reports-all-five-seeded-jurisdictions-including-chn
  (let [report (facts/coverage)]
    (is (= 5 (:covered report)))
    (is (= ["CHN" "DEU" "GBR" "JPN" "USA"] (:covered-jurisdictions report)))))

(deftest required-evidence-satisfied-needs-every-item
  (let [all (facts/evidence-checklist "JPN")]
    (is (facts/required-evidence-satisfied? "JPN" all))
    (is (not (facts/required-evidence-satisfied? "JPN" (rest all))))
    (is (not (facts/required-evidence-satisfied? "ATL" all)) "no spec-basis -> never satisfied")))

;; ──────────────── Unit-Model Catalog (UNSPSC/GTIN linkage) ────────────────

(deftest industrial-refrigeration-compressor-unit-type-has-unspsc-and-gtin
  (let [u (facts/unit-type-by-id :unit/industrial-refrigeration-compressor)]
    (is (some? u))
    (is (= "40101704" (:unspsc-code u)) "UNSPSC commodity code (class 40101700, 'Condensing units')")
    (is (= "0212813000010" (:gtin u)))
    (is (= :unissued-blueprint-placeholder (:gtin/status u))
        "placeholder GTIN is never presented as a real, GS1-issued identifier")
    (is (number? (:power-requirement-kw u)) "power draw, for the isic-3510 grid-outage crossref")))

(deftest unknown-unit-type-has-no-fabricated-entry
  (is (nil? (facts/unit-type-by-id :unit/does-not-exist))))
