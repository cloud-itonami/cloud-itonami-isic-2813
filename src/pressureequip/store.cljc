(ns pressureequip.store
  "SSoT for the pressure-equipment-manufacturing actor, behind a
  `Store` protocol so the backend is a swap, not a rewrite -- the same
  seam every prior `cloud-itonami-isic-*` actor in this fleet uses:

    - `MemStore`     -- atom of EDN. The deterministic default for
                        dev/tests/demo (no deps).
    - `DatomicStore` -- backed by `langchain.db`, a Datomic-API-compatible
                        EAV store (datalog q / pull / upsert). Pure `.cljc`,
                        so it runs offline AND can be pointed at a real
                        Datomic Local or a kotoba-server pod by swapping
                        `langchain.db`'s `:db-api` (see langchain.kotoba-db).

  Both implement the same protocol and pass the same contract
  (test/pressureequip/store_contract_test.clj), which is the whole
  point: the actor, the Pressure Equipment Governor and the audit
  ledger never know which SSoT they run on.

  Like every other dual-actuation sibling before it, this actor has
  TWO actuation events (dispatching a unit action, issuing a
  pressure-test certificate) acting on the SAME entity (a unit), each
  with its OWN history collection, sequence counter and dedicated
  double-actuation-guard boolean (`:unit-dispatched?`/
  `:pressure-test-certified?`, never a `:status` value) -- the same
  discipline every prior sibling governor's guards establish, informed
  by `cloud-itonami-isic-6492`'s status-lifecycle bug
  (ADR-2607071320).

  The ledger stays append-only on every backend: 'which unit was
  screened for an unresolved hydrostatic/pneumatic pressure-test
  defect, which unit action was dispatched, which pressure-test
  certificate was issued, on what jurisdictional basis, approved by
  whom' is always a query over an immutable log -- the audit trail a
  community trusting a pressure-equipment manufacturer needs, and the
  evidence a manufacturer needs if a dispatch or pressure-test-
  certificate decision is later disputed."
  (:require #?(:clj  [clojure.edn :as edn]
               :cljs [cljs.reader :as edn])
            [pressureequip.registry :as registry]
            [langchain.db :as d]))

(defprotocol Store
  (unit [s id])
  (all-units [s])
  (pressure-screen-of [s unit-id] "committed hydrostatic/pneumatic pressure-test screening verdict for a unit, or nil")
  (requirements-verification-of [s unit-id] "committed design-rules requirements verification, or nil")
  (ledger [s])
  (dispatch-history [s] "the append-only unit-dispatch history (pressureequip.registry drafts)")
  (evidence-history [s] "the append-only pressure-test-certificate history (pressureequip.registry drafts)")
  (maintenance-notice-history [s] "the append-only maintenance/recall-notice history (pressureequip.registry drafts) -- a unit may appear more than once, unlike dispatch/evidence")
  (next-dispatch-sequence [s jurisdiction] "next dispatch-number sequence for a jurisdiction")
  (next-evidence-sequence [s jurisdiction] "next evidence-number sequence for a jurisdiction")
  (next-maintenance-notice-sequence [s jurisdiction] "next maintenance-notice-number sequence for a jurisdiction")
  (unit-already-dispatched? [s unit-id] "has this unit's action already been dispatched?")
  (unit-already-certified? [s unit-id] "has this unit's pressure-test certificate already been issued?")
  (equipment-asset [s id] "a registered `:equipment-asset` (superproject equipment-asset-linkage shared shape) by id, or nil -- isic-2813's RECEIVE side, toward an upstream manufacturer actor e.g. cloud-itonami-isic-2822")
  (all-equipment-assets [s] "every registered equipment asset")
  (equipment-asset-already-registered? [s id] "has an equipment asset with this id already been registered?")
  (commit-record! [s record] "apply a committed op's record to the SSoT")
  (append-ledger! [s fact]   "append one immutable decision fact")
  (with-units [s units] "replace/seed the unit directory (map id->unit)"))

;; ----------------------------- demo data -----------------------------

