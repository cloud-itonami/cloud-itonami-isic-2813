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
  isic-6492`'s status-lifecycle bug (ADR-2607071320).

  Addendum (superproject UNSPSC/GTIN-linkage ADR): a SEVENTH HARD
  check, `unit-type-unregistered-violations`, was added alongside
  `pressureequip.facts/unit-types` (a concrete-unit-model catalog
  carrying UNSPSC/GTIN classification data, a separate catalog from
  the per-jurisdiction `catalog` the six checks above already use).
  For `:actuation/dispatch-unit`, when the unit declares a
  `:unit-type-id`, INDEPENDENTLY verify it actually resolves in that
  catalog -- purely additive (no `:unit-type-id` declared -> no
  violation), so it changes no existing unit's disposition.

  Addendum 2 (superproject equipment-asset-linkage ADR,
  cloud-itonami-isic-2813<->cloud-itonami-jsic-4721): an EIGHTH HARD
  check, `dispatch-ref-unverified-violations`, was added alongside the
  new `:issue-maintenance-notice` op. For `:issue-maintenance-notice`,
  INDEPENDENTLY verify (never trust the proposal's own echo) that the
  unit this notice names was actually already `:actuation/dispatch-
  unit`-dispatched by THIS actor and that the proposal's claimed
  `:dispatch-ref` matches the unit's own recorded `:dispatch-number`
  -- the same 'never trust a reference to a prior record, always
  re-look-it-up' discipline `unit-type-unregistered-violations`
  established for a unit-type reference, applied here to a
  dispatch-ref reference. `:issue-maintenance-notice` also joins
  `high-stakes` (below): a maintenance/recall notice about equipment
  already in the field always escalates to a human, exactly like the
  two actuation ops.

  Addendum 3 (superproject equipment-asset-linkage ADR,
  cloud-itonami-isic-2822<->cloud-itonami-isic-2813): a NINTH and
  TENTH HARD check, `equipment-asset-missing-fields-violations`/
  `equipment-asset-already-registered-violations`, were added
  alongside a new `:register-equipment-asset` op -- isic-2813's own
  RECEIVE side of the SAME `:equipment-asset` shared shape it is
  already the ISSUER side of toward cloud-itonami-jsic-4721 (Addendum
  2 above). This actor plays BOTH roles in the fleet: it manufactures
  pressure equipment (issuing maintenance notices about units it
  dispatched) AND it operates its own factory floor, which receives
  machine tools/welding cells manufactured by an upstream actor (e.g.
  cloud-itonami-isic-2822) and must register them as equipment assets
  tied to one of THIS factory's own `:station/cell`s
  (resources/pressureequip/compressor-unit-prod-order.edn). Independently
  mirrors cloud-itonami-jsic-4721's own `:register-equipment-asset`
  governor rule (no shared code, same required-field set and the same
  double-registration-guard discipline `already-dispatched-
  violations`/`already-certified-violations` already establish in this
  ns): (1) missing-fields -- a proposal missing any of `:equipment-
  asset/id`/`:equipment-asset/unit-type-id`/`:equipment-asset/source-
  actor`/`:equipment-asset/dispatch-ref` is refused, this actor never
  registers a partial/fabricated equipment-asset record; (2) already-
  registered -- the same id may never be registered twice. Deliberately
  NOT added to `high-stakes` (unlike the two actuation ops and
  `:issue-maintenance-notice`) -- the real safety-critical act (machine-
  tool dispatch) already happened on the UPSTREAM actor's own actuation-
  gated side; this op is bookkeeping registration of an asset already
  dispatched, mirroring jsic-4721's own non-high-stakes treatment of
  the identical op. It still never auto-commits in practice, because
  `pressureequip.phase`'s phase-3 `:auto` set has only ever had the one
  `:unit/intake` member."
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
  #{:actuation/dispatch-unit :actuation/issue-pressure-test-certificate
    :issue-maintenance-notice})

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

(defn- unit-type-unregistered-violations
  "For `:actuation/dispatch-unit`, when the unit declares a
  `:unit-type-id` (an OPTIONAL reference into `pressureequip.facts/
  unit-types`, the concrete-unit-model catalog carrying UNSPSC/GTIN
  classification data), INDEPENDENTLY verify that reference actually
  resolves via `facts/unit-type-by-id` -- the SAME anti-fabrication
  discipline `spec-basis-violations` applies to a jurisdiction
  citation, applied here to a unit-type reference: never trust a
  proposal's/unit's say-so that a `:unit-type-id` is real without
  looking it up. A unit with NO `:unit-type-id` declared (every
  pre-existing/legacy unit in this store, since the field is new,
  additive wiring, not a retroactive requirement) is NOT a violation --
  only a PRESENT-but-unresolvable reference (fabricated or mistyped)
  HARD-holds."
  [{:keys [op subject]} st]
  (when (= op :actuation/dispatch-unit)
    (let [a (store/unit st subject)
          unit-type-id (:unit-type-id a)]
      (when (and (some? unit-type-id) (nil? (facts/unit-type-by-id unit-type-id)))
        [{:rule :unit-type-unregistered
          :detail (str subject " の :unit-type-id (" unit-type-id
                      ") が pressureequip.facts/unit-types に存在しない -- 架空/誤記のユニット型式参照")}]))))

(defn- dispatch-ref-unverified-violations
  "For `:issue-maintenance-notice`, INDEPENDENTLY verify that the unit
  named by `subject` was actually already `:actuation/dispatch-unit`-
  dispatched by THIS actor, and that the proposal's claimed
  `:dispatch-ref` (`(:value proposal)`'s `:dispatch-ref`) matches the
  unit's own recorded `:dispatch-number` -- never trust the proposal's
  own echo of a prior record, the SAME anti-fabrication discipline
  `unit-type-unregistered-violations` applies to a unit-type
  reference, applied here to a dispatch-ref reference into THIS
  actor's own dispatch history. A unit that was never dispatched (or a
  `:dispatch-ref` that doesn't match its own recorded dispatch-number)
  HARD-holds; there is no override."
  [{:keys [op subject]} proposal st]
  (when (= op :issue-maintenance-notice)
    (let [a (store/unit st subject)
          claimed (:dispatch-ref (:value proposal))]
      (when-not (and (:unit-dispatched? a) (some? claimed) (= claimed (:dispatch-number a)))
        [{:rule :dispatch-ref-unverified
          :detail (str subject " の :dispatch-ref (" claimed
                      ") が実際の完成機実行記録(dispatch-number=" (:dispatch-number a)
                      ", unit-dispatched?=" (boolean (:unit-dispatched? a))
                      ")と一致しない -- 未実行または架空のdispatch-ref参照")}]))))

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

(defn- equipment-asset-missing-fields-violations
  "For `:register-equipment-asset`, the proposal's `:value` (the
  superproject `:equipment-asset` shared shape) must carry all four
  required fields (`registry/equipment-asset-fields-present?`) --
  this actor never registers a partial/fabricated equipment-asset
  record, the SAME anti-fabrication discipline `spec-basis-violations`
  applies to a jurisdiction citation."
  [{:keys [op]} proposal]
  (when (= op :register-equipment-asset)
    (when-not (registry/equipment-asset-fields-present? (:value proposal))
      [{:rule :equipment-asset-missing-fields
        :detail "設備資産登録提案に必須フィールド(:equipment-asset/id・:equipment-asset/unit-type-id・:equipment-asset/source-actor・:equipment-asset/dispatch-ref)が不足 -- 未確認/架空の設備資産は登録できない"}])))

(defn- equipment-asset-already-registered-violations
  "For `:register-equipment-asset`, refuses to register the SAME
  equipment-asset id twice, off the store's own registered-asset
  directory (never trusting the proposal's own claim) -- the same
  double-commit-guard discipline `already-dispatched-violations`/
  `already-certified-violations` establish, adapted to a NEW entity
  (an equipment asset, not a unit)."
  [{:keys [op subject]} st]
  (when (= op :register-equipment-asset)
    (when (store/equipment-asset-already-registered? st subject)
      [{:rule :equipment-asset-already-registered
        :detail (str subject " は既に設備資産として登録済み")}])))

(defn check
  "Censors a Pressure Equipment Advisor proposal against the governor
  rules. Returns {:ok? bool :violations [..] :confidence c :escalate?
  bool :high-stakes? bool :hard? bool}.

  Includes `unit-type-unregistered-violations` -- added alongside this
  fleet's UNSPSC/GTIN unit-model catalog (`pressureequip.facts/unit-
  types`) as a SEVENTH hard check, purely additive: it only ever fires
  for a unit that explicitly declares a `:unit-type-id`, so it changes
  no existing unit/proposal's disposition (every pre-existing unit in
  this store has no `:unit-type-id` at all). Also includes
  `dispatch-ref-unverified-violations` -- an EIGHTH hard check added
  alongside `:issue-maintenance-notice` (see ns docstring Addendum 2),
  purely additive: it only ever fires for that op. Also includes
  `equipment-asset-missing-fields-violations`/`equipment-asset-
  already-registered-violations` -- a NINTH and TENTH hard check added
  alongside `:register-equipment-asset` (see ns docstring Addendum 3),
  purely additive: both only ever fire for that op."
  [request _context proposal st]
  (let [hard (into []
                   (concat (spec-basis-violations request proposal)
                           (evidence-incomplete-violations request st)
                           (unit-test-pressure-out-of-range-violations request st)
                           (pressure-test-defect-unresolved-violations request proposal st)
                           (unit-type-unregistered-violations request st)
                           (dispatch-ref-unverified-violations request proposal st)
                           (already-dispatched-violations request st)
                           (already-certified-violations request st)
                           (equipment-asset-missing-fields-violations request proposal)
                           (equipment-asset-already-registered-violations request st)))
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
