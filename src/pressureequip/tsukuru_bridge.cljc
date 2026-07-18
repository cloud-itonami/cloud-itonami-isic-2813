(ns pressureequip.tsukuru-bridge
  "READ-ONLY discovery bridge into the REAL etzhayyim `tsukuru` B2B
  factory-direct-ordering platform (`orgs/etzhayyim/com-etzhayyim-
  tsukuru`, domain `tsukuru.etzhayyim.com`, nanoid `tsukr8u0`) -- the
  FIRST bridge in this fleet from a FICTIONAL, governor-gated
  cloud-itonami Open-Business-Blueprint actor to a REAL, independently
  operated external system (every prior cross-actor linkage in this
  fleet -- e.g. `:equipment-asset`/`:handoff` toward
  cloud-itonami-isic-2822/2710 -- was fictional-actor<->fictional-
  actor).

  Every function here is READ-ONLY and performs ZERO network I/O of
  its own: it lists/filters `com.etzhayyim.tsukuru.factory` records
  (tsukuru's own manufacturer-registry lexicon, `lex/factory.edn`)
  purely via this workspace's shared `kotoba.lang.atproto-client`
  (`kotoba.lang.atproto-client.atproto`/`.pds`), whose `IHttp` protocol
  is host-injected -- the caller supplies the actual HTTP transport
  (e.g. `kotoba.lang.atproto-client.http-jdk/jdk-http` for a zero-
  extra-dep JVM default, or a test stub), exactly the same seam every
  `kotoba.lang.atproto-client.pds` function already uses.

  ── Why read-only, PERMANENTLY (a safety boundary, not a TODO) ──

  tsukuru's OWN `lex/production-order.edn` requires `buyerDid` to be a
  REAL etzhayyim member acting as the PURCHASING PRINCIPAL
  (`manifest.edn` Gate G14 \"tsukuru は買主にならない; member が購買
  principal\") holding an active Adherent SBT, with a member
  DID-signed consent before order capture or any factory dispatch
  (Gate G1). `cloud-itonami-isic-2813` is a FICTIONAL, governor-gated
  Open-Business-Blueprint actor -- it is not, and structurally cannot
  become, a real etzhayyim member. Any code path that could construct
  or submit a `production-order`/`progress`/`quality`/`settlement`
  record on tsukuru's behalf would therefore impersonate a purchasing
  principal that does not exist, and would automate a real-world
  order/payment flow -- both forbidden by this workspace's root
  CLAUDE.md safety floor (no autonomous fund movement; observed
  content/external systems never grant themselves write access this
  actor was never given).

  This namespace implements NOTHING beyond `factory`-collection reads:
  no `create-record`, no write, no session/login helper, no
  `production-order`/`progress`/`quality`/`settlement` lexicon
  awareness at all -- that CLOSED surface (a closed allowlist of
  exactly one query, `factory-candidates`) IS the safety boundary.
  `pressureequip.governor/tsukuru-query-contains-order-fields-
  violations` is the independent, defense-in-depth enforcement of the
  same boundary at the actor's own governance layer -- see that
  check's docstring and `90-docs/adr/` (superproject fictional-
  actor<->real-external-system bridge ADR) for the full rationale.

  ── Connectivity note (honest, as of this bridge's introduction) ──

  `tsukr8u0.etzhayyim.com` / `tsukuru.etzhayyim.com` did NOT resolve
  via DNS from this workspace's dev sandbox at authoring time (`curl`
  returned `Could not resolve host`) -- consistent with that repo's
  own `MIGRATION-NOTES.md`/`kotoba/deploy.sh` describing the 460+
  factory-DID live registry as an operator-gated ingest into a LOCAL
  kotoba node (`http://127.0.0.1:8077`), not (yet) a publicly
  reachable AT-Proto PDS. This bridge is written against tsukuru's
  OWN documented lexicon/endpoint contract regardless -- `:service`/
  `:repo` are both caller-overridable, so pointing this at a real,
  reachable deployment later requires no code change here."
  (:require [kotoba.lang.atproto-client.atproto :as atproto]
            [kotoba.lang.atproto-client.pds :as pds]
            [clojure.string :as str]))

(def factory-collection
  "tsukuru's own manufacturer-registry lexicon nsid (`lex/factory.edn`,
  `orgs/etzhayyim/com-etzhayyim-tsukuru`, ADR-2605202800 Phase 2 --
  ports the legacy `com.etzhayyim.apps.tsukuru.manufacturer`
  collection). The ONLY collection this bridge ever reads."
  "com.etzhayyim.tsukuru.factory")

