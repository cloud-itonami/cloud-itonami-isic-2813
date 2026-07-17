(ns pressureequip.facts
  "Per-jurisdiction pressure-equipment design/pressure-test-conformity
  catalog -- the G2-style spec-basis table the Pressure Equipment
  Governor checks every `:design-rules/verify` proposal against.

  Coverage is reported HONESTLY: a jurisdiction not in this table has
  NO spec-basis. Seed values cite official pressure-equipment safety /
  conformity authorities: ASME BPVC Section VIII (pressure vessels) +
  ASME B31.3 (process piping) + API 610/674/675/676 (pumps) for USA,
  the EU Pressure Equipment Directive 2014/68/EU (PED) + EN 13445
  (unfired pressure vessels) for DEU, JIS B 8501/8265 + the High
  Pressure Gas Safety Act (高圧ガス保安法) for JPN, and PSSR 2000 + BS
  EN 13445 (UKCA-adopted) for GBR -- this is a starting catalog, not a
  survey of every market.")

(def catalog
  {"JPN" {:name "Japan"
          :owner-authority "経済産業省 (METI) / 高圧ガス保安協会 (KHK) / 日本産業規格 (JIS)"
          :legal-basis "高圧ガス保安法 / 労働安全衛生法 (ボイラー及び圧力容器安全規則) / JIS B 8501・B 8265 (耐圧試験、ASME BPVC/EN 13445 参考整合)"
          :national-spec "圧力容器・ポンプ・圧縮機・弁の耐圧試験(水圧・気密)および設計適合要件"
          :provenance "https://www.meti.go.jp/"
          :required-evidence ["耐圧試験報告書 (hydrostatic-pneumatic-pressure-test-report)"
                              "材料証明記録 (material-certification-record)"
                              "溶接施工記録 (weld-procedure-qualification-record)"
                              "設計計算適合記録 (design-calculation-conformity-record)"]}
   "USA" {:name "United States"
          :owner-authority "ASME (Boiler and Pressure Vessel Committee) / API"
          :legal-basis "ASME BPVC Section VIII Div. 1 (UG-99 hydrostatic/pneumatic test) / ASME B31.3 (Process Piping) / API 610 (centrifugal pumps) / API 674·675·676 (reciprocating/metering/rotary pumps)"
          :national-spec "US pressure-vessel, pump, compressor and valve design, fabrication and pressure-test acceptance requirements"
          :provenance "https://www.asme.org/codes-standards/bpvc-standards"
          :required-evidence ["hydrostatic-pneumatic-pressure-test-report"
                              "material-certification-record"
                              "weld-procedure-qualification-record"
                              "design-calculation-conformity-record"]}
   "GBR" {:name "United Kingdom"
          :owner-authority "HSE / UK pressure-systems framework"
          :legal-basis "Pressure Systems Safety Regulations 2000 (PSSR) / BS EN 13445 (unfired pressure vessels, UKCA-adopted, reference)"
          :national-spec "UK pressure-equipment written-scheme-of-examination and pressure-test conformity requirements"
          :provenance "https://www.hse.gov.uk/pressure-systems/"
          :required-evidence ["hydrostatic-pneumatic-pressure-test-report"
                              "material-certification-record"
                              "weld-procedure-qualification-record"
                              "design-calculation-conformity-record"]}
   "DEU" {:name "Germany"
          :owner-authority "notifizierte Stelle (CE-Kennzeichnung) / DIN / EU-Druckgeräterichtlinie-Kontext"
          :legal-basis "Druckgeräterichtlinie 2014/68/EU (PED) / DIN EN 13445 (unbefeuerte Druckbehälter, Referenz)"
          :national-spec "DE/EU Druckgeräte-Konformitätsbewertung und Druckprüfungsanforderungen"
          :provenance "https://www.din.de/"
          :required-evidence ["Druckprüfbericht (hydrostatic-pneumatic-pressure-test-report)"
                              "Werkstoffzertifikat (material-certification-record)"
                              "Schweißverfahrensprüfung (weld-procedure-qualification-record)"
                              "Festigkeitsberechnungsnachweis (design-calculation-conformity-record)"]}})

(defn spec-basis [iso3] (get catalog iso3))

(def unit-types
  "Catalog of concrete manufactured UNIT MODELS this actor's units can
  declare a `:unit-type-id` reference to (a SEPARATE catalog from
  `catalog` above, which is per-JURISDICTION regulatory evidence, not
  per-PRODUCT spec data -- this catalog does not replace or alter
  `catalog`).

  Two classification fields on each entry come from EXTERNAL,
  independently-verifiable authorities and are reported HONESTLY per
  this fleet's anti-fabrication discipline (see ns docstring / ADR on
  UNSPSC/GTIN linkage in the superproject's `90-docs/adr/`):

    `:unspsc-code` -- an 8-digit UNSPSC (United Nations Standard
    Products and Services Code) COMMODITY code. UNSPSC segment 40
    ('Distribution and Conditioning Systems and Equipment and
    Components') / family 40100000 ('Heating and ventilation and air
    circulation') / class 40101700 ('Cooling equipment and parts and
    accessories') is confirmed via independent public commodity-code
    references (e.g. usa.databasesets.com, govtribe.com category
    listings). Within that class, UNSPSC's own dedicated 'Compressors'
    commodity family lives in a DIFFERENT class (401516) -- the closest
    real UNSPSC commodity for a packaged compressor+condenser
    refrigeration assembly under class 401017 is `40101704`
    ('Condensing units', confirmed via the same sources plus industry
    usage, e.g. Copeland/Heatcraft/York cataloguing compressor+condenser
    assemblies for refrigeration exactly as 'condensing units'). This
    namespace does NOT fabricate a more specific 8-digit code UNSPSC
    itself does not publish.

    `:gtin` -- a GTIN (Global Trade Item Number) is NOT a classification
    taxonomy code at all -- it is an identifier GS1 issues per REGISTERED
    PHYSICAL PRODUCT, only after a real company enrolls with GS1 and
    assigns it (see `cloud-itonami-gtin-issuance`'s own README /
    superproject ADR-2607031800). This catalog is a units-catalog seed,
    not a GS1 registration record, so every `:gtin` value here is a
    SYNTACTICALLY VALID but NEVER-ISSUED placeholder: built on GS1's own
    officially-documented 'Restricted Circulation Number' (RCN) prefix
    range '020'-'029' (GS1 GSCN-23-006-RCN / gs1.org 'GS1 Company
    Prefix' docs) -- a prefix range GS1 itself reserves for
    company-internal/restricted use, i.e. explicitly NOT a
    globally-unique retail identifier -- with a correctly computed
    Modulo-10 GTIN-13 check digit (verified against the standard EAN-13
    worked example 400638133393->1). The sibling key `:gtin/status
    :unissued-blueprint-placeholder` makes the non-issuance explicit and
    machine-checkable; treat `:gtin` here as an EXAMPLE VALUE ONLY,
    never as a real, GS1-issued identifier for an actual unit."
  {:unit/industrial-refrigeration-compressor
   {:id :unit/industrial-refrigeration-compressor
    :name "産業用冷凍・冷蔵倉庫向けコンプレッサーユニット"
    :refrigerant "R-448A"
    :cooling-capacity-kw 350.0
    :power-requirement-kw 90.0
    :unspsc-code "40101704"
    :gtin "0212813000010"
    :gtin/status :unissued-blueprint-placeholder}})

(defn unit-type-by-id [id]
  (get unit-types id))

(defn coverage
  ([] (coverage (keys catalog)))
  ([iso3s]
   (let [have (filter catalog iso3s)
         missing (remove catalog iso3s)]
     {:requested (count iso3s)
      :covered (count have)
      :covered-jurisdictions (vec (sort have))
      :missing-jurisdictions (vec (sort missing))
      :note (str "cloud-itonami-isic-2813 R0: " (count catalog)
                 " jurisdictions seeded. Extend `pressureequip.facts/catalog`, "
                 "never fabricate a jurisdiction's requirements.")})))

(defn required-evidence-satisfied?
  [iso3 submitted]
  (when-let [{:keys [required-evidence]} (spec-basis iso3)]
    (let [need (count required-evidence)
          have (count (filter (set submitted) required-evidence))]
      (= need have))))

(defn evidence-checklist [iso3]
  (:required-evidence (spec-basis iso3) []))
