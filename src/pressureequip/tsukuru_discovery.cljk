(ns pressureequip.tsukuru-discovery
  "Operation-layer entry point for `:discover/tsukuru-factory-
  candidates` -- the ONE place in this actor that performs the actual
  READ-ONLY network query against the REAL `orgs/etzhayyim/com-
  etzhayyim-tsukuru` factory registry (via `pressureequip.tsukuru-
  bridge`, an injected `:http` IHttp transport) BEFORE driving the
  request through `pressureequip.operation`'s SAME advise -> govern ->
  decide graph every other op uses.

  Deliberately kept SEPARATE from `pressureequip.operation`: that ns
  is always on the default classpath (every existing test in this
  actor requires it directly, e.g. `governor_contract_test.clj`), so
  requiring `pressureequip.tsukuru-bridge` (and transitively
  `kotoba.lang.atproto-client`) FROM `pressureequip.operation` would
  force an AT-Proto client dependency onto every ordinary `clojure
  -M:test` run of this actor. This ns -- and `pressureequip.tsukuru-
  bridge` itself -- are only ever required by callers who explicitly
  opt in (this repo's `deps.edn` `:tsukuru-bridge` alias, mirroring
  how `:visualize` isolates `kotoba-lang/webgpu`+`playwright`).

  See `pressureequip.tsukuru-bridge` ns docstring for the full
  read-only safety-boundary rationale (tsukuru's own `production-
  order` lexicon requires a REAL etzhayyim member as purchasing
  principal, which this FICTIONAL actor structurally is not) --
  `discover!` below implements NOTHING beyond fetching+filtering
  `factory` records and handing them to the governed graph; it has no
  order/settlement/buyer awareness at all."
  (:require [langgraph.graph :as g]
            [pressureequip.phase :as phase]
            [pressureequip.tsukuru-bridge :as tsukuru-bridge]))

(defn discover!
  "Runs ONE `:discover/tsukuru-factory-candidates` OperationActor pass
  end-to-end: (1) fetches READ-ONLY candidates from the REAL tsukuru
  `com.etzhayyim.tsukuru.factory` registry via
  `pressureequip.tsukuru-bridge/factory-candidates` (an injected
  `:http` IHttp transport does the actual network call -- e.g.
  `kotoba.lang.atproto-client.http-jdk/jdk-http` for a real attempt, or
  a test stub; this function performs no I/O beyond delegating to the
  bridge), THEN (2) drives the pre-fetched candidates through
  `pressureequip.operation`'s compiled graph, so
  `pressureequip.governor`'s input-validation and order-field-
  contamination checks apply exactly as they would to any other op.

  `actor` is a compiled graph from `pressureequip.operation/build`.
  `opts`:
    :http        -- (required) an IHttp transport for the tsukuru fetch
    :isic-code   -- ISIC rev.4 section/class code to search for
    :capability  -- capability keyword/string to search for
    :service?    -- override tsukuru's API base (default:
                    `tsukuru-bridge/default-service`)
    :repo?       -- override tsukuru's DID root (default:
                    `tsukuru-bridge/default-repo`)
    :thread-id?  -- checkpoint thread-id (default: a fresh random one,
                    since a discovery query is never resumed/approved)
    :actor-id?/:phase? -- forwarded into `context`, same shape every
                    sibling op's `context` already uses

  Returns whatever `langgraph.graph/run*` returns for this op (a
  read-only, non-high-stakes op ends up governor-clean and phase-
  passthrough -- see `pressureequip.phase/read-ops` -- so a
  well-formed query auto-commits without ever reaching
  `:request-approval`, exactly like every other sibling actor's own
  `read-ops` query). This is the ONLY tsukuru-related entry point this
  actor implements -- see `pressureequip.tsukuru-bridge` ns docstring
  for why real production-order/settlement ops are deliberately absent
  (a closed allowlist of exactly this one op IS the safety boundary)."
  [actor {:keys [http isic-code capability service repo thread-id actor-id phase]}]
  (let [candidates (tsukuru-bridge/factory-candidates
                    (cond-> {:http http}
                      service (assoc :service service)
                      repo (assoc :repo repo))
                    isic-code capability)
        request {:op :discover/tsukuru-factory-candidates
                 :subject (str "tsukuru-query:" isic-code ":" capability)
                 :isic-code isic-code
                 :capability capability
                 :candidates candidates}
        context {:actor-id (or actor-id "cloud-itonami-isic-2813")
                 :actor-role :pressure-equipment-engineer
                 :phase (or phase phase/default-phase)}
        tid (or thread-id (str "tsukuru-discover-" (rand-int 1000000000)))]
    (g/run* actor {:request request :context context} {:thread-id tid})))