(defn demo-data
  "A small, self-contained unit set covering both actuation
  lifecycles (dispatching a unit action, issuing a pressure-test
  certificate) so the actor + tests run offline."
  []
  {:units
   {"unit-1" {:id "unit-1" :unit-name "Sakura API 610 Centrifugal Pump CP-04"
                  :test-pressure-actual 13.5 :test-pressure-min 13.0 :test-pressure-max 15.0
                  :pressure-test-defect-unresolved? false
                  :unit-dispatched? false :pressure-test-certified? false
                  :jurisdiction "JPN" :status :intake
                  ;; Optional reference into `pressureequip.facts/unit-types`
                  ;; (the UNSPSC/GTIN concrete-unit-model catalog) -- demo
                  ;; wiring proving the reference resolves; see
                  ;; `pressureequip.governor/unit-type-unregistered-violations`.
                  :unit-type-id :unit/industrial-refrigeration-compressor}
    "unit-2" {:id "unit-2" :unit-name "Atlantis Screw Compressor SC-12"
                  :test-pressure-actual 13.5 :test-pressure-min 13.0 :test-pressure-max 15.0
                  :pressure-test-defect-unresolved? false
                  :unit-dispatched? false :pressure-test-certified? false
                  :jurisdiction "ATL" :status :intake}
    "unit-3" {:id "unit-3" :unit-name "鈴木精密仕切弁 SV-07"
                  :test-pressure-actual 18.0 :test-pressure-min 13.0 :test-pressure-max 15.0
                  :pressure-test-defect-unresolved? false
                  :unit-dispatched? false :pressure-test-certified? false
                  :jurisdiction "JPN" :status :intake}
    "unit-4" {:id "unit-4" :unit-name "田中往復動圧縮機 RC-03"
                  :test-pressure-actual 13.5 :test-pressure-min 13.0 :test-pressure-max 15.0
                  :pressure-test-defect-unresolved? true
                  :unit-dispatched? false :pressure-test-certified? false
                  :jurisdiction "JPN" :status :intake}}})

;; ----------------------------- shared commit logic -----------------------------

(defn- dispatch-unit!
  "Backend-agnostic `:unit/mark-dispatched` -- looks up the
  unit via the protocol and drafts the unit-dispatch record,
  and returns {:result .. :unit-patch ..} for the caller to
  persist."
  [s unit-id]
  (let [a (unit s unit-id)
        seq-n (next-dispatch-sequence s (:jurisdiction a))
        result (registry/register-unit-dispatch unit-id (:jurisdiction a) seq-n)]
    {:result result
     :unit-patch {:unit-dispatched? true
                      :dispatch-number (get result "dispatch_number")}}))

(defn- issue-pressure-test-certificate!
  "Backend-agnostic `:unit/mark-certified` -- looks up the
  unit via the protocol and drafts the pressure-test-certificate
  record, and returns {:result .. :unit-patch ..} for the caller
  to persist."
  [s unit-id]
  (let [a (unit s unit-id)
        seq-n (next-evidence-sequence s (:jurisdiction a))
        result (registry/register-pressure-test-certificate unit-id (:jurisdiction a) seq-n)]
    {:result result
     :unit-patch {:pressure-test-certified? true
                      :evidence-number (get result "evidence_number")}}))

(defn- issue-maintenance-notice!
  "Backend-agnostic `:maintenance-notice/issue` -- looks up the unit
  via the protocol and drafts the maintenance/recall-notice record.
  Returns {:result ..} for the caller to persist -- unlike
  `dispatch-unit!`/`issue-pressure-test-certificate!`, there is no
  `:unit-patch`: a unit may receive many maintenance notices over its
  life, so there is no dedicated single-shot `:unit-*?` boolean to
  flip."
  [s unit-id dispatch-ref]
  (let [a (unit s unit-id)
        seq-n (next-maintenance-notice-sequence s (:jurisdiction a))]
    {:result (registry/register-maintenance-notice unit-id dispatch-ref (:jurisdiction a) seq-n)}))

;; ----------------------------- MemStore (default) -----------------------------

