(ns pressureequip.governor-contract-test
  "The governor contract as executable tests -- the pressure-
  equipment-manufacturer analog of `cloud-itonami-isic-6512`'s
  `casualty.governor-contract-test`. The single invariant under test:

    Pressure Equipment Advisor never dispatches a unit action or
    issues a pressure-test certificate the Pressure Equipment
    Governor would reject, `:actuation/dispatch-unit`/`:actuation/
    issue-pressure-test-certificate` NEVER auto-commit at any phase,
    `:unit/intake` (no direct capital risk) MAY auto-commit when
    clean, and every decision (commit OR hold) leaves exactly one
    ledger fact."
  (:require [clojure.test :refer [deftest is testing]]
            [langgraph.graph :as g]
            [pressureequip.store :as store]
            [pressureequip.operation :as op]))

(defn- fresh []
  (let [db (store/seed-db)]
    [db (op/build db)]))

(def operator {:actor-id "op-1" :actor-role :pressure-equipment-engineer :phase 3})

(defn- exec-op [actor tid request context]
  (g/run* actor {:request request :context context} {:thread-id tid}))

(defn- approve! [actor tid]
  (g/run* actor {:approval {:status :approved :by "op-1"}} {:thread-id tid :resume? true}))

(defn- verify!
  "Walks `subject` through verify -> approve, leaving a requirements
  verification on file. Uses distinct thread-ids per call site by
  suffixing `tid-prefix`."
  [actor tid-prefix subject]
  (exec-op actor (str tid-prefix "-verify") {:op :design-rules/verify :subject subject} operator)
  (approve! actor (str tid-prefix "-verify")))

(defn- screen!
  "Walks `subject` through pressure-test-defect screening -> approve,
  leaving a screening on file. Only safe to call for a unit whose
  defect status has already resolved -- an unresolved defect
  HARD-holds the screen itself (see
  `pressure-test-defect-is-held-and-unoverridable`)."
  [actor tid-prefix subject]
  (exec-op actor (str tid-prefix "-screen") {:op :pressure-test/screen :subject subject} operator)
  (approve! actor (str tid-prefix "-screen")))

(deftest clean-intake-auto-commits
  (let [[db actor] (fresh)
        res (exec-op actor "t1"
                  {:op :unit/intake :subject "unit-1"
                   :patch {:id "unit-1" :unit-name "Sakura API 610 Centrifugal Pump CP-04"}} operator)]
    (is (= :commit (get-in res [:state :disposition])))
    (is (= "Sakura API 610 Centrifugal Pump CP-04" (:unit-name (store/unit db "unit-1"))) "SSoT actually updated")
    (is (= 1 (count (store/ledger db))))))

(deftest requirements-verify-always-needs-approval
  (testing "verify is never in any phase's :auto set -- always human approval, even when clean"
    (let [[db actor] (fresh)
          res (exec-op actor "t2" {:op :design-rules/verify :subject "unit-1"} operator)]
      (is (= :interrupted (:status res)))
      (let [r2 (approve! actor "t2")]
        (is (= :commit (get-in r2 [:state :disposition])))
        (is (some? (store/requirements-verification-of db "unit-1")))))))

