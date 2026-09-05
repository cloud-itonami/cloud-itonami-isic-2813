(ns pressureequip.render-html
  "Build-time HTML renderer for `docs/samples/operator-console.html`.

  Every id, status, hold reason, registry draft number and ledger row on
  the generated page comes from actually running THIS repo's real actor
  stack -- `pressureequip.operation` (langgraph StateGraph) ->
  `pressureequip.governor` -> `pressureequip.store`. Nothing on the page
  is hand-typed telemetry, and no field is synthesised at render time:
  every rendered cell is either a `pressureequip.store` entity key, a
  real audit-ledger fact, a real `pressureequip.registry` draft record,
  or the return value of a real domain predicate
  (`registry/unit-test-pressure-out-of-range?`). The ONE hand-written
  block is `action-gate-rows`, which describes this actor's own fixed
  op/gate contract -- documentation-of-code read off
  `pressureequip.phase` and `pressureequip.governor`, explicitly NOT
  runtime telemetry (see the comment there).

  ── Input provenance ──

  Every `:subject` driven below is a unit id that ALREADY EXISTS in
  `pressureequip.store/demo-data` (`unit-1`..`unit-4`) -- passing a
  fabricated id through real code would still make the page fabricated.
  The scenario is authored here rather than reusing `pressureequip.sim`
  wholesale, but it is built on the same real seed (this repo's own sim
  driver, unlike some siblings', does use real seeded ids -- verified by
  running `clojure -M:dev:run` before writing this file), and it differs
  from the sim in three deliberate ways:

    1. `unit-2`'s `:no-spec-basis` hold is driven WITHOUT the sim's
       `:no-spec? true` flag. `unit-2`'s OWN seeded `:jurisdiction` is
       `\"ATL\"`, which is absent from `pressureequip.facts/catalog`
       (JPN/USA/DEU), so the hold falls out of the seed data itself
       rather than out of a test flag -- strictly better provenance.
    2. `:testlab-engagement-ref-missing` is isolated on `unit-3` (which
       has a committed JPN requirements verification, so the evidence
       check passes and no other rule co-fires) instead of being
       observed only co-fired with `:already-certified` on `unit-1`.
    3. A real human REJECTION is driven, so the third and last ledger
       fact type this actor can actually append (`:approval-rejected`)
       is exercised, not just asserted.

  ── Which ledger fact types actually exist ──

  `pressureequip.store/append-ledger!` is called from exactly two nodes
  of `pressureequip.operation/build`: `:commit` (writes `:committed`)
  and `:hold` (writes whichever of `:governor-hold` / `:approval-
  rejected` is on the audit channel). `:approval-granted` and
  `:approval-requested` are produced too, but ONLY onto the in-memory
  `:audit` channel -- they never reach the ledger. `status-cell` below
  therefore branches on `:committed` / `:governor-hold` /
  `:approval-rejected` and nothing else; a branch on `:approval-granted`
  would be permanently dead code.

  ── Scenario, subject by subject (every id is seeded) ──

    unit-1  Sakura API 610 Centrifugal Pump CP-04 (JPN, test pressure
            13.5 inside spec [13.0,15.0], no unresolved defect) -- the
            FULL clean lifecycle: `:unit/intake` auto-commits (the only
            member of phase 3's `:auto` set), then `:design-rules/
            verify`, `:pressure-test/screen`, `:actuation/dispatch-unit`
            and `:actuation/issue-pressure-test-certificate` each
            escalate to the human operator and are approved and
            committed, producing the real registry drafts JPN-PEQ-000000
            and JPN-PTC-000000. Then a SECOND `:actuation/dispatch-unit`
            HARD-holds on `:already-dispatched` (double-actuation
            guard, off the dedicated `:unit-dispatched?` boolean).

    unit-2  Atlantis Screw Compressor SC-12, seeded `:jurisdiction`
            \"ATL\" -- `:design-rules/verify` HARD-holds on
            `:no-spec-basis`: the advisor found no official spec-basis
            and cited nothing, and the governor refuses to let this
            actor invent a jurisdiction's design-rules requirements.

    unit-3  鈴木精密仕切弁 SV-07 (JPN, test pressure 18.0 OUTSIDE spec
            [13.0,15.0]) -- `:design-rules/verify` is approved and
            commits, then `:actuation/dispatch-unit` HARD-holds on
            `:unit-test-pressure-out-of-range` (the governor
            INDEPENDENTLY recomputes the window from the unit's own
            ground-truth fields), and `:actuation/issue-pressure-test-
            certificate` without a `:certification/testlab-engagement-
            ref` HARD-holds on `:testlab-engagement-ref-missing` alone
            (self-issued certification is refused outright).

    unit-4  田中往復動圧縮機 RC-03 (JPN, `:pressure-test-defect-
            unresolved? true`) -- `:pressure-test/screen` HARD-holds on
            `:pressure-test-defect-unresolved`, screened DIRECTLY as
            this actor's own governor docstring instructs (never via an
            actuation op against an unscreened unit). Then
            `:design-rules/verify` escalates cleanly and the human
            operator REJECTS it, producing the `:approval-rejected`
            ledger fact -- a human decision, NOT a governor hold.

  Five distinct HARD rules fire (`:no-spec-basis`,
  `:unit-test-pressure-out-of-range`, `:testlab-engagement-ref-missing`,
  `:pressure-test-defect-unresolved`, `:already-dispatched`), none of
  which any approver can override.

  ── Determinism ──

  `store/seed-db` is a fresh in-memory store, the advisor is the
  deterministic mock, `store/all-units` sorts by id, the ledger is
  append-ordered, and `pressureequip.registry`'s draft numbers are
  derived from a per-jurisdiction sequence counter. No timestamps and no
  random ids reach the page, so two consecutive runs are byte-identical.

  Styling is `jp-go-dds.skin/dds+skin` (デジタル庁デザインシステム, the
  workspace BASE design system) -- no hand-written stylesheet and no raw
  hex colours; the skin targets exactly the small semantic class
  vocabulary emitted below.

  Usage: `clojure -M:dev:render-html [out-file]`
  (default `docs/samples/operator-console.html`)."
  (:require [jp-go-dds.skin]
            [clojure.string :as str]
            [pressureequip.registry :as registry]
            [pressureequip.store :as store]
            [pressureequip.operation :as op]
            [langgraph.graph :as g]))

(def ^:private operator
  "The human pressure-equipment manufacturing engineer this run is
  driven as. Phase 3 = `supervised-auto` (`pressureequip.phase`)."
  {:actor-id "op-1" :actor-role :pressure-equipment-engineer :phase 3})

(def ^:private testlab-engagement-ref
  "The independent third-party accredited testing laboratory reference
  `:actuation/issue-pressure-test-certificate` has REQUIRED since the
  superproject independent-verification-of-self-issued-certificates ADR
  (`pressureequip.governor` Addendum 5). Reproduced verbatim from this
  repo's OWN demo driver `pressureequip.sim`; it is a cross-actor
  reference toward `cloud-itonami-isic-7120`, and -- as that governor
  rule's own docstring states -- this actor can only verify the
  reference's wire-shape completeness, never reach across and confirm
  the engagement exists on isic-7120's live store. It is therefore
  rendered below as a shape-checked citation, never as a verified one."
  {:testlab-engagement-ref/id "engagement-1"
   :testlab-engagement-ref/source-actor "cloud-itonami-isic-7120"
   :testlab-engagement-ref/certification-number "JPN-CERT-000000"})

(defn- exec! [actor tid request]
  (g/run* actor {:request request :context operator} {:thread-id tid}))

(defn- approve! [actor tid]
  (g/run* actor {:approval {:status :approved :by "op-1"}}
          {:thread-id tid :resume? true}))

(defn- reject! [actor tid]
  (g/run* actor {:approval {:status :rejected :by "op-1"}}
          {:thread-id tid :resume? true}))

(defn run-demo!
  "Drives a fresh seeded store through the scenario documented in this
  ns's docstring and returns the resulting store. Every `:subject` is a
  `pressureequip.store/demo-data` unit id; every disposition below is
  decided by the real governor and phase gate, never asserted here."
  []
  (let [db (store/seed-db)
        actor (op/build db)]
    ;; ---- unit-1: full clean lifecycle -------------------------------
    ;; The patch echoes unit-1's OWN seeded fields; the advisor
    ;; normalizes, it does not invent (no new field enters the store).
    (exec! actor "u1-intake"
           {:op :unit/intake :subject "unit-1"
            :patch {:id "unit-1" :unit-name "Sakura API 610 Centrifugal Pump CP-04"}})

    (exec! actor "u1-verify" {:op :design-rules/verify :subject "unit-1"})
    (approve! actor "u1-verify")

    (exec! actor "u1-screen" {:op :pressure-test/screen :subject "unit-1"})
    (approve! actor "u1-screen")

    (exec! actor "u1-dispatch" {:op :actuation/dispatch-unit :subject "unit-1"})
    (approve! actor "u1-dispatch")

    (exec! actor "u1-certify"
           {:op :actuation/issue-pressure-test-certificate :subject "unit-1"
            :certification/testlab-engagement-ref testlab-engagement-ref})
    (approve! actor "u1-certify")

    ;; ---- unit-2: HARD :no-spec-basis, straight from the seed --------
    ;; No `:no-spec?` flag: unit-2's own seeded jurisdiction "ATL" is
    ;; simply absent from pressureequip.facts/catalog.
    (exec! actor "u2-verify" {:op :design-rules/verify :subject "unit-2"})

    ;; ---- unit-3: clean verification, then two distinct HARD holds ---
    (exec! actor "u3-verify" {:op :design-rules/verify :subject "unit-3"})
    (approve! actor "u3-verify")

    (exec! actor "u3-dispatch" {:op :actuation/dispatch-unit :subject "unit-3"})

    ;; unit-3 HAS a committed JPN verification, so the evidence check
    ;; passes and :testlab-engagement-ref-missing fires in isolation.
    (exec! actor "u3-certify"
           {:op :actuation/issue-pressure-test-certificate :subject "unit-3"})

    ;; ---- unit-4: HARD defect hold, then a human rejection -----------
    (exec! actor "u4-screen" {:op :pressure-test/screen :subject "unit-4"})

    (exec! actor "u4-verify" {:op :design-rules/verify :subject "unit-4"})
    (reject! actor "u4-verify")

    ;; ---- unit-1 again: double-actuation guard -----------------------
    (exec! actor "u1-dispatch-again" {:op :actuation/dispatch-unit :subject "unit-1"})
    db))

;; ----------------------------- rendering -----------------------------

(defn- esc [v]
  (-> (str v)
      (str/replace "&" "&amp;")
      (str/replace "<" "&lt;")
      (str/replace ">" "&gt;")))

(defn- kw-name
  "Render a keyword WITH its namespace (`:design-rules/verify` ->
  \"design-rules/verify\"). `clojure.core/name` would drop the namespace
  and collapse `:design-rules/verify` and `:pressure-test/screen` into
  bare \"verify\"/\"screen\", which is exactly the op distinction this
  page exists to show. Non-keywords (basis strings, numbers) pass
  through."
  [v]
  (if (keyword? v) (subs (str v) 1) (str v)))

(defn- join-basis [basis]
  (if (seq basis) (str/join ", " (map kw-name basis)) ""))

(defn- last-fact-for [ledger unit-id]
  (last (filter #(= (:subject %) unit-id) ledger)))

(defn- status-cell
  "The unit's LAST audit-ledger fact. Branches only on the three fact
  types `pressureequip.store/append-ledger!` can actually receive (see
  ns docstring) -- `:approval-granted` never reaches the ledger, so
  there is deliberately no branch for it."
  [ledger unit-id]
  (let [{:keys [t basis] :as f} (last-fact-for ledger unit-id)]
    (cond
      (nil? f) "<span class=\"muted\">no activity</span>"
      (= :committed t) "<span class=\"ok\">committed</span>"
      (= :governor-hold t)
      (str "<span class=\"critical\">HARD hold &middot; " (esc (join-basis basis)) "</span>")
      (= :approval-rejected t)
      (str "<span class=\"warn\">rejected by approver &middot; " (esc (join-basis basis)) "</span>")
      :else (str "<span class=\"muted\">" (esc (kw-name t)) "</span>"))))

(defn- pressure-cell
  "Measured acceptance test pressure against the unit's OWN recorded
  spec window. The in/out verdict is `pressureequip.registry/unit-test-
  pressure-out-of-range?` -- the same predicate the governor's own
  independent recompute uses, not a comparison re-implemented here."
  [{:keys [test-pressure-actual test-pressure-min test-pressure-max] :as u}]
  (str (esc test-pressure-actual)
       " <span class=\"muted\">/ spec [" (esc test-pressure-min) ", " (esc test-pressure-max) "]</span> "
       (if (registry/unit-test-pressure-out-of-range? u)
         "<span class=\"critical\">out of range</span>"
         "<span class=\"ok\">in range</span>")))

(defn- dispatch-cell [{:keys [unit-dispatched? dispatch-number]}]
  (if unit-dispatched?
    (str "<span class=\"ok\">dispatched</span> <code>" (esc dispatch-number) "</code>")
    "<span class=\"muted\">not dispatched</span>"))

(defn- certificate-cell [{:keys [pressure-test-certified? evidence-number]}]
  (if pressure-test-certified?
    (str "<span class=\"ok\">issued</span> <code>" (esc evidence-number) "</code>")
    "<span class=\"muted\">not issued</span>"))

(defn- unit-row [ledger {:keys [id unit-name jurisdiction] :as u}]
  (format "        <tr><td><code>%s</code></td><td>%s</td><td>%s</td><td>%s</td><td>%s</td><td>%s</td><td>%s</td></tr>"
          (esc id) (esc unit-name) (esc jurisdiction)
          (pressure-cell u) (dispatch-cell u) (certificate-cell u)
          (status-cell ledger id)))

(defn- hold-rows
  "One row per governor violation actually recorded this run. Both the
  `:rule` and the Japanese `:detail` are the governor's own output."
  [ledger]
  (for [{:keys [op subject violations]} (filter #(= :governor-hold (:t %)) ledger)
        {:keys [rule detail]} violations]
    (format "        <tr><td><code>%s</code></td><td><code>%s</code></td><td><span class=\"critical\">%s</span></td><td>%s</td></tr>"
            (esc (kw-name op)) (esc subject) (esc (kw-name rule)) (esc detail))))

(defn- ledger-row [{:keys [t op subject disposition basis summary]}]
  (format "        <tr><td>%s</td><td><code>%s</code></td><td><code>%s</code></td><td>%s</td><td>%s</td><td>%s</td></tr>"
          (esc (kw-name t)) (esc (kw-name (or op :n-a))) (esc subject)
          (esc (kw-name (or disposition "")))
          (esc (join-basis basis))
          (esc (or summary ""))))

(defn- draft-row [r]
  (format "        <tr><td><code>%s</code></td><td>%s</td><td><code>%s</code></td><td>%s</td><td>%s</td></tr>"
          (esc (get r "record_id")) (esc (get r "kind")) (esc (get r "unit_id"))
          (esc (get r "jurisdiction")) (esc (get r "immutable"))))

(def ^:private action-gate-rows
  ;; Static description of this actor's own CLOSED op contract, read off
  ;; `pressureequip.phase` (`read-ops`/`write-ops`/`phases`) and
  ;; `pressureequip.governor` (`high-stakes` + the sixteen HARD checks).
  ;; This is documentation-of-code -- fixed behaviour of the compiled
  ;; actor, NOT runtime telemetry -- so it is legitimately hand-described
  ;; rather than derived from the live run above. Everything else on this
  ;; page is derived.
  ["        <tr><td><code>:unit/intake</code></td><td><span class=\"ok\">phase-3 auto-commit when governor-clean &middot; the ONLY member of any phase's <code>:auto</code> set</span></td></tr>"
   "        <tr><td><code>:design-rules/verify</code></td><td><span class=\"warn\">human approval &middot; HARD: official spec-basis citation required, never invented</span></td></tr>"
   "        <tr><td><code>:pressure-test/screen</code></td><td><span class=\"warn\">human approval &middot; HARD: an unresolved pressure-test defect holds un-overridably</span></td></tr>"
   "        <tr><td><code>:actuation/dispatch-unit</code></td><td><span class=\"critical\">ALWAYS human approval, at every phase</span> &middot; HARD: evidence checklist complete, test pressure independently recomputed against spec window, unit-type reference resolved, no double dispatch</td></tr>"
   "        <tr><td><code>:actuation/issue-pressure-test-certificate</code></td><td><span class=\"critical\">ALWAYS human approval, at every phase</span> &middot; HARD: mandatory third-party testlab engagement reference, evidence checklist complete, no unresolved defect, no double issuance</td></tr>"
   "        <tr><td><code>:issue-maintenance-notice</code></td><td><span class=\"critical\">ALWAYS human approval</span> &middot; HARD: <code>:dispatch-ref</code> re-verified against the unit's own recorded dispatch-number</td></tr>"
   "        <tr><td><code>:register-equipment-asset</code></td><td><span class=\"warn\">human approval &middot; HARD: all four <code>:equipment-asset/*</code> fields required, no double registration</span></td></tr>"
   "        <tr><td><code>:register-part-receipt</code></td><td><span class=\"warn\">human approval &middot; HARD: <code>:part-receipt/*</code> fields required, no double registration, a present-but-incomplete <code>:handoff</code> refused</span></td></tr>"
   "        <tr><td><code>:discover/tsukuru-factory-candidates</code></td><td><span class=\"warn\">read-only &middot; HARD: ISIC/capability format validated, and any order/settlement/buyer field smuggled into the query is refused outright</span></td></tr>"])

(defn render
  "Renders the full operator-console document from a store `db` that has
  already been driven by `run-demo!` (or any other real scenario)."
  [db]
  (let [ledger (vec (store/ledger db))
        units (store/all-units db)
        holds (hold-rows ledger)
        drafts (concat (store/dispatch-history db) (store/evidence-history db))]
    (str
     "<!doctype html>\n"
     "<html lang=\"en\"><head><meta charset=\"utf-8\">"
     "<meta name=\"viewport\" content=\"width=device-width, initial-scale=1\">"
     "<title>cloud-itonami-isic-2813 &middot; pressure equipment operator console</title><style>"
     (jp-go-dds.skin/dds+skin)
     "</style></head><body>\n"
     "<header class=\"bar\">\n"
     "  <h1>Manufacture of other pumps, compressors, taps and valves (ISIC 2813) — Operator Console</h1>\n"
     "  <span class=\"badge\">read-only sample · governor-gated · unit dispatch and pressure-test certificate always human-approved</span>\n"
     "</header>\n"
     "<main>\n"

     "  <section class=\"card\">\n"
     "    <h2>Production units</h2>\n"
     "    <p class=\"muted\">Build-time snapshot generated from <code>pressureequip.store</code> by driving the real <code>pressureequip.operation</code> → <code>pressureequip.governor</code> stack (<code>clojure -M:dev:render-html</code>). Every unit id is seeded in <code>pressureequip.store/demo-data</code>; every cell is a store entity key or a real ledger fact.</p>\n"
     "    <table>\n"
     "      <thead><tr><th>Unit</th><th>Name</th><th>Jurisdiction</th><th>Acceptance test pressure</th><th>Unit dispatch</th><th>Pressure-test certificate</th><th>Last ledger fact</th></tr></thead>\n"
     "      <tbody>\n"
     (str/join "\n" (map (partial unit-row ledger) units)) "\n"
     "      </tbody>\n"
     "    </table>\n"
     "  </section>\n"

     "  <section class=\"card\">\n"
     "    <h2>HARD holds this run (" (count holds) ")</h2>\n"
     "    <p class=\"muted\">Produced by <code>pressureequip.governor</code> during the run above. A HARD hold cannot be overridden by any approver — it never reaches a human at all. Rule and detail are the governor's own output.</p>\n"
     "    <table>\n"
     "      <thead><tr><th>Op</th><th>Unit</th><th>Rule</th><th>Detail</th></tr></thead>\n"
     "      <tbody>\n"
     (str/join "\n" holds) "\n"
     "      </tbody>\n"
     "    </table>\n"
     "  </section>\n"

     "  <section class=\"card\">\n"
     "    <h2>Action gate (Pressure Equipment Governor)</h2>\n"
     "    <p class=\"muted\">This actor's fixed op contract, read off <code>pressureequip.phase</code> and <code>pressureequip.governor</code> — documentation of compiled behaviour, not telemetry from the run above.</p>\n"
     "    <table>\n"
     "      <thead><tr><th>Op</th><th>Gate</th></tr></thead>\n"
     "      <tbody>\n"
     (str/join "\n" action-gate-rows) "\n"
     "      </tbody>\n"
     "    </table>\n"
     "  </section>\n"

     "  <section class=\"card\">\n"
     "    <h2>Registry drafts committed this run (" (count drafts) ")</h2>\n"
     "    <p class=\"muted\">Immutable draft records built by <code>pressureequip.registry</code> and appended to the store's own append-only dispatch/evidence histories. Record numbers come from a per-jurisdiction sequence counter — no timestamps, no random ids.</p>\n"
     "    <table>\n"
     "      <thead><tr><th>Record</th><th>Kind</th><th>Unit</th><th>Jurisdiction</th><th>Immutable</th></tr></thead>\n"
     "      <tbody>\n"
     (str/join "\n" (map draft-row drafts)) "\n"
     "      </tbody>\n"
     "    </table>\n"
     "  </section>\n"

     "  <section class=\"card\">\n"
     "    <h2>Audit ledger (" (count ledger) " facts)</h2>\n"
     "    <p class=\"muted\">The append-only decision-fact log this run produced. <code>pressureequip.store/append-ledger!</code> is reached from exactly two graph nodes, so the only fact types that can appear are <code>committed</code>, <code>governor-hold</code> and <code>approval-rejected</code>.</p>\n"
     "    <table>\n"
     "      <thead><tr><th>Fact</th><th>Op</th><th>Unit</th><th>Disposition</th><th>Basis</th><th>Summary</th></tr></thead>\n"
     "      <tbody>\n"
     (str/join "\n" (map ledger-row ledger)) "\n"
     "      </tbody>\n"
     "    </table>\n"
     "  </section>\n"

     "</main>\n"
     "<footer>\n"
     "  <p class=\"muted\">Generated at build time by <code>pressureequip.render-html</code> from a fresh <code>pressureequip.store/seed-db</code>. Deterministic: two consecutive runs are byte-identical. No invented units, no invented metrics.</p>\n"
     "</footer>\n"
     "</body></html>\n")))

(defn -main [& args]
  (let [out (or (first args) "docs/samples/operator-console.html")
        db (run-demo!)
        f (java.io.File. out)]
    (when-let [parent (.getParentFile f)] (.mkdirs parent))
    (spit f (render db))
    (println "wrote" out
             "(" (count (store/ledger db)) "ledger facts,"
             (count (filter #(= :governor-hold (:t %)) (store/ledger db))) "governor holds,"
             (count (store/dispatch-history db)) "unit dispatches,"
             (count (store/evidence-history db)) "pressure-test certificates )")))