(defrecord MemStore [a]
  Store
  (unit [_ id] (get-in @a [:units id]))
  (all-units [_] (sort-by :id (vals (:units @a))))
  (pressure-screen-of [_ id] (get-in @a [:pressure-screens id]))
  (requirements-verification-of [_ unit-id] (get-in @a [:verifications unit-id]))
  (ledger [_] (:ledger @a))
  (dispatch-history [_] (:dispatches @a))
  (evidence-history [_] (:evidences @a))
  (maintenance-notice-history [_] (:maintenance-notices @a))
  (next-dispatch-sequence [_ jurisdiction] (get-in @a [:dispatch-sequences jurisdiction] 0))
  (next-evidence-sequence [_ jurisdiction] (get-in @a [:evidence-sequences jurisdiction] 0))
  (next-maintenance-notice-sequence [_ jurisdiction] (get-in @a [:maintenance-notice-sequences jurisdiction] 0))
  (unit-already-dispatched? [_ unit-id] (boolean (get-in @a [:units unit-id :unit-dispatched?])))
  (unit-already-certified? [_ unit-id] (boolean (get-in @a [:units unit-id :pressure-test-certified?])))
  (equipment-asset [_ id] (get-in @a [:equipment-assets id]))
  (all-equipment-assets [_] (vec (vals (:equipment-assets @a))))
  (equipment-asset-already-registered? [_ id] (contains? (:equipment-assets @a) id))
  (commit-record! [s {:keys [effect path value payload]}]
    (case effect
      :unit/upsert
      (swap! a update-in [:units (:id value)] merge value)

      :verification/set
      (swap! a assoc-in [:verifications (first path)] payload)

      :pressure-test-screen/set
      (swap! a assoc-in [:pressure-screens (first path)] payload)

      :unit/mark-dispatched
      (let [unit-id (first path)
            {:keys [result unit-patch]} (dispatch-unit! s unit-id)
            jurisdiction (:jurisdiction (unit s unit-id))]
        (swap! a (fn [state]
                   (-> state
                       (update-in [:dispatch-sequences jurisdiction] (fnil inc 0))
                       (update-in [:units unit-id] merge unit-patch)
                       (update :dispatches registry/append result))))
        result)

      :unit/mark-certified
      (let [unit-id (first path)
            {:keys [result unit-patch]} (issue-pressure-test-certificate! s unit-id)
            jurisdiction (:jurisdiction (unit s unit-id))]
        (swap! a (fn [state]
                   (-> state
                       (update-in [:evidence-sequences jurisdiction] (fnil inc 0))
                       (update-in [:units unit-id] merge unit-patch)
                       (update :evidences registry/append result))))
        result)

      :maintenance-notice/issue
      (let [unit-id (first path)
            {:keys [result]} (issue-maintenance-notice! s unit-id (:dispatch-ref value))
            jurisdiction (:jurisdiction (unit s unit-id))]
        (swap! a (fn [state]
                   (-> state
                       (update-in [:maintenance-notice-sequences jurisdiction] (fnil inc 0))
                       (update :maintenance-notices registry/append result))))
        result)

      :equipment-asset/register
      (swap! a assoc-in [:equipment-assets (:equipment-asset/id value)] value)
      nil)
    s)
  (append-ledger! [_ fact] (swap! a update :ledger conj fact) fact)
  (with-units [s units] (when (seq units) (swap! a assoc :units units)) s))

(defn seed-db
  "A MemStore seeded with the demo unit set. The deterministic
  default."
  []
  (->MemStore (atom (assoc (demo-data)
                           :verifications {} :pressure-screens {} :ledger [] :dispatch-sequences {}
                           :dispatches [] :evidence-sequences {} :evidences []
                           :maintenance-notice-sequences {} :maintenance-notices []
                           :equipment-assets {}))))

;; ----------------------------- DatomicStore (langchain.db) -----------------------------

(def ^:private schema
  "DataScript/Datomic-style schema: only constraint attrs are declared.
  Map/compound values (verification/pressure-screen payloads, ledger
  facts, dispatch/evidence records) are stored as EDN strings so
  `langchain.db` doesn't expand them into sub-entities -- the same
  convention every sibling actor's store uses."
  {:unit/id                            {:db/unique :db.unique/identity}
   :verification/unit-id               {:db/unique :db.unique/identity}
   :pressure-screen/unit-id            {:db/unique :db.unique/identity}
   :ledger/seq                         {:db/unique :db.unique/identity}
   :dispatch/seq                       {:db/unique :db.unique/identity}
   :evidence/seq                       {:db/unique :db.unique/identity}
   :maintenance-notice/seq             {:db/unique :db.unique/identity}
   :dispatch-sequence/jurisdiction     {:db/unique :db.unique/identity}
   :evidence-sequence/jurisdiction     {:db/unique :db.unique/identity}
   :maintenance-notice-sequence/jurisdiction {:db/unique :db.unique/identity}
   :equipment-asset/id                 {:db/unique :db.unique/identity}})

(defn- enc [v] (pr-str v))
(defn- dec* [s] (when s (edn/read-string s)))

