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

(defn equipment-asset-fields-present?
  "True when all four REQUIRED `:equipment-asset/*` fields
  (`:equipment-asset/id`/`:equipment-asset/unit-type-id`/
  `:equipment-asset/source-actor`/`:equipment-asset/dispatch-ref`) are
  present on `equipment-asset` -- the RECEIVE-side field-presence rule
  for the superproject `:equipment-asset` shared shape this actor's
  own `:register-equipment-asset` op uses (isic-2813 plays the equipment-
  asset RECEIVER role here, toward an upstream manufacturer actor e.g.
  cloud-itonami-isic-2822, the mirror image of `:issue-maintenance-
  notice`'s ISSUER role toward cloud-itonami-jsic-4721). Independently
  mirrors cloud-itonami-jsic-4721's own `equipment-asset-required-
  fields-present?` -- no shared code, same four required keys, the same
  honest discipline `pressureequip.facts` uses: never register a
  partial/fabricated equipment-asset record."
  [equipment-asset]
  (every? some? ((juxt :equipment-asset/id :equipment-asset/unit-type-id
                       :equipment-asset/source-actor :equipment-asset/dispatch-ref)
                 equipment-asset)))

;; ----------------------------- part-receipt / :handoff (additive) -----------------------------
;;
;; Part-receipt traceability -- DISTINCT from `equipment-asset-fields-
;; present?` above: an equipment asset is FIXED CAPITAL this factory
;; OPERATES (a machine tool/welding cell, received once and installed
;; at a `:station/cell`); a part receipt is a CONSUMABLE/COMPONENT
;; this factory CONSUMES into its own BOM (resources/pressureequip/
;; compressor-unit-bom.edn), received repeatedly, batch by batch. The
;; superproject `:handoff` shared shape (ADR-2607177600, isic-1075<->
;; jsic-4721) is REUSED AS-IS here -- see superproject ADR-2800000500
;; -- never a new shape. Unlike `equipment-asset-fields-present?`,
;; `:handoff` itself is entirely OPTIONAL on a part receipt: this
;; actor accepts parts from any supplier, tracked or not.

(defn part-receipt-fields-present?
  "True when the two REQUIRED `:part-receipt/*` fields
  (`:part-receipt/id`/`:part-receipt/part-id`) are present -- this
  actor never registers a receipt for an unnamed part. `:handoff` is
  NOT in this required set (see `handoff-fields-present?` below,
  checked separately and only when the key itself is present)."
  [part-receipt]
  (every? some? ((juxt :part-receipt/id :part-receipt/part-id) part-receipt)))

(defn handoff-fields-present?
  "True when `handoff` carries the three identity/correlation
  `:handoff/*` fields (`:handoff/id`/`:handoff/source-actor`/
  `:handoff/batch-id`) the superproject `:handoff` shared shape
  requires for traceability (ADR-2607177600) -- called ONLY when a
  `:handoff` map is actually present on a part receipt (see
  `pressureequip.governor/part-receipt-handoff-incomplete-
  violations`); a part receipt with NO `:handoff` at all never reaches
  this predicate. Domain-specific optional fields on the shared shape
  (`:handoff/product-type-id`/`:handoff/quantity-kg`/`:handoff/cold-
  chain-temp-min-c`/`:handoff/cold-chain-temp-max-c`/`:handoff/
  dispatched-at-iso`) are NOT required here -- this actor's parts
  (electric motors, refrigerant charge) are not cold-chain goods, the
  same 'optional field absent -> not checked' discipline
  cloud-itonami-jsic-4721's own handoff-compatibility check uses."
  [handoff]
  (every? some? ((juxt :handoff/id :handoff/source-actor :handoff/batch-id) handoff)))

;; ----------------------------- testlab-engagement-ref (additive) -----------------------------
;;
;; Independent third-party verification of a pressure-test
;; certificate -- superproject ADR (independent-verification-of-
;; self-issued-certificates), the ADR-2607176500 SecurityIncidentGovernor
;; `disclosure-integrity` theme (self-attestation alone is not enough)
;; applied to this actor's own `:actuation/issue-pressure-test-
;; certificate`. DISTINCT from `handoff-fields-present?` above: a
;; `:handoff` on a part receipt is entirely OPTIONAL traceability into
;; a SUPPLY chain; `:certification/testlab-engagement-ref` is a
;; MANDATORY reference into an independent accredited testing
;; laboratory actor (cloud-itonami-isic-7120, `testlab.store`'s own
;; engagement `:id` + `testlab.registry/register-certification`'s own
;; issued certification number) that this actor cannot skip.

(defn testlab-engagement-ref-fields-present?
  "True when `ref` (the `:certification/testlab-engagement-ref` value
  on an `:actuation/issue-pressure-test-certificate` proposal) carries
  all three REQUIRED `:testlab-engagement-ref/*` identity fields
  (`:testlab-engagement-ref/id`/`:testlab-engagement-ref/source-
  actor`/`:testlab-engagement-ref/certification-number`) -- called on
  EVERY such proposal (unlike `handoff-fields-present?`, this
  reference is MANDATORY, not optional: see `pressureequip.governor/
  testlab-engagement-ref-missing-violations`, which also treats a
  wholly ABSENT `ref` as the same violation, not a separate one).
  `nil` (the field entirely absent) is NOT present, the same honest
  'never trust a bare presence flag' discipline `equipment-asset-
  fields-present?` establishes."
  [ref]
  (and (map? ref)
       (every? some? ((juxt :testlab-engagement-ref/id :testlab-engagement-ref/source-actor
                            :testlab-engagement-ref/certification-number) ref))))

;; ----------------------------- tsukuru discovery bridge (additive, read-only) -----------------------------
;;
;; Pure input-validation predicates for `:discover/tsukuru-factory-
;; candidates` (superproject fictional-actor<->real-external-system
;; bridge ADR) -- the FIRST bridge in this fleet from a fictional
;; cloud-itonami Open-Business-Blueprint actor to a REAL, independently
;; operated external system (`orgs/etzhayyim/com-etzhayyim-tsukuru`,
;; `tsukuru.etzhayyim.com`). See `pressureequip.tsukuru-bridge` ns
;; docstring for the full safety-boundary rationale; these two
;; predicates are the pure, no-I/O core `pressureequip.governor`'s new
;; hard checks call -- never the network fetch itself.

(defn valid-isic-code?
  "True when `isic-code` LOOKS like an ISIC rev.4 section/class code --
  either a bare numeric division/class code (e.g. \"2822\", 2-4 digits,
  matching this actor's OWN `isic-2813` repo-naming convention) or a
  section-letter + 2-digit division (e.g. \"C26\", per tsukuru's own
  `lex/factory.edn` `:isic` field description: \"ISIC rev.4
  section/class (e.g. C26 semiconductor)\"). Pure format validation
  ONLY -- this does NOT verify the code is a REAL, registered ISIC
  entry (this actor keeps no ISIC catalog); it only rejects
  structurally-malformed input before a query is ever sent to the real
  tsukuru registry, the same honest, non-fabricating discipline
  `pressureequip.facts` uses for jurisdiction spec-basis."
  [isic-code]
  (and (string? isic-code)
       (boolean (re-matches #"[A-Za-z]?\d{2,4}" isic-code))))

(def order-contamination-keys
  "Closed set of production-order/settlement/buyer field names, as BOTH
  bare keywords AND strings -- this actor's own request/proposal keys
  are keywords, and `pressureequip.tsukuru-bridge`'s fetched `factory`
  records are ALSO keyword-keyed (`kotoba.lang.atproto-client`'s own
  `json-read` deeply keywordizes every parsed field), but this set
  covers the string form too as defense-in-depth against any future
  path that hands this a pre-parse/raw-JSON shape instead. Must NEVER
  appear anywhere in a read-only tsukuru discovery request or
  proposal. Sourced directly from
  `com.etzhayyim.tsukuru.production-order`'s own required/settlement
  fields (`buyerDid`/`orderId`) plus the settlement/payment fields any
  real order-EXECUTION flow would need (`settlementId`/`usdcTx`/
  `paymentRef`) -- see `pressureequip.governor/tsukuru-query-contains-
  order-fields-violations` for why ANY of these appearing here is a
  HARD, un-overridable hold: tsukuru's own `production-order` lexicon
  requires `buyerDid` to be a REAL etzhayyim member (active Adherent
  SBT, purchasing principal per its own Gate G14), and this actor is a
  FICTIONAL governor-gated cloud-itonami actor that structurally cannot
  be one."
  #{:buyerDid :orderId :settlementId :usdcTx :paymentRef
    :buyer-did :order-id :settlement-id
    "buyerDid" "orderId" "settlementId" "usdcTx" "paymentRef"
    "buyer-did" "order-id" "settlement-id"})

(defn contains-order-fields?
  "Deep, pure structural scan: does `x` (any nested map/vector/seq)
  contain a key from `order-contamination-keys` at ANY level? No
  network I/O, no trust in any `:type`/`:$type`/`\"$type\"` self-
  declaration inside `x` -- every key at every depth is checked
  against the SAME closed set, the discipline this fleet's other
  anti-fabrication checks (e.g. `unit-type-unregistered-violations`
  never trusting a `:unit-type-id` reference's own say-so) already
  establish, applied here to an entire request/proposal shape instead
  of one reference field."
  [x]
  (cond
    (map? x) (boolean (or (some order-contamination-keys (keys x))
                          (some contains-order-fields? (vals x))))
    (sequential? x) (boolean (some contains-order-fields? x))
    :else false))
