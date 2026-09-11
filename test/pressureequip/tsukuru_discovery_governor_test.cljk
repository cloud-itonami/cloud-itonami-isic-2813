(ns pressureequip.tsukuru-discovery-governor-test
  "Governor contract for `:discover/tsukuru-factory-candidates` -- the
  first bridge in this fleet from this FICTIONAL, governor-gated actor
  to a REAL, independently operated external system
  (`orgs/etzhayyim/com-etzhayyim-tsukuru`). Mirrors
  `governor_contract_test.clj`'s own `exec-op`/`operator` pattern
  EXACTLY -- this file only exercises `pressureequip.governor`/
  `pressureequip.phase`/`pressureequip.pressureequipadvisor`/
  `pressureequip.operation` (all on the DEFAULT classpath, `clojure
  -M:test`), with `:candidates` supplied directly on the request --
  the SAME 'advisor never fetches, only normalizes what it's handed'
  discipline `propose-tsukuru-discovery` documents. It deliberately
  does NOT touch `pressureequip.tsukuru-bridge`/`kotoba.lang.atproto-
  client` at all (see `test-tsukuru-bridge/pressureequip/
  tsukuru_bridge_test.clj`, run via `clojure -M:tsukuru-bridge`, for
  the mock-HTTP-backed bridge-level coverage)."
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

(def ^:private sample-candidates
  "Shaped exactly as `pressureequip.tsukuru-bridge/factory-candidates`
  actually returns them -- keyword keys (`kotoba.lang.atproto-client`'s
  own `json-read` deeply keywordizes every parsed field; see that ns's
  docstring), never string keys."
  [{:factoryDid "did:web:factory-1.example.com" :displayName "Example Machine Works"
    :country "JP" :isic "2822" :capabilities ["cnc-machining" "welding"]
    :fulfillmentModes ["mto"] :laborProvenance "iso-45001-audited"}])

(deftest clean-discovery-auto-commits-with-no-approval
  (testing "a well-formed, contamination-free discovery request -> COMMIT immediately (read-op, never escalates)"
    (let [[db actor] (fresh)
          res (exec-op actor "d1"
                    {:op :discover/tsukuru-factory-candidates
                     :subject "tsukuru-query:2822:cnc-machining"
                     :isic-code "2822" :capability "cnc-machining"
                     :candidates sample-candidates} operator)]
      (is (not= :interrupted (:status res)) "read-only discovery never pauses for human approval")
      (is (= :commit (get-in res [:state :disposition])))
      (is (= 1 (count (store/ledger db))))
      (is (= :committed (:t (first (store/ledger db))))))))

(deftest invalid-isic-code-is-held
  (testing "a malformed ISIC code -> HOLD, never reaches a human, no ledger entry beyond the hold"
    (let [[db actor] (fresh)
          res (exec-op actor "d2"
                    {:op :discover/tsukuru-factory-candidates
                     :subject "tsukuru-query:bogus:cnc-machining"
                     :isic-code "not-an-isic-code!!" :capability "cnc-machining"
                     :candidates []} operator)]
      (is (= :hold (get-in res [:state :disposition])))
      (is (some #{:tsukuru-discovery-invalid-isic-code} (-> (store/ledger db) first :basis))))))

(deftest blank-capability-is-held
  (testing "an empty/blank capability keyword -> HOLD"
    (let [[db actor] (fresh)
          res (exec-op actor "d3"
                    {:op :discover/tsukuru-factory-candidates
                     :subject "tsukuru-query:2822:"
                     :isic-code "2822" :capability ""
                     :candidates []} operator)]
      (is (= :hold (get-in res [:state :disposition])))
      (is (some #{:tsukuru-discovery-missing-capability} (-> (store/ledger db) first :basis))))))

(deftest missing-capability-is-held
  (testing "a nil capability -> HOLD (same rule as blank)"
    (let [[db actor] (fresh)
          res (exec-op actor "d3b"
                    {:op :discover/tsukuru-factory-candidates
                     :subject "tsukuru-query:2822:nil"
                     :isic-code "2822"
                     :candidates []} operator)]
      (is (= :hold (get-in res [:state :disposition])))
      (is (some #{:tsukuru-discovery-missing-capability} (-> (store/ledger db) first :basis))))))

(deftest order-field-smuggled-into-request-is-held-and-unoverridable
  (testing "a :buyerDid smuggled directly onto the request (alongside :isic-code/:capability) -> HARD HOLD -- this actor can never legitimately be tsukuru's purchasing principal"
    (let [[db actor] (fresh)
          res (exec-op actor "d4"
                    {:op :discover/tsukuru-factory-candidates
                     :subject "tsukuru-query:2822:cnc-machining"
                     :isic-code "2822" :capability "cnc-machining"
                     :buyerDid "did:web:some-etzhayyim-member.example.com"
                     :candidates sample-candidates} operator)]
      (is (= :hold (get-in res [:state :disposition])))
      (is (some #{:tsukuru-query-contains-order-fields} (-> (store/ledger db) first :basis))))))

(deftest order-field-smuggled-into-candidates-is-held
  (testing "an :orderId key hiding inside a fetched candidate's own map -> HARD HOLD -- never trust the fetched payload either"
    (let [[db actor] (fresh)
          contaminated (conj sample-candidates {:factoryDid "did:web:factory-2.example.com"
                                                 :displayName "Suspicious Factory"
                                                 :country "US" :isic "2822"
                                                 :capabilities ["cnc-machining"]
                                                 :orderId "should-never-be-here"})
          res (exec-op actor "d5"
                    {:op :discover/tsukuru-factory-candidates
                     :subject "tsukuru-query:2822:cnc-machining"
                     :isic-code "2822" :capability "cnc-machining"
                     :candidates contaminated} operator)]
      (is (= :hold (get-in res [:state :disposition])))
      (is (some #{:tsukuru-query-contains-order-fields} (-> (store/ledger db) first :basis))))))

(deftest settlement-field-smuggled-as-keyword-is-held
  (testing "a keyword-form :settlement-id anywhere in the request -> HARD HOLD, same closed contamination set covers keyword AND string forms"
    (let [[db actor] (fresh)
          res (exec-op actor "d6"
                    {:op :discover/tsukuru-factory-candidates
                     :subject "tsukuru-query:2822:cnc-machining"
                     :isic-code "2822" :capability "cnc-machining"
                     :settlement-id "stl-001"
                     :candidates []} operator)]
      (is (= :hold (get-in res [:state :disposition])))
      (is (some #{:tsukuru-query-contains-order-fields} (-> (store/ledger db) first :basis))))))

(deftest discovery-never-writes-to-the-pressure-equipment-ssot
  (testing "a clean, committed discovery query touches ONLY the ledger -- no unit/dispatch/evidence/equipment-asset/part-receipt record is ever created by this op"
    (let [[db actor] (fresh)
          before-units (store/all-units db)]
      (exec-op actor "d7"
              {:op :discover/tsukuru-factory-candidates
               :subject "tsukuru-query:2822:cnc-machining"
               :isic-code "2822" :capability "cnc-machining"
               :candidates sample-candidates} operator)
      (is (= before-units (store/all-units db)) "unit directory untouched")
      (is (empty? (store/dispatch-history db)))
      (is (empty? (store/evidence-history db)))
      (is (empty? (store/all-equipment-assets db)))
      (is (empty? (store/all-part-receipts db))))))
