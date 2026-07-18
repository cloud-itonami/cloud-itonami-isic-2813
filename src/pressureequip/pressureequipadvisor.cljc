(ns pressureequip.pressureequipadvisor
  "Pressure Equipment Advisor client -- the *contained intelligence
  node* for the pressure-equipment-manufacturing actor.

  It normalizes unit-intake, drafts a per-jurisdiction design-rules/
  conformity evidence checklist, screens units for an unresolved
  hydrostatic/pneumatic pressure-test-detected defect, drafts the
  unit-dispatch action, and drafts the pressure-test-certificate-
  issuance action. CRITICAL: it is a smart-but-untrusted advisor. It
  returns a *proposal* (with a rationale + the fields it cited), never
  a committed record or a real robot dispatch/pressure-test-
  certificate issuance. Every output is censored downstream by
  `pressureequip.governor` before anything touches the SSoT, and
  `:actuation/dispatch-unit`/`:actuation/issue-pressure-test-
  certificate` proposals NEVER auto-commit at any phase -- see README
  `Actuation`.

  Like every sibling actor's advisor, this is a deterministic mock so
  the actor graph runs offline and the governor contract is exercised
  end-to-end. In production this calls a real LLM (kotoba-llm or
  equivalent) with the same proposal shape.

  Proposal shape (all kinds):
    {:summary    str            ; human-facing draft / finding
     :rationale  str            ; why -- SCANNED by the spec-basis gate
     :cites      [kw|str ..]    ; facts/sources the LLM used -- SCANNED too
     :effect     kw             ; how a commit would mutate the SSoT
     :stake      kw|nil         ; :actuation/dispatch-unit | :actuation/issue-pressure-test-certificate | nil
     :confidence 0..1}"
  (:require #?(:clj  [clojure.edn :as edn]
               :cljs [cljs.reader :as edn])
            [clojure.string :as str]
            [pressureequip.facts :as facts]
            [pressureequip.registry :as registry]
            [pressureequip.store :as store]
            [langchain.model :as model]))

(defn- normalize-intake
  "Directory upsert -- the LLM only normalizes/validates the patch; it
  does not invent the unit, test-pressure figures or jurisdiction.
  High confidence, low stakes."
  [_db {:keys [patch]}]
  {:summary    (str "機体記録更新: " (pr-str (keys patch)))
   :rationale  "入力 patch の正規化のみ。新規事実の生成なし。"
   :cites      (vec (keys patch))
   :effect     :unit/upsert
   :value      patch
   :stake      nil
   :confidence 0.97})

