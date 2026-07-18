(ns pressureequip.tsukuru-bridge-test
  "Mock-HTTP-backed coverage of `pressureequip.tsukuru-bridge` (this
  actor's `kotoba.lang.atproto-client`-based READ-ONLY bridge into the
  REAL `orgs/etzhayyim/com-etzhayyim-tsukuru` factory registry) --
  NEVER touches the real `tsukr8u0.etzhayyim.com`. The stub `IHttp`
  below implements `kotoba.lang.atproto-client.atproto/IHttp` directly
  (no embedded HTTP server needed: `-request` is a plain function, so
  a fixture-returning `reify` is a complete, correct transport for
  these tests -- the SAME seam `kotoba.lang.atproto-client`'s own
  `pds_test.clj` exercises with a real socket, minus the socket).

  Lives OUTSIDE `test/`, in `test-tsukuru-bridge/`, exactly mirroring
  this repo's own `test-visualize/` isolation (see `deps.edn`'s
  `:tsukuru-bridge` alias comment) -- `io.github.kotoba-lang/atproto-
  client` is NOT on the classpath for a plain `clojure -M:test` run.
  Run with:
    clojure -M:tsukuru-bridge"
  (:require [clojure.test :refer [deftest is testing]]
            [clojure.string :as str]
            [clojure.data.json :as json]
            [kotoba.lang.atproto-client.atproto :as atproto]
            [pressureequip.tsukuru-bridge :as bridge]
            [pressureequip.tsukuru-discovery :as discovery]
            [pressureequip.operation :as op]
            [pressureequip.store :as store]))

