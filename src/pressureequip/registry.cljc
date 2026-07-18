(ns pressureequip.registry
  "Pure-function unit-dispatch + pressure-test-certificate record
  construction -- an append-only pressure-equipment-manufacturer
  book-of-record draft.

  Like every sibling actor's registry, there is no single
  international check-digit standard for a unit-dispatch or
  pressure-test-certificate reference number -- every manufacturer/
  jurisdiction assigns its own reference format. This namespace does
  NOT invent one; it builds a jurisdiction-scoped sequence number and
  validates the record's required fields, the same honest, non-
  fabricating discipline `pressureequip.facts` uses.

  `unit-test-pressure-out-of-range?` continues this fleet's two-sided
  range check family (`testlab.registry/within-tolerance?` /
  `conservation.registry/body-condition-out-of-range?` /
  `water.registry/contaminant-level-out-of-range?` /
  `steelworks.registry/heat-chemistry-out-of-range?` /
  `turbine.registry/unit-tolerance-out-of-range?` /
  `automotive.registry/vehicle-emissions-out-of-range?` /
  `machinetool.registry/unit-accuracy-out-of-range?` /
  `heavyequip.registry/unit-out-of-range?` established the priors),
  applying the SAME lo/hi bounds-comparison shape to a pump/
  compressor/valve unit's own measured hydrostatic/pneumatic
  acceptance test pressure against the unit's own recorded spec
  bounds -- the direct analog of ASME BPVC UG-99's required minimum
  hydrostatic test pressure (>= 1.3x MAWP, temperature-corrected) and
  the code's overstress ceiling (a test pressure high enough to risk
  exceeding 90% of yield is out of range on the other side).

  This namespace is pure data + pure functions -- no I/O, no network
  call to any real fab/final-assembly control system. It builds the
  RECORD a manufacturer would keep, not the act of dispatching the
  robot unit action or issuing the pressure-test certificate itself
  (that is `pressureequip.operation`'s `:actuation/dispatch-unit`/
  `:actuation/issue-pressure-test-certificate`, always human-gated --
  see README `Actuation`)."
  (:require [clojure.string :as str]))

(defn- unsigned-certificate
  "Every certificate this actor produces is UNSIGNED -- signature is the
  manufacturer's own act, not this actor's. See README `Actuation`."
  [kind subject record-id]
  {"@context" ["https://www.w3.org/ns/credentials/v2"]
   "type" ["VerifiableCredential" kind]
   "credentialSubject" {"id" subject "record" record-id}
   "proof" nil
   "issued_by_registry" false
   "status" "draft-unsigned"})

(defn- zero-pad [n w]
  (let [s (str n)]
    (str (apply str (repeat (max 0 (- w (count s))) "0")) s)))

(defn unit-test-pressure-out-of-range?
  "Does `unit`'s own `:test-pressure-actual` (hydrostatic/pneumatic
  acceptance test pressure) fall outside its own `[:test-pressure-min
  :test-pressure-max]` recorded spec-bounds? A pure ground-truth check
  against the unit's own permanent fields -- no upstream comparison
  needed. One of this fleet's two-sided range check family (see ns
  docstring)."
  [{:keys [test-pressure-actual test-pressure-min test-pressure-max]}]
  (and (number? test-pressure-actual) (number? test-pressure-min) (number? test-pressure-max)
       (or (< test-pressure-actual test-pressure-min)
           (> test-pressure-actual test-pressure-max))))

(defn register-unit-dispatch
  "Validate + construct the UNIT-DISPATCH registration DRAFT -- the
  manufacturer's own act of dispatching a real robot final-assembly/
  shipment action to complete a pump/compressor/valve unit. Pure
  function -- does not touch any real fab/final-assembly control
  system; it builds the RECORD a manufacturer would keep.
  `pressureequip.governor` independently re-verifies the unit's own
  test-pressure sufficiency against its own spec bounds, and a
  double-dispatch for the same unit, before this is ever allowed to
  commit."
  [unit-id jurisdiction sequence]
  (when-not (and unit-id (not= unit-id ""))
    (throw (ex-info "unit-dispatch: unit_id required" {})))
  (when-not (and jurisdiction (not= jurisdiction ""))
    (throw (ex-info "unit-dispatch: jurisdiction required" {})))
  (when (< sequence 0)
    (throw (ex-info "unit-dispatch: sequence must be >= 0" {})))
  (let [dispatch-number (str (str/upper-case jurisdiction) "-PEQ-" (zero-pad sequence 6))
        record {"record_id" dispatch-number
                "kind" "unit-dispatch-draft"
                "unit_id" unit-id
                "jurisdiction" jurisdiction
                "immutable" true}]
    {"record" record "dispatch_number" dispatch-number
     "certificate" (unsigned-certificate "UnitDispatch" dispatch-number dispatch-number)}))

