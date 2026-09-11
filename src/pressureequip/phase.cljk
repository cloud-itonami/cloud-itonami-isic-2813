(ns pressureequip.phase
  "Phase 0->3 staged rollout -- the pressure-equipment-manufacturer
  analog of `cloud-itonami-isic-6512`'s `casualty.phase`.

    Phase 0  read-only        -- no writes, still governor-gated.
    Phase 1  assisted-intake  -- unit intake allowed, every write
                                 needs human approval.
    Phase 2  assisted-verify  -- adds design-rules requirements
                                 verification + pressure-test
                                 screening writes, still approval.
    Phase 3  supervised auto  -- governor-clean, high-confidence
                                 `:unit/intake` (no capital risk
                                 yet) may auto-commit. `:actuation/
                                 dispatch-unit`/`:actuation/issue-
                                 pressure-test-certificate` NEVER
                                 auto-commit, at any phase.

  `:actuation/dispatch-unit`/`:actuation/issue-pressure-test-
  certificate` are deliberately ABSENT from every phase's `:auto` set,
  including phase 3 -- a permanent structural fact, not a rollout
  milestone still to come. Dispatching a real robot final-assembly/
  shipment action on a pump/compressor/valve unit and issuing a real
  ASME BPVC/PED hydrostatic or pneumatic pressure-test acceptance
  certificate are the two real-world legal acts this actor performs;
  both are always a human pressure-equipment manufacturing engineer's
  call. `pressureequip.governor`'s `:actuation/dispatch-unit`/
  `:actuation/issue-pressure-test-certificate` high-stakes gate
  enforces the same invariant independently -- two layers, not one,
  agree on this. `:pressure-test/screen` is likewise never auto-
  eligible, at any phase -- the same posture every sibling's screening
  op has. Phase 3's `:auto` set here has only ONE member
  (`:unit/intake`) -- this domain has no separate no-capital-risk
  'file' lifecycle distinct from the unit record itself.

  `:issue-maintenance-notice` (superproject equipment-asset-linkage
  ADR, cloud-itonami-isic-2813<->cloud-itonami-jsic-4721) joins this
  same never-auto set: a maintenance/recall notice about equipment
  already dispatched into the field is real-world safety/compliance
  communication, given the same human-in-the-loop treatment as the two
  actuation ops -- `pressureequip.governor`'s `high-stakes` set
  independently enforces this too.

  `:register-equipment-asset` (superproject equipment-asset-linkage
  ADR, cloud-itonami-isic-2822<->cloud-itonami-isic-2813) also joins
  `write-ops` -- isic-2813's RECEIVE side of the same shared shape it
  is the ISSUER side of toward cloud-itonami-jsic-4721. It is NOT in
  `pressureequip.governor/high-stakes` (unlike the two actuation ops
  and `:issue-maintenance-notice`), but the phase gate below still
  never auto-commits it in practice: phase 3's `:auto` set has only
  ever had the one `:unit/intake` member, so this op always escalates
  once the governor clears it, exactly like `:design-rules/verify`/
  `:pressure-test/screen` before it.

  `:register-part-receipt` (superproject part-supplier-linkage ADR,
  cloud-itonami-isic-2813<->cloud-itonami-isic-2710, ADR-2800000500)
  joins `write-ops` the SAME way `:register-equipment-asset` does --
  bookkeeping registration of a BOM consumable/component part receipt
  (never fixed capital, see `pressureequip.governor` ns docstring
  Addendum 4), NOT in `high-stakes`, and never auto-eligible in
  practice since phase 3's `:auto` set is UNCHANGED by this
  addition.

  `:discover/tsukuru-factory-candidates` (superproject fictional-
  actor<->real-external-system bridge ADR) joins `read-ops`, NOT
  `write-ops` -- a READ-ONLY candidate-discovery query against the
  REAL `orgs/etzhayyim/com-etzhayyim-tsukuru` factory registry can
  never mutate this actor's own SSoT beyond an audit-ledger entry, so
  it never needs the phase-gate's write/auto machinery at all: like
  every sibling actor's own `read-ops` members (e.g. `crm`'s
  `:pipeline/dashboard-query`, `dossier`'s `:disclosure/query`), it
  simply passes the governor's own disposition straight through at
  every phase, 0 through 3 -- see `gate` below.")

(def read-ops  #{:discover/tsukuru-factory-candidates})
(def write-ops #{:unit/intake :design-rules/verify :pressure-test/screen
                 :actuation/dispatch-unit :actuation/issue-pressure-test-certificate
                 :issue-maintenance-notice :register-equipment-asset
                 :register-part-receipt})

;; NOTE the invariant: `:actuation/dispatch-unit`/`:actuation/
;; issue-pressure-test-certificate`/`:issue-maintenance-notice` are
;; members of `write-ops` (governor-gated like any write) but are
;; NEVER members of any phase's `:auto` set below. Do not add them
;; there.
(def phases
  "phase -> {:label .. :writes <ops allowed to write> :auto <ops allowed to
  auto-commit when governor-clean>}."
  {0 {:label "read-only"        :writes #{}                                                          :auto #{}}
   1 {:label "assisted-intake"  :writes #{:unit/intake}                                          :auto #{}}
   2 {:label "assisted-verify"  :writes #{:unit/intake :design-rules/verify :pressure-test/screen}          :auto #{}}
   3 {:label "supervised-auto"  :writes write-ops
      :auto #{:unit/intake}}})

(def default-phase 3)

(defn gate
  "Adjust a governor disposition for the rollout phase. Returns
  {:disposition kw :reason kw|nil}.

  - a governor HOLD always stays HOLD (compliance wins).
  - a write op not yet enabled in this phase -> HOLD (:phase-disabled).
  - a write op enabled but not auto-eligible -> ESCALATE (:phase-approval),
    even if the governor was clean.
  - `:actuation/dispatch-unit`/`:actuation/issue-pressure-test-
    certificate` are never auto-eligible at any phase, so they always
    escalate once the governor clears them (or hold if the governor
    doesn't)."
  [phase {:keys [op]} governor-disposition]
  (let [{:keys [writes auto]} (get phases phase (get phases default-phase))]
    (cond
      (= :hold governor-disposition)       {:disposition :hold :reason nil}
      (contains? read-ops op)              {:disposition governor-disposition :reason nil}
      (not (contains? writes op))          {:disposition :hold :reason :phase-disabled}
      (and (= :commit governor-disposition)
           (not (contains? auto op)))      {:disposition :escalate :reason :phase-approval}
      :else                                {:disposition governor-disposition :reason nil})))

(defn verdict->disposition
  "Map a Pressure Equipment Governor verdict to a base
  disposition before the phase gate."
  [verdict]
  (cond (:hard? verdict) :hold
        (:escalate? verdict) :escalate
        :else :commit))