(defn- verify-requirements
  "Per-jurisdiction design-rules/conformity evidence checklist draft.
  `:no-spec?` injects the failure mode we must defend against:
  proposing a checklist for a jurisdiction with NO official spec-basis
  in `pressureequip.facts` -- the Pressure Equipment Governor must
  reject this (never invent a jurisdiction's requirements)."
  [db {:keys [subject no-spec?]}]
  (let [a (store/unit db subject)
        iso3 (if no-spec? "ATL" (:jurisdiction a))
        sb (facts/spec-basis iso3)]
    (if (nil? sb)
      {:summary    (str iso3 " の公式spec-basisが見つかりません")
       :rationale  "pressureequip.facts に未登録の法域。要件を推測で作らない。"
       :cites      []
       :effect     :verification/set
       :value      {:jurisdiction iso3 :checklist [] :spec-basis nil}
       :stake      nil
       :confidence 0.9}
      {:summary    (str iso3 " (" (:owner-authority sb) ") 向け必要書類 "
                        (count (:required-evidence sb)) " 件を提案")
       :rationale  (str "公式ソース: " (:provenance sb) " / 法的根拠: " (:legal-basis sb))
       :cites      [(:legal-basis sb) (:provenance sb)]
       :effect     :verification/set
       :value      {:jurisdiction iso3
                    :checklist (:required-evidence sb)
                    :spec-basis (:provenance sb)
                    :legal-basis (:legal-basis sb)}
       :stake      nil
       :confidence 0.9})))

(defn- screen-pressure-test-defect
  "Hydrostatic/pneumatic pressure-test screening draft.
  `:pressure-test-defect-unresolved?` on the unit record injects the
  failure mode: the Pressure Equipment Governor must HOLD,
  un-overridably, on any unresolved defect."
  [db {:keys [subject]}]
  (let [a (store/unit db subject)]
    (cond
      (nil? a)
      {:summary "対象機体記録が見つかりません" :rationale "no unit record"
       :cites [] :effect :pressure-test-screen/set :value {:unit-id subject :verdict :unknown}
       :stake nil :confidence 0.0}

      (true? (:pressure-test-defect-unresolved? a))
      {:summary    (str (:unit-name a) ": 未解決の耐圧試験欠陥を検出")
       :rationale  "耐圧試験(水圧/気密)スクリーニングが未解決の欠陥(漏れ/変形)を検出。人手確認とホールドが必須。"
       :cites      [:pressure-check]
       :effect     :pressure-test-screen/set
       :value      {:unit-id subject :verdict :unresolved}
       :stake      nil
       :confidence 0.95}

      :else
      {:summary    (str (:unit-name a) ": 未解決の耐圧試験欠陥なし")
       :rationale  "耐圧試験欠陥スクリーニング完了。"
       :cites      [:pressure-check]
       :effect     :pressure-test-screen/set
       :value      {:unit-id subject :verdict :resolved}
       :stake      nil
       :confidence 0.9})))

(defn- propose-unit-dispatch
  "Draft the actual UNIT-DISPATCH action -- dispatching a real robot
  final-assembly/shipment action on a pump/compressor/valve unit.
  ALWAYS `:stake :actuation/dispatch-unit` -- this is a REAL-WORLD
  safety-critical act, never a draft the actor may auto-run. See
  README `Actuation`: no phase ever adds this op to a phase's `:auto`
  set (`pressureequip.phase`); the governor also always escalates on
  `:actuation/dispatch-unit`. Two independent layers agree,
  deliberately."
  [db {:keys [subject]}]
  (let [a (store/unit db subject)]
    {:summary    (str subject " 向け完成機実行提案"
                      (when a (str " (unit=" (:unit-name a) ")")))
     :rationale  (if a
                   (str "test-pressure-actual=" (:test-pressure-actual a)
                        " spec=[" (:test-pressure-min a) "," (:test-pressure-max a) "]")
                   "機体記録が見つかりません")
     :cites      (if a [subject] [])
     :effect     :unit/mark-dispatched
     :value      {:unit-id subject}
     :stake      :actuation/dispatch-unit
     :confidence (if (and a (not (registry/unit-test-pressure-out-of-range? a))) 0.9 0.3)}))

(defn- propose-pressure-test-certificate
  "Draft the actual PRESSURE-TEST-CERTIFICATE action -- issuing a real
  ASME BPVC/PED hydrostatic or pneumatic acceptance-test certificate
  for a unit. ALWAYS `:stake :actuation/issue-pressure-test-
  certificate` -- this is a REAL-WORLD safety-critical act, never a
  draft the actor may auto-run. See README `Actuation`: no phase ever
  adds this op to a phase's `:auto` set (`pressureequip.phase`); the
  governor also always escalates on `:actuation/issue-pressure-test-
  certificate`. Two independent layers agree, deliberately."
  [db {:keys [subject]}]
  (let [a (store/unit db subject)]
    {:summary    (str subject " 向け耐圧証明書発行提案"
                      (when a (str " (unit=" (:unit-name a) ")")))
     :rationale  (if a
                   "jurisdiction-evidence-checklist referenced"
                   "機体記録が見つかりません")
     :cites      (if a [subject] [])
     :effect     :unit/mark-certified
     :value      {:unit-id subject}
     :stake      :actuation/issue-pressure-test-certificate
     :confidence (if a 0.9 0.3)}))

(defn- propose-maintenance-notice
  "Draft a MAINTENANCE/RECALL NOTICE for a unit, referencing that
  unit's OWN prior `:actuation/dispatch-unit` -- the superproject
  `:equipment-asset` shared shape a downstream operator actor (e.g.
  cloud-itonami-jsic-4721, running its own equipment as this actor's
  manufactured units) independently cross-checks (no shared code).
  The advisor reads the unit's OWN recorded `:dispatch-number`
  directly from the store -- it does not invent a dispatch-ref -- and
  `pressureequip.governor/dispatch-ref-unverified-violations`
  INDEPENDENTLY re-verifies this same field before anything commits
  (never trust the proposal's own echo). ALWAYS `:stake
  :issue-maintenance-notice` -- like the two actuation ops, this is
  real-world safety/compliance communication about equipment already
  in the field, never a draft the actor may auto-run."
  [db {:keys [subject]}]
  (let [a (store/unit db subject)
        dispatch-ref (:dispatch-number a)
        dispatched? (boolean (and a (:unit-dispatched? a)))]
    {:summary    (str subject " 向け保守/リコール通知発行提案"
                      (when a (str " (unit=" (:unit-name a) " dispatch-ref=" dispatch-ref ")")))
     :rationale  (if dispatched?
                   (str "unit-dispatched?=true dispatch-number=" dispatch-ref " を参照")
                   "機体記録が見つからないか、まだ完成機実行されていません")
     :cites      (if dispatched? [subject dispatch-ref] [])
     :effect     :maintenance-notice/issue
     :value      {:unit-id subject :dispatch-ref dispatch-ref}
     :stake      :issue-maintenance-notice
     :confidence (if dispatched? 0.9 0.3)}))

(defn- propose-register-equipment-asset
  "Draft a REGISTER-EQUIPMENT-ASSET action -- registering a manufactured
  unit RECEIVED from an upstream manufacturer actor (e.g. cloud-itonami-
  isic-2822, a machine-tool/welding-cell manufacturer supplying THIS
  factory's own production line) as an operated equipment asset, tied
  to one of this factory's own `:station/cell`s (resources/
  pressureequip/compressor-unit-prod-order.edn). The superproject
  `:equipment-asset` shared shape (`:equipment-asset/id`/`:unit-type-
  id`/`:source-actor`/`:dispatch-ref`/`:installed-at-iso`) is the SAME
  wire shape cloud-itonami-jsic-4721 independently registers for units
  THIS actor dispatches to it (`propose-maintenance-notice` above) --
  isic-2813 plays BOTH roles in this fleet (supply side toward
  jsic-4721, receive side here toward isic-2822). `subject` doubles as
  the new asset's `:equipment-asset/id`; the advisor only echoes/
  normalizes the request's own `:equipment-asset/*` fields, it does
  not invent a source-actor, dispatch-ref or unit-type-id.
  `pressureequip.governor` INDEPENDENTLY re-verifies required-field
  presence and the double-registration guard before anything commits."
  [_db {:keys [subject] :as request}]
  (let [ea (-> (select-keys request [:equipment-asset/unit-type-id
                                     :equipment-asset/source-actor
                                     :equipment-asset/dispatch-ref
                                     :equipment-asset/installed-at-iso
                                     :equipment-asset/station-cell])
               (assoc :equipment-asset/id subject))
        present? (every? some? ((juxt :equipment-asset/id :equipment-asset/unit-type-id
                                      :equipment-asset/source-actor :equipment-asset/dispatch-ref)
                                ea))]
    {:summary    (str subject " 設備資産登録提案"
                      (when-let [src (:equipment-asset/source-actor ea)] (str " (source=" src ")")))
     :rationale  (if present?
                   (str "供給元 " (:equipment-asset/source-actor ea) " のdispatch-ref "
                        (:equipment-asset/dispatch-ref ea) " を参照して資産登録")
                   "必須フィールド(:equipment-asset/id・:unit-type-id・:source-actor・:dispatch-ref)が不足")
     :cites      (if present? [subject (:equipment-asset/source-actor ea) (:equipment-asset/dispatch-ref ea)] [])
     :effect     :equipment-asset/register
     :value      ea
     :stake      nil
     :confidence (if present? 0.9 0.3)}))

(defn- propose-register-part-receipt
  "Draft a REGISTER-PART-RECEIPT action -- registering the receipt of
  a BOM consumable/component part (resources/pressureequip/
  compressor-unit-bom.edn `:bom/parts`, e.g. `part:electric-motor`)
  FROM an upstream component-supplier actor (e.g. cloud-itonami-
  isic-2710, an electric-motor/generator/transformer manufacturer).
  DISTINCT from `propose-register-equipment-asset` above: an
  equipment asset is FIXED CAPITAL this factory OPERATES (a machine
  tool/welding cell); a part receipt is a CONSUMABLE/COMPONENT this
  factory CONSUMES into its own BOM. The superproject `:handoff`
  shared shape (`{:handoff/id :handoff/source-actor :handoff/batch-id
  :handoff/product-type-id :handoff/quantity-kg :handoff/dispatched-
  at-iso}`, ADR-2607177600) is REUSED AS-IS, never a new shape -- see
  superproject ADR-2800000500. `:handoff` is entirely OPTIONAL on a
  part receipt: this actor accepts parts from any supplier, tracked
  or not; the advisor only echoes/normalizes the request's own
  `:part-receipt/*`/`:handoff` fields, it does not invent a
  source-actor or batch-id. `pressureequip.governor` INDEPENDENTLY
  re-verifies required-field presence (both the receipt's own and,
  when present, the handoff's own) and the double-registration guard
  before anything commits."
  [_db {:keys [subject] :as request}]
  (let [part-id (:part-receipt/part-id request)
        qty (:part-receipt/qty request)
        handoff (:handoff request)
        pr (cond-> {:part-receipt/id subject :part-receipt/part-id part-id}
             (some? qty) (assoc :part-receipt/qty qty)
             handoff (assoc :handoff handoff))
        required-present? (every? some? [subject part-id])]
    {:summary    (str subject " 部品受入登録提案 (part=" part-id ")"
                      (when-let [src (:handoff/source-actor handoff)] (str " supplier=" src)))
     :rationale  (if required-present?
                   (str "部品 " part-id " の受入登録"
                        (if handoff
                          (str "、handoff参照あり(batch-id=" (:handoff/batch-id handoff) ")")
                          "、handoff参照なし(トレーサビリティ情報なしでの受入)"))
                   "必須フィールド(:part-receipt/id・:part-receipt/part-id)が不足")
     :cites      (if required-present? (cond-> [subject part-id] (:handoff/source-actor handoff) (conj (:handoff/source-actor handoff))) [])
     :effect     :part-receipt/register
     :value      pr
     :stake      nil
     :confidence (if required-present? 0.9 0.3)}))

(defn infer
  "Route a request to the right proposal generator.
  request: {:op kw :subject id ...op-specific...}"
  [db {:keys [op] :as request}]
  (case op
    :unit/intake                                  (normalize-intake db request)
    :design-rules/verify                          (verify-requirements db request)
    :pressure-test/screen                         (screen-pressure-test-defect db request)
    :actuation/dispatch-unit                      (propose-unit-dispatch db request)
    :actuation/issue-pressure-test-certificate     (propose-pressure-test-certificate db request)
    :issue-maintenance-notice                     (propose-maintenance-notice db request)
    :register-equipment-asset                     (propose-register-equipment-asset db request)
    :register-part-receipt                        (propose-register-part-receipt db request)
    {:summary "未対応の操作" :rationale (str op) :cites []
     :effect :noop :stake nil :confidence 0.0}))

;; ----------------------------- Advisor protocol -----------------------------

(defprotocol Advisor
  (-advise [advisor store request] "store + request -> proposal map"))

(defn mock-advisor
  "The deterministic advisor (the `infer` logic above). Default everywhere."
  [] (reify Advisor (-advise [_ st req] (infer st req))))

(def ^:private system-prompt
  (str "あなたは圧力機器(ポンプ・圧縮機・弁)製造工場の完成機実行・耐圧証明書発行"
       "エージェントの助言者です。与えられた事実のみに基づき、提案を1つだけEDN"
       "マップで返します。説明や前置きは一切書かず、EDNだけを出力します。\n"
       "キー: :summary(人向けドラフト) :rationale(根拠/必ず事実から) "
       ":cites(使った事実キーのベクタ) "
       ":effect(:unit/upsert|:verification/set|:pressure-test-screen/set|"
       ":unit/mark-dispatched|:unit/mark-certified|:maintenance-notice/issue|"
       ":equipment-asset/register|:part-receipt/register) "
       ":stake(:actuation/dispatch-unit か :actuation/issue-pressure-test-certificate か "
       ":issue-maintenance-notice か nil) :confidence(0..1)。\n"
       "重要: 登録されていない法域の要件を絶対に創作してはいけません。"
       "spec-basisが無い場合は :cites を空にし confidence を上げないこと。"))

(defn- facts-for [st {:keys [op subject]}]
  (case op
    :design-rules/verify                          {:unit (store/unit st subject)}
    :pressure-test/screen                         {:unit (store/unit st subject)}
    :actuation/dispatch-unit                      {:unit (store/unit st subject)}
    :actuation/issue-pressure-test-certificate     {:unit (store/unit st subject)}
    :issue-maintenance-notice                     {:unit (store/unit st subject)}
    :register-equipment-asset                     {:equipment-asset (store/equipment-asset st subject)}
    :register-part-receipt                        {:part-receipt (store/part-receipt st subject)}
    {:unit (store/unit st subject)}))

(defn- parse-proposal
  "Parse the model's EDN proposal defensively. Any parse/shape failure
  yields a safe low-confidence noop so the Pressure Equipment
  Governor escalates/holds -- an LLM hiccup can never auto-dispatch a
  unit action or auto-issue a pressure-test certificate."
  [content]
  (let [p (try (edn/read-string (str/trim (str content)))
               (catch #?(:clj Exception :cljs :default) _ nil))]
    (if (map? p)
      (-> p
          (update :cites #(vec (or % [])))
          (update :confidence #(if (number? %) (double %) 0.0))
          (update :effect #(or % :noop)))
      {:summary "LLM応答を解釈できませんでした" :rationale (str content)
       :cites [] :effect :noop :stake nil :confidence 0.0})))

(defn llm-advisor
  "An advisor backed by a `langchain.model/ChatModel` (real inference)."
  ([chat-model] (llm-advisor chat-model {}))
  ([chat-model gen-opts]
   (reify Advisor
     (-advise [_ st req]
       (let [msgs [{:role :system :content system-prompt}
                   {:role :user :content (str "操作: " (:op req)
                                              "\n対象: " (:subject req)
                                              "\n事実: " (pr-str (facts-for st req)))}]
             resp (model/-generate chat-model msgs gen-opts)]
         (parse-proposal (:content resp)))))))

(defn trace
  "Decision-grounded audit record -- persisted to the :audit channel."
  [request proposal]
  {:t          :pressureequipadvisor-proposal
   :op         (:op request)
   :subject    (:subject request)
   :summary    (:summary proposal)
   :rationale  (:rationale proposal)
   :cites      (:cites proposal)
   :confidence (:confidence proposal)})