(defn register-pressure-test-certificate
  "Validate + construct the PRESSURE-TEST-CERTIFICATE registration
  DRAFT -- the manufacturer's own act of issuing a real ASME BPVC/PED
  hydrostatic or pneumatic acceptance-test certificate documenting a
  unit's pressure-test result. Pure function -- does not touch any
  real fab/final-assembly control system; it builds the RECORD a
  manufacturer would keep. `pressureequip.governor` independently
  re-verifies the unit's own pressure-test defect resolution status,
  and a double-issuance for the same unit, before this is ever
  allowed to commit."
  [unit-id jurisdiction sequence]
  (when-not (and unit-id (not= unit-id ""))
    (throw (ex-info "pressure-test-certificate: unit_id required" {})))
  (when-not (and jurisdiction (not= jurisdiction ""))
    (throw (ex-info "pressure-test-certificate: jurisdiction required" {})))
  (when (< sequence 0)
    (throw (ex-info "pressure-test-certificate: sequence must be >= 0" {})))
  (let [evidence-number (str (str/upper-case jurisdiction) "-PTC-" (zero-pad sequence 6))
        record {"record_id" evidence-number
                "kind" "pressure-test-certificate-draft"
                "unit_id" unit-id
                "jurisdiction" jurisdiction
                "immutable" true}]
    {"record" record "evidence_number" evidence-number
     "certificate" (unsigned-certificate "PressureTestCertificate" evidence-number evidence-number)}))

(defn register-maintenance-notice
  "Validate + construct the MAINTENANCE/RECALL-NOTICE registration
  DRAFT -- the manufacturer's own act of issuing a maintenance or
  recall notice for a unit it PREVIOUSLY dispatched, referencing that
  prior `:actuation/dispatch-unit`'s own `dispatch-ref` (the
  `:equipment-asset` shared shape a downstream operator actor, e.g.
  cloud-itonami-jsic-4721, independently cross-checks against its own
  registered equipment asset -- superproject ADR, no shared code).
  Pure function -- does not touch any real fab/final-assembly control
  system; it builds the RECORD a manufacturer would keep. Unlike
  `register-unit-dispatch`/`register-pressure-test-certificate`, a
  unit may receive MANY maintenance notices over its life (recurring
  maintenance, more than one recall), so there is no double-issuance
  guard here -- `pressureequip.governor` instead INDEPENDENTLY
  re-verifies that the referenced `dispatch-ref` actually corresponds
  to a unit this actor itself already dispatched, before this is ever
  allowed to commit."
  [unit-id dispatch-ref jurisdiction sequence]
  (when-not (and unit-id (not= unit-id ""))
    (throw (ex-info "maintenance-notice: unit_id required" {})))
  (when-not (and dispatch-ref (not= dispatch-ref ""))
    (throw (ex-info "maintenance-notice: dispatch_ref required" {})))
  (when-not (and jurisdiction (not= jurisdiction ""))
    (throw (ex-info "maintenance-notice: jurisdiction required" {})))
  (when (< sequence 0)
    (throw (ex-info "maintenance-notice: sequence must be >= 0" {})))
  (let [notice-number (str (str/upper-case jurisdiction) "-PMN-" (zero-pad sequence 6))
        record {"record_id" notice-number
                "kind" "maintenance-notice-draft"
                "unit_id" unit-id
                "dispatch_ref" dispatch-ref
                "jurisdiction" jurisdiction
                "immutable" true}]
    {"record" record "notice_number" notice-number
     "certificate" (unsigned-certificate "MaintenanceNotice" notice-number notice-number)}))

(defn append [history result]
  (conj (vec history) (get result "record")))