(defn- unit->tx [{:keys [id unit-name test-pressure-actual test-pressure-min test-pressure-max
                          pressure-test-defect-unresolved?
                          unit-dispatched? pressure-test-certified?
                          jurisdiction status dispatch-number evidence-number unit-type-id]}]
  (cond-> {:unit/id id}
    unit-name                                   (assoc :unit/unit-name unit-name)
    test-pressure-actual                        (assoc :unit/test-pressure-actual test-pressure-actual)
    test-pressure-min                           (assoc :unit/test-pressure-min test-pressure-min)
    test-pressure-max                           (assoc :unit/test-pressure-max test-pressure-max)
    (some? pressure-test-defect-unresolved?)     (assoc :unit/pressure-test-defect-unresolved? pressure-test-defect-unresolved?)
    (some? unit-dispatched?)                     (assoc :unit/unit-dispatched? unit-dispatched?)
    (some? pressure-test-certified?)             (assoc :unit/pressure-test-certified? pressure-test-certified?)
    jurisdiction                                 (assoc :unit/jurisdiction jurisdiction)
    status                                       (assoc :unit/status status)
    dispatch-number                              (assoc :unit/dispatch-number dispatch-number)
    evidence-number                              (assoc :unit/evidence-number evidence-number)
    unit-type-id                                 (assoc :unit/unit-type-id unit-type-id)))

(def ^:private unit-pull
  [:unit/id :unit/unit-name :unit/test-pressure-actual
   :unit/test-pressure-min :unit/test-pressure-max
   :unit/pressure-test-defect-unresolved? :unit/unit-dispatched? :unit/pressure-test-certified?
   :unit/jurisdiction :unit/status :unit/dispatch-number :unit/evidence-number :unit/unit-type-id])

(defn- pull->unit [m]
  (when (:unit/id m)
    {:id (:unit/id m) :unit-name (:unit/unit-name m)
     :test-pressure-actual (:unit/test-pressure-actual m)
     :test-pressure-min (:unit/test-pressure-min m)
     :test-pressure-max (:unit/test-pressure-max m)
     :pressure-test-defect-unresolved? (boolean (:unit/pressure-test-defect-unresolved? m))
     :unit-dispatched? (boolean (:unit/unit-dispatched? m))
     :pressure-test-certified? (boolean (:unit/pressure-test-certified? m))
     :jurisdiction (:unit/jurisdiction m) :status (:unit/status m)
     :dispatch-number (:unit/dispatch-number m) :evidence-number (:unit/evidence-number m)
     :unit-type-id (:unit/unit-type-id m)}))

(def ^:private equipment-asset-pull
  "Pull attrs for a registered `:equipment-asset` entity -- the same
  flat superproject shared-shape keys `commit-record!`'s
  `:equipment-asset/register` case transacts directly (no `enc`/`dec*`
  needed, every value here is already a top-level scalar)."
  [:equipment-asset/id :equipment-asset/unit-type-id :equipment-asset/source-actor
   :equipment-asset/dispatch-ref :equipment-asset/installed-at-iso :equipment-asset/station-cell])

(defn- pull->equipment-asset [m]
  (when (:equipment-asset/id m) m))