(deftest fabricated-jurisdiction-is-held
  (testing "a design-rules/verify proposal with no official spec-basis -> HOLD, never reaches a human"
    (let [[db actor] (fresh)
          res (exec-op actor "t3"
                    {:op :design-rules/verify :subject "unit-1" :no-spec? true} operator)]
      (is (= :hold (get-in res [:state :disposition])))
      (is (some #{:no-spec-basis} (-> (store/ledger db) first :basis)))
      (is (nil? (store/requirements-verification-of db "unit-1")) "no verification written"))))

(deftest dispatch-unit-without-verification-is-held
  (testing "actuation/dispatch-unit before any requirements verification -> HOLD (evidence incomplete)"
    (let [[db actor] (fresh)
          res (exec-op actor "t4" {:op :actuation/dispatch-unit :subject "unit-1"} operator)]
      (is (= :hold (get-in res [:state :disposition])))
      (is (some #{:evidence-incomplete} (-> (store/ledger db) first :basis))))))

(deftest unit-test-pressure-out-of-range-is-held
  (testing "a unit whose own hydrostatic/pneumatic test pressure falls outside its own spec bounds -> HOLD"
    (let [[db actor] (fresh)
          _ (verify! actor "t5pre" "unit-3")
          res (exec-op actor "t5" {:op :actuation/dispatch-unit :subject "unit-3"} operator)]
      (is (= :hold (get-in res [:state :disposition])))
      (is (some #{:unit-test-pressure-out-of-range} (-> (store/ledger db) last :basis)))
      (is (empty? (store/dispatch-history db))))))

(deftest pressure-test-defect-is-held-and-unoverridable
  (testing "an unresolved pressure-test defect on a unit -> HOLD, and never reaches request-approval -- exercised via :pressure-test/screen DIRECTLY, not via the actuation op against an unscreened unit (see this actor's governor ns docstring / parksafety's ADR-2607071922 Decision 5 / turbine's and automotive's and machinetool's ADR-0001s)"
    (let [[db actor] (fresh)
          res (exec-op actor "t6" {:op :pressure-test/screen :subject "unit-4"} operator)]
      (is (= :hold (get-in res [:state :disposition])) "settles immediately, no interrupt")
      (is (not= :interrupted (:status res)))
      (is (some #{:pressure-test-defect-unresolved} (-> (store/ledger db) first :basis)))
      (is (nil? (store/pressure-screen-of db "unit-4")) "no clearance written"))))

(deftest dispatch-unit-always-escalates-then-human-decides
  (testing "a clean, fully-verified, in-spec unit still ALWAYS interrupts for human approval -- actuation/dispatch-unit is never auto"
    (let [[db actor] (fresh)
          _ (verify! actor "t7pre" "unit-1")
          r1 (exec-op actor "t7" {:op :actuation/dispatch-unit :subject "unit-1"} operator)]
      (is (= :interrupted (:status r1)) "pauses for human approval even when governor-clean")
      (testing "approve -> commit, dispatch record drafted"
        (let [r2 (approve! actor "t7")]
          (is (= :commit (get-in r2 [:state :disposition])))
          (is (true? (:unit-dispatched? (store/unit db "unit-1"))))
          (is (= 1 (count (store/dispatch-history db))) "one draft dispatch record"))))))

(def ^:private testlab-engagement-ref
  "A complete, well-formed reference into the independent third-party
  accredited testing laboratory actor `cloud-itonami-isic-7120` --
  used by every test below that needs a CLEAN `:actuation/issue-
  pressure-test-certificate` proposal (see `pressureequip.governor`
  ns docstring Addendum 5 / `testlab-engagement-ref-missing-
  violations`)."
  {:testlab-engagement-ref/id "engagement-1"
   :testlab-engagement-ref/source-actor "cloud-itonami-isic-7120"
   :testlab-engagement-ref/certification-number "JPN-CERT-000000"})

(deftest issue-pressure-test-certificate-always-escalates-then-human-decides
  (testing "a clean, fully-verified, resolved-defect unit still ALWAYS interrupts for human approval -- actuation/issue-pressure-test-certificate is never auto"
    (let [[db actor] (fresh)
          _ (verify! actor "t8pre" "unit-1")
          _ (screen! actor "t8pre2" "unit-1")
          r1 (exec-op actor "t8"
                   {:op :actuation/issue-pressure-test-certificate :subject "unit-1"
                    :certification/testlab-engagement-ref testlab-engagement-ref} operator)]
      (is (= :interrupted (:status r1)) "pauses for human approval even when governor-clean")
      (testing "approve -> commit, certificate record drafted, testlab-engagement-ref persisted onto the unit"
        (let [r2 (approve! actor "t8")]
          (is (= :commit (get-in r2 [:state :disposition])))
          (is (true? (:pressure-test-certified? (store/unit db "unit-1"))))
          (is (= 1 (count (store/evidence-history db))) "one draft certificate record")
          (is (= testlab-engagement-ref (:testlab-engagement-ref (store/unit db "unit-1")))
              "the independent third-party engagement/certification reference is retained, not just checked-then-discarded"))))))

(deftest issue-pressure-test-certificate-without-testlab-engagement-ref-is-held
  (testing "a clean, fully-verified, resolved-defect unit WITHOUT a :certification/testlab-engagement-ref -> HOLD, never reaches a human -- self-issuance alone is not sufficient (superproject independent-verification-of-self-issued-certificates ADR)"
    (let [[db actor] (fresh)
          _ (verify! actor "t8bpre" "unit-1")
          _ (screen! actor "t8bpre2" "unit-1")
          res (exec-op actor "t8b" {:op :actuation/issue-pressure-test-certificate :subject "unit-1"} operator)]
      (is (= :hold (get-in res [:state :disposition])))
      (is (not= :interrupted (:status res)))
      (is (some #{:testlab-engagement-ref-missing} (-> (store/ledger db) last :basis)))
      (is (false? (:pressure-test-certified? (store/unit db "unit-1")))))))

(deftest issue-pressure-test-certificate-with-incomplete-testlab-engagement-ref-is-held
  (testing "a :certification/testlab-engagement-ref that IS present but missing its own required identity fields -> HOLD -- a fabricated/incomplete reference is refused exactly like a missing one"
    (let [[db actor] (fresh)
          _ (verify! actor "t8cpre" "unit-1")
          _ (screen! actor "t8cpre2" "unit-1")
          res (exec-op actor "t8c"
                    {:op :actuation/issue-pressure-test-certificate :subject "unit-1"
                     :certification/testlab-engagement-ref {:testlab-engagement-ref/source-actor "cloud-itonami-isic-7120"}}
                    operator)]
      (is (= :hold (get-in res [:state :disposition])))
      (is (some #{:testlab-engagement-ref-missing} (-> (store/ledger db) last :basis)))
      (is (false? (:pressure-test-certified? (store/unit db "unit-1")))))))

(deftest dispatch-unit-double-dispatch-is-held
  (testing "dispatching the same unit's action twice -> HOLD on the second attempt"
    (let [[db actor] (fresh)
          _ (verify! actor "t9pre" "unit-1")
          _ (exec-op actor "t9a" {:op :actuation/dispatch-unit :subject "unit-1"} operator)
          _ (approve! actor "t9a")
          res (exec-op actor "t9" {:op :actuation/dispatch-unit :subject "unit-1"} operator)]
      (is (= :hold (get-in res [:state :disposition])))
      (is (some #{:already-dispatched} (-> (store/ledger db) last :basis)))
      (is (= 1 (count (store/dispatch-history db))) "still only the one earlier dispatch"))))

(deftest issue-pressure-test-certificate-double-issuance-is-held
  (testing "issuing the same unit's pressure-test certificate twice -> HOLD on the second attempt"
    (let [[db actor] (fresh)
          _ (verify! actor "t10pre" "unit-1")
          _ (screen! actor "t10pre2" "unit-1")
          _ (exec-op actor "t10a"
                   {:op :actuation/issue-pressure-test-certificate :subject "unit-1"
                    :certification/testlab-engagement-ref testlab-engagement-ref} operator)
          _ (approve! actor "t10a")
          res (exec-op actor "t10"
                    {:op :actuation/issue-pressure-test-certificate :subject "unit-1"
                     :certification/testlab-engagement-ref testlab-engagement-ref} operator)]
      (is (= :hold (get-in res [:state :disposition])))
      (is (some #{:already-certified} (-> (store/ledger db) last :basis)))
      (is (= 1 (count (store/evidence-history db))) "still only the one earlier certificate issuance"))))

(deftest issue-maintenance-notice-with-no-prior-dispatch-is-held
  (testing "issue-maintenance-notice for a unit that was never :actuation/dispatch-unit-dispatched -> HOLD, independent of the proposal's own claim"
    (let [[db actor] (fresh)
          res (exec-op actor "t12" {:op :issue-maintenance-notice :subject "unit-1"} operator)]
      (is (= :hold (get-in res [:state :disposition])))
      (is (some #{:dispatch-ref-unverified} (-> (store/ledger db) last :basis)))
      (is (empty? (store/maintenance-notice-history db))))))

(deftest issue-maintenance-notice-always-escalates-then-human-decides
  (testing "a maintenance notice referencing an ACTUALLY dispatched unit still ALWAYS interrupts for human approval -- issue-maintenance-notice is never auto"
    (let [[db actor] (fresh)
          _ (verify! actor "t13pre" "unit-1")
          _ (exec-op actor "t13disp" {:op :actuation/dispatch-unit :subject "unit-1"} operator)
          _ (approve! actor "t13disp")
          r1 (exec-op actor "t13" {:op :issue-maintenance-notice :subject "unit-1"} operator)]
      (is (= :interrupted (:status r1)) "pauses for human approval even when governor-clean")
      (testing "approve -> commit, maintenance-notice record drafted, referencing the real dispatch-ref"
        (let [r2 (approve! actor "t13")]
          (is (= :commit (get-in r2 [:state :disposition])))
          (is (= 1 (count (store/maintenance-notice-history db))) "one draft maintenance-notice record")
          (is (= (:dispatch-number (store/unit db "unit-1"))
                 (get (first (store/maintenance-notice-history db)) "dispatch_ref"))
              "the committed notice's dispatch_ref matches the unit's own real dispatch-number"))))))

(deftest issue-maintenance-notice-allows-more-than-one-per-unit
  (testing "unlike dispatch/certificate, a unit may receive more than one maintenance notice over its life"
    (let [[db actor] (fresh)
          _ (verify! actor "t14pre" "unit-1")
          _ (exec-op actor "t14disp" {:op :actuation/dispatch-unit :subject "unit-1"} operator)
          _ (approve! actor "t14disp")]
      (exec-op actor "t14a" {:op :issue-maintenance-notice :subject "unit-1"} operator)
      (approve! actor "t14a")
      (exec-op actor "t14b" {:op :issue-maintenance-notice :subject "unit-1"} operator)
      (approve! actor "t14b")
      (is (= 2 (count (store/maintenance-notice-history db)))))))

(deftest dispatch-unit-with-unregistered-unit-type-is-held
  (testing "actuation/dispatch-unit for a unit whose declared :unit-type-id does not resolve in pressureequip.facts/unit-types -> HOLD, independent of unit-3/unit-4's own hard checks"
    (let [[db actor] (fresh)]
      (store/with-units db
        {"unit-9" {:id "unit-9" :unit-name "架空型式ユニット"
                       :unit-type-id :unit/does-not-exist
                       :test-pressure-actual 13.5 :test-pressure-min 13.0 :test-pressure-max 15.0
                       :pressure-test-defect-unresolved? false
                       :unit-dispatched? false :pressure-test-certified? false
                       :jurisdiction "JPN" :status :intake}})
      (verify! actor "t11pre" "unit-9")
      (let [res (exec-op actor "t11" {:op :actuation/dispatch-unit :subject "unit-9"} operator)]
        (is (= :hold (get-in res [:state :disposition])))
        (is (some #{:unit-type-unregistered} (-> (store/ledger db) last :basis)))
        (is (empty? (store/dispatch-history db)))))))

(deftest register-equipment-asset-with-missing-fields-is-held
  (testing "a :register-equipment-asset proposal missing a required :equipment-asset/* field -> HOLD"
    (let [[db actor] (fresh)
          res (exec-op actor "t15"
                    {:op :register-equipment-asset :subject "eqa-1"
                     :equipment-asset/source-actor "cloud-itonami-isic-2822"} operator)]
      (is (= :hold (get-in res [:state :disposition])))
      (is (some #{:equipment-asset-missing-fields} (-> (store/ledger db) first :basis)))
      (is (nil? (store/equipment-asset db "eqa-1"))))))

(deftest register-equipment-asset-clean-always-escalates-then-human-decides
  (testing "a clean, fully-fielded equipment-asset registration still ALWAYS interrupts for human approval -- register-equipment-asset is never auto"
    (let [[db actor] (fresh)
          request {:op :register-equipment-asset :subject "eqa-1"
                   :equipment-asset/unit-type-id :unit/industrial-welding-cell
                   :equipment-asset/source-actor "cloud-itonami-isic-2822"
                   :equipment-asset/dispatch-ref "JPN-MTL-000000"
                   :equipment-asset/installed-at-iso "2026-07-18T00:00:00Z"
                   :equipment-asset/station-cell "cell:weld-1"}
          r1 (exec-op actor "t16" request operator)]
      (is (= :interrupted (:status r1)) "pauses for human approval even when governor-clean")
      (testing "approve -> commit, equipment-asset registered under this factory's own station-cell"
        (let [r2 (approve! actor "t16")]
          (is (= :commit (get-in r2 [:state :disposition])))
          (is (= "cloud-itonami-isic-2822" (:equipment-asset/source-actor (store/equipment-asset db "eqa-1"))))
          (is (= "cell:weld-1" (:equipment-asset/station-cell (store/equipment-asset db "eqa-1")))))))))

(deftest register-equipment-asset-double-registration-is-held
  (testing "registering the same equipment-asset id twice -> HOLD on the second attempt"
    (let [[db actor] (fresh)
          request {:op :register-equipment-asset :subject "eqa-1"
                   :equipment-asset/unit-type-id :unit/industrial-welding-cell
                   :equipment-asset/source-actor "cloud-itonami-isic-2822"
                   :equipment-asset/dispatch-ref "JPN-MTL-000000"
                   :equipment-asset/installed-at-iso "2026-07-18T00:00:00Z"
                   :equipment-asset/station-cell "cell:weld-1"}
          _ (exec-op actor "t17a" request operator)
          _ (approve! actor "t17a")
          res (exec-op actor "t17" request operator)]
      (is (= :hold (get-in res [:state :disposition])))
      (is (some #{:equipment-asset-already-registered} (-> (store/ledger db) last :basis)))
      (is (= 1 (count (store/all-equipment-assets db)))
          "still only the one earlier registration"))))

;; ───────────── Additive: part-receipt / :handoff (superproject part-supplier-linkage ADR-2800000500) ─────────────
;;
;; isic-2813's RECEIVE side of the superproject `:handoff` shared
;; shape (ADR-2607177600, isic-1075<->jsic-4721, reused as-is here),
;; for BOM consumable/component parts -- DISTINCT from
;; `:register-equipment-asset` above (fixed capital this factory
;; OPERATES).

(deftest register-part-receipt-with-missing-fields-is-held
  (testing "a :register-part-receipt proposal missing a required :part-receipt/* field -> HOLD"
    (let [[db actor] (fresh)
          res (exec-op actor "t18"
                    {:op :register-part-receipt :subject "pr-1"} operator)]
      (is (= :hold (get-in res [:state :disposition])))
      (is (some #{:part-receipt-missing-fields} (-> (store/ledger db) first :basis)))
      (is (nil? (store/part-receipt db "pr-1"))))))

(deftest register-part-receipt-without-handoff-clean-always-escalates-then-human-decides
  (testing "a part receipt with NO :handoff at all is NOT held -- :handoff is entirely optional (this actor accepts parts from any supplier, tracked or not)"
    (let [[db actor] (fresh)
          request {:op :register-part-receipt :subject "pr-2"
                   :part-receipt/part-id "part:condenser-coil" :part-receipt/qty 1}
          r1 (exec-op actor "t19" request operator)]
      (is (= :interrupted (:status r1)) "pauses for human approval even when governor-clean")
      (let [r2 (approve! actor "t19")]
        (is (= :commit (get-in r2 [:state :disposition])))
        (is (= "part:condenser-coil" (:part-receipt/part-id (store/part-receipt db "pr-2"))))
        (is (nil? (:handoff (store/part-receipt db "pr-2"))))))))

(deftest register-part-receipt-with-complete-handoff-clean-always-escalates-then-links-the-supplier
  (testing "a part receipt WITH a complete :handoff registers and carries the supplier linkage through to the SSoT -- register-part-receipt is never auto"
    (let [[db actor] (fresh)
          request {:op :register-part-receipt :subject "pr-3"
                   :part-receipt/part-id "part:electric-motor" :part-receipt/qty 1
                   :handoff {:handoff/id "ho-1"
                             :handoff/source-actor "cloud-itonami-isic-2710"
                             :handoff/batch-id "JPN-EEQ-000000"
                             :handoff/product-type-id "part:electric-motor"
                             :handoff/dispatched-at-iso "2026-07-18T00:00:00Z"}}
          r1 (exec-op actor "t20" request operator)]
      (is (= :interrupted (:status r1)) "pauses for human approval even when governor-clean")
      (let [r2 (approve! actor "t20")
            pr (store/part-receipt db "pr-3")]
        (is (= :commit (get-in r2 [:state :disposition])))
        (is (= "part:electric-motor" (:part-receipt/part-id pr)))
        (is (= "cloud-itonami-isic-2710" (:handoff/source-actor (:handoff pr))))
        (is (= "JPN-EEQ-000000" (:handoff/batch-id (:handoff pr))))))))

(deftest register-part-receipt-with-incomplete-handoff-is-held
  (testing "a :handoff that IS present but missing its own required identity fields -> HOLD, even though :handoff itself is optional"
    (let [[db actor] (fresh)
          request {:op :register-part-receipt :subject "pr-4"
                   :part-receipt/part-id "part:electric-motor"
                   :handoff {:handoff/source-actor "cloud-itonami-isic-2710"}}
          res (exec-op actor "t21" request operator)]
      (is (= :hold (get-in res [:state :disposition])))
      (is (some #{:part-receipt-handoff-incomplete} (-> (store/ledger db) first :basis)))
      (is (nil? (store/part-receipt db "pr-4"))))))

(deftest register-part-receipt-double-registration-is-held
  (testing "registering the same part-receipt id twice -> HOLD on the second attempt"
    (let [[db actor] (fresh)
          request {:op :register-part-receipt :subject "pr-5"
                   :part-receipt/part-id "part:electric-motor" :part-receipt/qty 1}
          _ (exec-op actor "t22a" request operator)
          _ (approve! actor "t22a")
          res (exec-op actor "t22" request operator)]
      (is (= :hold (get-in res [:state :disposition])))
      (is (some #{:part-receipt-already-registered} (-> (store/ledger db) last :basis)))
      (is (= 1 (count (store/all-part-receipts db)))
          "still only the one earlier registration"))))

(deftest every-decision-leaves-one-ledger-fact
  (testing "write-only-through-ledger: N operations -> N ledger facts"
    (let [[db actor] (fresh)]
      (exec-op actor "a" {:op :unit/intake :subject "unit-1"
                          :patch {:id "unit-1" :unit-name "Sakura API 610 Centrifugal Pump CP-04"}} operator)
      (exec-op actor "b" {:op :design-rules/verify :subject "unit-1" :no-spec? true} operator)
      (is (= 2 (count (store/ledger db)))
          "one commit + one hold, both recorded"))))

;; ───────────── Additive: remaining-parts supplier linkage (superproject part-supplier-linkage ADR, follow-up to ADR-2800000500/ADR-2800000600) ─────────────
;;
;; `compressor-unit-bom.edn`'s `part:frame`/`part:control-panel`/
;; `part:piping`/`part:vibration-isolators` now each name a real-world
;; supplier actor via `:part/supplier-actor`, the SAME optional key
;; `part:electric-motor` already used. The RECEIVE side is the SAME
;; `:register-part-receipt` op exercised above for `part:electric-
;; motor` -- no governor/registry/store change was needed for these
;; parts (`part-receipt-fields-present?`/`part-receipt-handoff-
;; incomplete-violations` never hardcode a specific `:part-receipt/
;; part-id` value or supplier name), so these tests exercise the
;; SAME existing checks against the new parts' handoff shapes, purely
;; as data-level coverage.

(deftest register-part-receipt-with-complete-handoff-for-frame-from-isic-2410
  (testing "a part receipt for part:frame WITH a complete :handoff from cloud-itonami-isic-2410 registers and carries the supplier linkage through to the SSoT"
    (let [[db actor] (fresh)
          request {:op :register-part-receipt :subject "pr-6"
                   :part-receipt/part-id "part:frame" :part-receipt/qty 1
                   :handoff {:handoff/id "ho-5"
                             :handoff/source-actor "cloud-itonami-isic-2410"
                             :handoff/batch-id "JPN-HET-000000"
                             :handoff/product-type-id "part:frame"
                             :handoff/dispatched-at-iso "2026-07-18T00:00:00Z"}}
          r1 (exec-op actor "t23" request operator)]
      (is (= :interrupted (:status r1)) "pauses for human approval even when governor-clean")
      (let [r2 (approve! actor "t23")
            pr (store/part-receipt db "pr-6")]
        (is (= :commit (get-in r2 [:state :disposition])))
        (is (= "part:frame" (:part-receipt/part-id pr)))
        (is (= "cloud-itonami-isic-2410" (:handoff/source-actor (:handoff pr))))))))

(deftest register-part-receipt-with-complete-handoff-for-control-panel-from-isic-2610
  (testing "a part receipt for part:control-panel WITH a complete :handoff from cloud-itonami-isic-2610 registers and carries the supplier linkage through to the SSoT"
    (let [[db actor] (fresh)
          request {:op :register-part-receipt :subject "pr-7"
                   :part-receipt/part-id "part:control-panel" :part-receipt/qty 1
                   :handoff {:handoff/id "ho-6"
                             :handoff/source-actor "cloud-itonami-isic-2610"
                             :handoff/batch-id "JPN-YLD-000000"
                             :handoff/product-type-id "part:control-panel"
                             :handoff/dispatched-at-iso "2026-07-18T00:00:00Z"}}
          r1 (exec-op actor "t24" request operator)]
      (is (= :interrupted (:status r1)) "pauses for human approval even when governor-clean")
      (let [r2 (approve! actor "t24")
            pr (store/part-receipt db "pr-7")]
        (is (= :commit (get-in r2 [:state :disposition])))
        (is (= "part:control-panel" (:part-receipt/part-id pr)))
        (is (= "cloud-itonami-isic-2610" (:handoff/source-actor (:handoff pr))))))))

(deftest register-part-receipt-with-complete-handoff-for-piping-and-vibration-isolators-from-isic-2599
  (testing "part receipts for part:piping and part:vibration-isolators WITH complete :handoff from cloud-itonami-isic-2599 each register and carry the supplier linkage through to the SSoT"
    (let [[db actor] (fresh)
          piping-request {:op :register-part-receipt :subject "pr-8"
                          :part-receipt/part-id "part:piping" :part-receipt/qty 1
                          :handoff {:handoff/id "ho-7"
                                    :handoff/source-actor "cloud-itonami-isic-2599"
                                    :handoff/batch-id "SHP-000001"
                                    :handoff/product-type-id "part:piping"
                                    :handoff/dispatched-at-iso "2026-07-18T00:00:00Z"}}
          viso-request {:op :register-part-receipt :subject "pr-9"
                        :part-receipt/part-id "part:vibration-isolators" :part-receipt/qty 4
                        :handoff {:handoff/id "ho-8"
                                  :handoff/source-actor "cloud-itonami-isic-2599"
                                  :handoff/batch-id "SHP-000002"
                                  :handoff/product-type-id "part:vibration-isolators"
                                  :handoff/dispatched-at-iso "2026-07-18T00:00:00Z"}}
          r1 (exec-op actor "t25a" piping-request operator)
          _ (approve! actor "t25a")
          r2 (exec-op actor "t25b" viso-request operator)]
      (is (= :interrupted (:status r1)))
      (is (= :interrupted (:status r2)))
      (let [r2b (approve! actor "t25b")
            piping-pr (store/part-receipt db "pr-8")
            viso-pr (store/part-receipt db "pr-9")]
        (is (= :commit (get-in r2b [:state :disposition])))
        (is (= "cloud-itonami-isic-2599" (:handoff/source-actor (:handoff piping-pr))))
        (is (= "cloud-itonami-isic-2599" (:handoff/source-actor (:handoff viso-pr))))))))
