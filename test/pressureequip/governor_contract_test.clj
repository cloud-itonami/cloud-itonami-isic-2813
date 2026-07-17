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

(deftest issue-pressure-test-certificate-always-escalates-then-human-decides
  (testing "a clean, fully-verified, resolved-defect unit still ALWAYS interrupts for human approval -- actuation/issue-pressure-test-certificate is never auto"
    (let [[db actor] (fresh)
          _ (verify! actor "t8pre" "unit-1")
          _ (screen! actor "t8pre2" "unit-1")
          r1 (exec-op actor "t8" {:op :actuation/issue-pressure-test-certificate :subject "unit-1"} operator)]
      (is (= :interrupted (:status r1)) "pauses for human approval even when governor-clean")
      (testing "approve -> commit, certificate record drafted"
        (let [r2 (approve! actor "t8")]
          (is (= :commit (get-in r2 [:state :disposition])))
          (is (true? (:pressure-test-certified? (store/unit db "unit-1"))))
          (is (= 1 (count (store/evidence-history db))) "one draft certificate record"))))))

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
          _ (exec-op actor "t10a" {:op :actuation/issue-pressure-test-certificate :subject "unit-1"} operator)
          _ (approve! actor "t10a")
          res (exec-op actor "t10" {:op :actuation/issue-pressure-test-certificate :subject "unit-1"} operator)]
      (is (= :hold (get-in res [:state :disposition])))
      (is (some #{:already-certified} (-> (store/ledger db) last :basis)))
      (is (= 1 (count (store/evidence-history db))) "still only the one earlier certificate issuance"))))

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

(deftest every-decision-leaves-one-ledger-fact
  (testing "write-only-through-ledger: N operations -> N ledger facts"
    (let [[db actor] (fresh)]
      (exec-op actor "a" {:op :unit/intake :subject "unit-1"
                          :patch {:id "unit-1" :unit-name "Sakura API 610 Centrifugal Pump CP-04"}} operator)
      (exec-op actor "b" {:op :design-rules/verify :subject "unit-1" :no-spec? true} operator)
      (is (= 2 (count (store/ledger db)))
          "one commit + one hold, both recorded"))))