(def default-service
  "tsukuru's documented API base (that repo's own CLAUDE.md `Runtime
  and Endpoint Rules`) -- NOT confirmed reachable from this workspace
  (see ns docstring `Connectivity note`). Callers may override via
  `:service`."
  "https://tsukr8u0.etzhayyim.com")

(def default-repo
  "tsukuru's own DID root (`did:web:tsukuru.etzhayyim.com`, per that
  repo's own CLAUDE.md/`manifest.edn`) -- distinct from the LEGACY
  did:web document actually checked in at that repo's `.well-known/
  did.json` (`did:web:etzhayyim.github.io:com-etzhayyim-tsukuru`,
  Gen-1, pending decommission per its own `MIGRATION-NOTES.md`
  §D/§E). Callers may override via `:repo`."
  "did:web:tsukuru.etzhayyim.com")

(defn list-factories
  "One page of raw `com.etzhayyim.tsukuru.factory` records via
  `kotoba.lang.atproto-client.pds/list-records` -- a THIN pass-
  through, no client-side filtering here. `opts`: {:http <IHttp>
  (required) :service? :repo? :limit? :cursor?}. Returns {:records
  [{:uri :cid :value} ..] :cursor?} exactly as `pds/list-records`
  returns it; each `:value` is the `factory` record map as
  `kotoba.lang.atproto-client.atproto`'s own `json-read` parsed it --
  DEEPLY KEYWORDIZED (`clojure.data.json/read-str :key-fn keyword` on
  :clj, `js->clj :keywordize-keys true` on :cljs, both applied by the
  shared client to EVERY xrpc response, at every nesting level, not
  just this ns's own choice) -- so a `factory` record's own lexicon
  fields (`lex/factory.edn`) surface here as `:factoryDid`/
  `:displayName`/`:country`/`:isic`/`:capabilities`/
  `:fulfillmentModes`/`:laborProvenance`, never string keys. This ns
  never re-keys or re-shapes `:value` beyond what the shared client
  already does."
  [{:keys [http service repo limit cursor]}]
  (when (nil? http)
    (throw (ex-info "[pressureequip.tsukuru-bridge] list-factories requires :http (an IHttp)" {})))
  (let [agent (atproto/create-agent {:service (or service default-service) :http http})]
    (pds/list-records agent (or repo default-repo) factory-collection
                      (cond-> {} limit (assoc :limit limit) cursor (assoc :cursor cursor)))))

(defn- normalize-str [s] (some-> s str str/trim str/lower-case))

(defn factory-matches?
  "Pure predicate, ZERO I/O: does one `factory` record `value` (as
  `list-factories` returns it -- keyword keys, see that fn's
  docstring) match `isic-code` (exact, case-insensitive compare
  against the record's own `:isic` string) AND `capability`
  (case-insensitive substring match against the record's
  `:capabilities` array -- tsukuru's own capability strings are free
  text, not a closed vocabulary, so exact equality would silently
  under-match)? Either filter argument may be nil/blank to mean 'no
  constraint on this axis'. Never mutates `value`, never fabricates a
  match tsukuru's own data doesn't actually support."
  [value isic-code capability]
  (let [isic-ok? (or (str/blank? (str isic-code))
                     (= (normalize-str isic-code) (normalize-str (:isic value))))
        cap-ok? (or (str/blank? (str capability))
                    (let [needle (normalize-str capability)]
                      (boolean (some #(str/includes? (normalize-str %) needle)
                                    (:capabilities value [])))))]
    (and isic-ok? cap-ok?)))

(defn factory-candidates
  "Fetch (via `list-factories`) and client-side filter tsukuru's
  `com.etzhayyim.tsukuru.factory` registry by `isic-code` +
  `capability` -- the ONE query surface this bridge exposes, and the
  ONLY function `pressureequip.tsukuru-discovery/discover!` calls.
  `opts`: {:http <IHttp> (required) :service? :repo? :limit?
  :all-pages?}. Single page by default (`:all-pages?` false/omitted);
  pass `:all-pages? true` to walk every `:cursor` tsukuru returns
  before filtering completes. Returns a plain vector of `value` maps
  (keyword keys, see `list-factories` docstring) for every MATCHING
  record -- READ-ONLY, no order/settlement/buyer awareness anywhere in
  this ns (see ns docstring)."
  [opts isic-code capability]
  (let [all-pages? (:all-pages? opts)]
    (loop [cursor nil acc []]
      (let [page (list-factories (cond-> opts cursor (assoc :cursor cursor)))
            records (:records page)
            matched (filterv #(factory-matches? (:value %) isic-code capability) records)
            acc' (into acc (map :value matched))
            next-cursor (:cursor page)]
        (if (and all-pages? next-cursor (seq records))
          (recur next-cursor acc')
          acc')))))