(defrecord DatomicStore [conn]
  Store
  (unit [_ id]
    (pull->unit (d/pull (d/db conn) unit-pull [:unit/id id])))
  (all-units [_]
    (->> (d/q '[:find [?id ...] :where [?e :unit/id ?id]] (d/db conn))
         (map #(pull->unit (d/pull (d/db conn) unit-pull [:unit/id %])))
         (sort-by :id)))
  (pressure-screen-of [_ id]
    (dec* (d/q '[:find ?p . :in $ ?aid
                :where [?k :pressure-screen/unit-id ?aid] [?k :pressure-screen/payload ?p]]
              (d/db conn) id)))
  (requirements-verification-of [_ unit-id]
    (dec* (d/q '[:find ?p . :in $ ?aid
                :where [?a :verification/unit-id ?aid] [?a :verification/payload ?p]]
              (d/db conn) unit-id)))
  (ledger [_]
    (->> (d/q '[:find ?s ?f :where [?e :ledger/seq ?s] [?e :ledger/fact ?f]] (d/db conn))
         (sort-by first)
         (mapv (comp dec* second))))
  (dispatch-history [_]
    (->> (d/q '[:find ?s ?r :where [?e :dispatch/seq ?s] [?e :dispatch/record ?r]] (d/db conn))
         (sort-by first)
         (mapv (comp dec* second))))
  (evidence-history [_]
    (->> (d/q '[:find ?s ?r :where [?e :evidence/seq ?s] [?e :evidence/record ?r]] (d/db conn))
         (sort-by first)
         (mapv (comp dec* second))))
  (maintenance-notice-history [_]
    (->> (d/q '[:find ?s ?r :where [?e :maintenance-notice/seq ?s] [?e :maintenance-notice/record ?r]] (d/db conn))
         (sort-by first)
         (mapv (comp dec* second))))
  (next-dispatch-sequence [_ jurisdiction]
    (or (d/q '[:find ?n . :in $ ?j
              :where [?e :dispatch-sequence/jurisdiction ?j] [?e :dispatch-sequence/next ?n]]
            (d/db conn) jurisdiction)
        0))
  (next-evidence-sequence [_ jurisdiction]
    (or (d/q '[:find ?n . :in $ ?j
              :where [?e :evidence-sequence/jurisdiction ?j] [?e :evidence-sequence/next ?n]]
            (d/db conn) jurisdiction)
        0))
  (next-maintenance-notice-sequence [_ jurisdiction]
    (or (d/q '[:find ?n . :in $ ?j
              :where [?e :maintenance-notice-sequence/jurisdiction ?j] [?e :maintenance-notice-sequence/next ?n]]
            (d/db conn) jurisdiction)
        0))
  (unit-already-dispatched? [s unit-id]
    (boolean (:unit-dispatched? (unit s unit-id))))
  (unit-already-certified? [s unit-id]
    (boolean (:pressure-test-certified? (unit s unit-id))))
  (equipment-asset [_ id]
    (pull->equipment-asset (d/pull (d/db conn) equipment-asset-pull [:equipment-asset/id id])))
  (all-equipment-assets [_]
    (->> (d/q '[:find [?id ...] :where [?e :equipment-asset/id ?id]] (d/db conn))
         (map #(pull->equipment-asset (d/pull (d/db conn) equipment-asset-pull [:equipment-asset/id %])))))
  (equipment-asset-already-registered? [s id]
    (boolean (equipment-asset s id)))
  (commit-record! [s {:keys [effect path value payload]}]
    (case effect
      :unit/upsert
      (d/transact! conn [(unit->tx value)])

      :verification/set
      (d/transact! conn [{:verification/unit-id (first path) :verification/payload (enc payload)}])

      :pressure-test-screen/set
      (d/transact! conn [{:pressure-screen/unit-id (first path) :pressure-screen/payload (enc payload)}])

      :unit/mark-dispatched
      (let [unit-id (first path)
            {:keys [result unit-patch]} (dispatch-unit! s unit-id)
            jurisdiction (:jurisdiction (unit s unit-id))
            next-n (inc (next-dispatch-sequence s jurisdiction))]
        (d/transact! conn
                     [(unit->tx (assoc unit-patch :id unit-id))
                      {:dispatch-sequence/jurisdiction jurisdiction :dispatch-sequence/next next-n}
                      {:dispatch/seq (count (dispatch-history s)) :dispatch/record (enc (get result "record"))}])
        result)

      :unit/mark-certified
      (let [unit-id (first path)
            {:keys [result unit-patch]} (issue-pressure-test-certificate! s unit-id)
            jurisdiction (:jurisdiction (unit s unit-id))
            next-n (inc (next-evidence-sequence s jurisdiction))]
        (d/transact! conn
                     [(unit->tx (assoc unit-patch :id unit-id))
                      {:evidence-sequence/jurisdiction jurisdiction :evidence-sequence/next next-n}
                      {:evidence/seq (count (evidence-history s)) :evidence/record (enc (get result "record"))}])
        result)

      :maintenance-notice/issue
      (let [unit-id (first path)
            {:keys [result]} (issue-maintenance-notice! s unit-id (:dispatch-ref value))
            jurisdiction (:jurisdiction (unit s unit-id))
            next-n (inc (next-maintenance-notice-sequence s jurisdiction))]
        (d/transact! conn
                     [{:maintenance-notice-sequence/jurisdiction jurisdiction :maintenance-notice-sequence/next next-n}
                      {:maintenance-notice/seq (count (maintenance-notice-history s)) :maintenance-notice/record (enc (get result "record"))}])
        result)

      :equipment-asset/register
      (d/transact! conn [value])
      nil)
    s)
  (append-ledger! [s fact]
    (d/transact! conn [{:ledger/seq (count (ledger s)) :ledger/fact (enc fact)}])
    fact)
  (with-units [s units]
    (when (seq units) (d/transact! conn (mapv unit->tx (vals units)))) s))

(defn datomic-store
  "A DatomicStore (langchain.db backend) seeded from `data`
  ({:units ..}); empty when omitted."
  ([] (datomic-store {}))
  ([{:keys [units]}]
   (let [s (->DatomicStore (d/create-conn schema))]
     (with-units s units))))

(defn datomic-seed-db
  "A DatomicStore seeded with the demo unit set -- the Datomic-
  backed analog of `seed-db`, used to prove protocol parity."
  []
  (datomic-store (demo-data)))
