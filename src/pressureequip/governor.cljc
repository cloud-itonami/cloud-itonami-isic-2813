(ns pressureequip.governor
  "Pressure Equipment Governor -- the independent compliance layer
  that earns the Pressure Equipment Advisor the right to commit. The
  LLM has no notion of design-rules law, whether a unit's own measured
  hydrostatic/pneumatic acceptance test pressure actually stays
  within its own recorded spec bounds, whether a pressure-test-
  detected defect against the unit has actually stayed unresolved, or
  when an act stops being a draft and becomes a real-world robot unit
  dispatch or pressure-test-certificate issuance, so this MUST be a
  separate system able to *reject* a proposal and fall back to HOLD --
  the pressure-equipment-manufacturer analog of
  `cloud-itonami-isic-6512`'s CasualtyGovernor.

  Six checks, in priority order, ALL HARD violations: a human approver
  CANNOT override them (you don't get to approve your way past a
  fabricated design-rules spec-basis, incomplete evidence, an
  out-of-window test pressure, an unresolved pressure-test defect, or
  a double dispatch/certificate-issuance). The confidence/actuation
  gate is SOFT: it asks a human to look (low confidence / actuation),
  and the human may approve -- but see `pressureequip.phase`: for
  `:stake :actuation/dispatch-unit`/`:actuation/issue-pressure-test-
  certificate` (a real safety-critical act) NO phase ever allows
  auto-commit either. Two independent layers agree that actuation is
  always a human call.

    1. Spec-basis                  -- did the requirements proposal cite
                                       an OFFICIAL source (`pressureequip.
                                       facts`), or invent one?
    2. Evidence incomplete         -- for `:actuation/dispatch-
                                       unit`/`:actuation/issue-
                                       pressure-test-certificate`, has
                                       the unit actually been verified
                                       with a full hydrostatic-
                                       pneumatic-pressure-test-report/
                                       material-certification-record/
                                       weld-procedure-qualification-
                                       record/design-calculation-
                                       conformity-record evidence
                                       checklist on file?
    3. Test pressure out of range  -- for `:actuation/dispatch-
                                       unit`, INDEPENDENTLY
                                       recompute whether the
                                       unit's own measured
                                       hydrostatic/pneumatic
                                       acceptance test pressure
                                       falls outside its own
                                       recorded spec bounds
                                       (`pressureequip.registry/
                                       unit-test-pressure-out-of-
                                       range?`) -- needs no proposal
                                       inspection or stored-verdict
                                       lookup at all. One of this
                                       fleet's two-sided range check
                                       family (`testlab.governor/
                                       within-tolerance-violations`/
                                       `conservation.governor/body-
                                       condition-out-of-range-
                                       violations`/`water.governor/
                                       contaminant-level-out-of-range-
                                       violations`/`steelworks.
                                       governor`/`turbine.governor`/
                                       `automotive.governor`/
                                       `machinetool.governor`/
                                       `heavyequip.governor`
                                       established the priors).
    4. Pressure-test defect
       unresolved                  -- reported by THIS proposal itself
                                       (a `:pressure-test/screen` that
                                       just found an unresolved
                                       defect), or already on file
                                       for the unit (`:pressure-
                                       test/screen`/`:actuation/
                                       issue-pressure-test-
                                       certificate`). Evaluated
                                       UNCONDITIONALLY (not scoped to
                                       a specific op), the SAME
                                       discipline `casualty.
                                       governor/sanctions-
                                       violations`/...(prior
                                       siblings)... established --
                                       exercised in tests/demo via
                                       `:pressure-test/screen`
                                       DIRECTLY, not via an actuation
                                       op against an unscreened
                                       unit -- see this ns's own
                                       test suite.
    5. Confidence floor / actuation
       gate                          -- LLM confidence below threshold,
                                       OR the op is `:actuation/
                                       dispatch-unit`/`:actuation/
                                       issue-pressure-test-
                                       certificate` (REAL
                                       safety-critical acts) ->
                                       escalate.

  Two more guards, double-dispatch/double-certificate-issuance
  prevention, are enforced but NOT listed as numbered HARD checks
  above because they need no upstream comparison at all --
  `already-dispatched-violations`/`already-certified-violations`
  refuse to dispatch a unit action/issue a pressure-test certificate
  for the SAME unit twice, off dedicated `:unit-dispatched?`/
  `:pressure-test-certified?` facts (never a `:status` value) -- the
  SAME 'check a dedicated boolean, not status' discipline every prior
  sibling governor's guards establish, informed by `cloud-itonami-
  isic-6492`'s status-lifecycle bug (ADR-2607071320)."
  (:require [pressureequip.facts :as facts]
            [pressureequip.registry :as registry]
            [pressureequip.store :as store]))

(def confidence-floor 0.6)

(def high-stakes
  "Stakes grave enough to always require a human, even when clean.
  Dispatching a real robot unit action on a pump/compressor/valve
  unit and issuing a real ASME BPVC/PED pressure-test certificate are
  the two real-world actuation events this actor performs -- a
  two-member set, matching every prior dual-actuation sibling's
  shape."
  #{:actuation/dispatch-unit :actuation/issue-pressure-test-certificate})

;; ----------------------------- checks -----------------------------

(defn- spec-basis-violations
  "A `:design-rules/verify` (or actuation) proposal with no
  spec-basis citation is a HARD violation -- never invent a
  jurisdiction's design-rules requirements."
  [{:keys [op]} proposal]
  (when (contains? #{:design-rules/verify :actuation/dispatch-unit :actuation/issue-pressure-test-certificate} op)
    (let [value (:value proposal)]
      (when (or (empty? (:cites proposal))
                (and (contains? value :spec-basis) (nil? (:spec-basis value))))
        [{:rule :no-spec-basis
          :detail "公式spec-basisの引用が無い提案は設計規則要件として扱えない"}]))))

(defn- evidence-incomplete-violations
  "For `:actuation/dispatch-unit`/`:actuation/issue-pressure-test-
  certificate`, the jurisdiction's required hydrostatic-pneumatic-
  pressure-test-report/material-certification-record/weld-procedure-
  qualification-record/design-calculation-conformity-record evidence
  must actually be satisfied -- do not trust the advisor's
  self-reported confidence alone."
  [{:keys [op subject]} st]
  (when (contains? #{:actuation/dispatch-unit :actuation/issue-pressure-test-certificate} op)
    (let [a (store/unit st subject)
          verification (store/requirements-verification-of st subject)]
      (when-not (and verification
                     (facts/required-evidence-satisfied?
                      (:jurisdiction a) (:checklist verification)))
        [{:rule :evidence-incomplete
          :detail "法域の必要書類(耐圧試験報告書/材料証明記録/溶接施工記録/設計計算適合記録等)が充足していない状態での提案"}]))))

(defn- unit-test-pressure-out-of-range-violations
  "For `:actuation/dispatch-unit`, INDEPENDENTLY recompute whether
  the unit's own hydrostatic/pneumatic acceptance test pressure falls
  outside its own recorded spec bounds via `pressureequip.registry/
  unit-test-pressure-out-of-range?` -- needs no proposal inspection or
  stored-verdict lookup at all, since its inputs are permanent
  ground-truth fields already on the unit."
  [{:keys [op subject]} st]
  (when (= op :actuation/dispatch-unit)
    (let [a (store/unit st subject)]
      (when (registry/unit-test-pressure-out-of-range? a)
        [{:rule :unit-test-pressure-out-of-range
          :detail (str subject " の実測耐圧試験圧力(" (:test-pressure-actual a)
                      ")が仕様範囲[" (:test-pressure-min a) "," (:test-pressure-max a) "]を逸脱")}]))))

(defn- pressure-test-defect-unresolved-violations
  "An unresolved pressure-test-detected defect (leak, deformation or
  other acceptance-test failure) -- reported by THIS proposal (e.g. a
  `:pressure-test/screen` that itself just found one), or already on
  file in the store for the unit (`:pressure-test/screen`/
  `:actuation/issue-pressure-test-certificate`) -- is a HARD,
  un-overridable hold. Evaluated UNCONDITIONALLY (not scoped to a
  specific op) so the screening op itself can HARD-hold on its own
  finding."
  [{:keys [op subject]} proposal st]
  (let [hit-in-proposal? (= :unresolved (get-in proposal [:value :verdict]))
        unit-id (when (contains? #{:pressure-test/screen :actuation/issue-pressure-test-certificate} op) subject)
        hit-on-file? (and unit-id (= :unresolved (:verdict (store/pressure-screen-of st unit-id))))]
    (when (or hit-in-proposal? hit-on-file?)
      [{:rule :pressure-test-defect-unresolved
        :detail "未解決の耐圧試験欠陥がある状態での耐圧証明書発行提案は進められない"}])))

(defn- already-dispatched-violations
  "For `:actuation/dispatch-unit`, refuses to dispatch a unit
  action for the SAME unit twice, off a dedicated `:unit-
  dispatched?` fact (never a `:status` value)."
  [{:keys [op subject]} st]
  (when (= op :actuation/dispatch-unit)
    (when (store/unit-already-dispatched? st subject)
      [{:rule :already-dispatched
        :detail (str subject " は既に完成機実行済み")}])))

(defn- already-certified-violations
  "For `:actuation/issue-pressure-test-certificate`, refuses to issue
  a pressure-test certificate for the SAME unit twice, off a
  dedicated `:pressure-test-certified?` fact (never a `:status`
  value)."
  [{:keys [op subject]} st]
  (when (= op :actuation/issue-pressure-test-certificate)
    (when (store/unit-already-certified? st subject)
      [{:rule :already-certified
        :detail (str subject " は既に耐圧証明書発行済み")}])))

(defn check
  "Censors a Pressure Equipment Advisor proposal against the governor
  rules. Returns {:ok? bool :violations [..] :confidence c :escalate?
  bool :high-stakes? bool :hard? bool}."
  [request _context proposal st]
  (let [hard (into []
                   (concat (spec-basis-violations request proposal)
                           (evidence-incomplete-violations request st)
                           (unit-test-pressure-out-of-range-violations request st)
                           (pressure-test-defect-unresolved-violations request proposal st)
                           (already-dispatched-violations request st)
                           (already-certified-violations request st)))
        conf (:confidence proposal 0.0)
        low? (< conf confidence-floor)
        stakes? (boolean (high-stakes (:stake proposal)))
        hard? (boolean (seq hard))]
    {:ok?          (and (not hard?) (not low?) (not stakes?))
     :violations   hard
     :confidence   conf
     :hard?        hard?
     :escalate?    (and (not hard?) (or low? stakes?))
     :high-stakes? stakes?}))

(defn hold-fact
  "The audit fact written when a proposal is rejected (HOLD)."
  [request context verdict]
  {:t          :governor-hold
   :op         (:op request)
   :actor      (:actor-id context)
   :subject    (:subject request)
   :disposition :hold
   :basis      (mapv :rule (:violations verdict))
   :violations (:violations verdict)
   :confidence (:confidence verdict)})