;; ─── fixture pages (tsukuru's own lex/factory.edn shape, on the wire as JSON) ──

(def ^:private page-1
  {:records
   [{:uri "at://did:web:tsukuru.etzhayyim.com/com.etzhayyim.tsukuru.factory/r1"
     :cid "bafyreialr2vrqpzh4ay4r3jqfd76nlpchdfpyrhqbfgsodwxqndvonbymq"
     :value {"factoryDid" "did:web:factory-1.example.com" "displayName" "Example Machine Works"
             "country" "JP" "isic" "2822" "capabilities" ["cnc-machining" "welding"]
             "fulfillmentModes" ["mto"] "laborProvenance" "iso-45001-audited"}}
    {:uri "at://did:web:tsukuru.etzhayyim.com/com.etzhayyim.tsukuru.factory/r2"
     :cid "bafyreib5oyv2m4siqvgqjqjxqjqxqjqxqjqxqjqxqjqxqjqxqjqxqjqxqu"
     :value {"factoryDid" "did:web:factory-2.example.com" "displayName" "Baltic Textiles Ltd"
             "country" "LT" "isic" "1310" "capabilities" ["weaving"]
             "fulfillmentModes" ["bto"] "laborProvenance" "unverified"}}]
   :cursor "page-2"})

(def ^:private page-2
  {:records
   [{:uri "at://did:web:tsukuru.etzhayyim.com/com.etzhayyim.tsukuru.factory/r3"
     :cid "bafyreic3z2sowplpjcqhkjjqjqxqjqxqjqxqjqxqjqxqjqxqjqxqjqxqu"
     :value {"factoryDid" "did:web:factory-3.example.com" "displayName" "Second Page CNC Shop"
             "country" "DE" "isic" "2822" "capabilities" ["CNC-Machining" "5-axis-milling"]
             "fulfillmentModes" ["mto" "cto"] "laborProvenance" "iso-45001-audited"}}]
   :cursor nil})

(defn- stub-http
  "IHttp stub -- ignores everything about the request except
  pagination: returns `page-2` when the GET URL's own query string
  carries `cursor=page-2` (`list-factories` -> `pds/list-records` ->
  `atproto/xrpc` puts `:cursor` on the query string for a GET), else
  `page-1`. `clojure.data.json/write-str` + the REAL `atproto/xrpc`'s
  own `json-read` round-trips these fixtures through actual JSON
  serialization/deserialization -- `:value`'s string keys above
  become KEYWORD keys by the time `pressureequip.tsukuru-bridge` ever
  sees them, exactly like the real wire response would (see
  `pressureequip.tsukuru-bridge/list-factories` docstring)."
  []
  (reify atproto/IHttp
    (-request [_ {:keys [url]}]
      (let [page (if (str/includes? (or url "") "cursor=page-2") page-2 page-1)]
        {:status 200 :body (json/write-str page)}))))

;; ─── list-factories / factory-matches? / factory-candidates ───────────────

(deftest list-factories-returns-one-page-deeply-keywordized
  (let [http (stub-http)
        {:keys [records cursor]} (bridge/list-factories {:http http})]
    (is (= 2 (count records)))
    (is (= "page-2" cursor))
    (is (= "did:web:factory-1.example.com" (:factoryDid (:value (first records))))
        "atproto-client's own json-read deeply keywordizes -- :factoryDid, not \"factoryDid\"")
    (is (nil? (get (:value (first records)) "factoryDid"))
        "a string-keyed lookup on the (now keyword-keyed) parsed map correctly misses")))

(deftest factory-matches-isic-and-capability
  (testing "exact case-insensitive ISIC match + substring case-insensitive capability match"
    (is (true? (bridge/factory-matches? {:isic "2822" :capabilities ["CNC-Machining"]} "2822" "cnc")))
    (is (false? (bridge/factory-matches? {:isic "1310" :capabilities ["weaving"]} "2822" "cnc"))))
  (testing "blank isic-code/capability means 'no constraint on this axis'"
    (is (true? (bridge/factory-matches? {:isic "1310" :capabilities ["weaving"]} nil nil)))
    (is (true? (bridge/factory-matches? {:isic "1310" :capabilities ["weaving"]} "" "")))))

(deftest factory-candidates-single-page-filters-client-side
  (let [http (stub-http)
        matches (bridge/factory-candidates {:http http} "2822" "cnc")]
    (is (= 1 (count matches)) "only the ISIC 2822 + cnc-machining match on page 1 (single-page default)")
    (is (= "did:web:factory-1.example.com" (:factoryDid (first matches))))))

(deftest factory-candidates-all-pages-walks-cursor
  (let [http (stub-http)
        matches (bridge/factory-candidates {:http http :all-pages? true} "2822" "cnc")]
    (is (= 2 (count matches)) "one ISIC-2822+cnc match on page 1 + one on page 2, cursor walked")
    (is (= #{"did:web:factory-1.example.com" "did:web:factory-3.example.com"}
          (set (map :factoryDid matches))))))

(deftest factory-candidates-no-match-returns-empty-not-fabricated
  (let [http (stub-http)]
    (is (= [] (bridge/factory-candidates {:http http} "9999" "no-such-capability")))))

(deftest list-factories-requires-http
  (is (thrown? clojure.lang.ExceptionInfo (bridge/list-factories {}))))

;; ─── end-to-end: discover! (real bridge fetch, via the mock transport, -> governed graph) ──

(deftest discover-clean-query-auto-commits-with-real-candidates-from-the-bridge
  (let [http (stub-http)
        db (store/seed-db)
        actor (op/build db)
        res (discovery/discover! actor {:http http :isic-code "2822" :capability "cnc"})]
    (is (not= :interrupted (:status res)) "read-only discovery never pauses for human approval")
    (is (= :commit (get-in res [:state :disposition])))
    (is (= 1 (count (store/ledger db))))))

(deftest discover-invalid-isic-is-held-even-though-the-fetch-itself-succeeds
  (testing "the bridge fetch succeeds (mock returns 200), but the governor still HARD-holds on malformed input"
    (let [http (stub-http)
          db (store/seed-db)
          actor (op/build db)
          res (discovery/discover! actor {:http http :isic-code "!!!" :capability "cnc"})]
      (is (= :hold (get-in res [:state :disposition])))
      (is (some #{:tsukuru-discovery-invalid-isic-code} (-> (store/ledger db) first :basis))))))

(deftest discover-single-page-default-never-sees-a-second-page-match-but-still-commits-cleanly
  (testing "discover! doesn't opt into :all-pages?, so page-2's \"5-axis-milling\" capability is never fetched -- zero matches on page 1 is a well-formed EMPTY result, not a fabricated one, and a well-formed (if empty) discovery request still auto-commits"
    (let [http (stub-http)
          db (store/seed-db)
          actor (op/build db)
          res (discovery/discover! actor {:http http :isic-code "2822" :capability "5-axis"})]
      (is (not= :interrupted (:status res)))
      (is (= :commit (get-in res [:state :disposition]))))))
