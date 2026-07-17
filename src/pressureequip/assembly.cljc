(ns pressureequip.assembly
  "BOM <-> production-line consistency for `:unit/industrial-refrigeration-
  compressor`'s assembly resources (`resources/pressureequip/*.edn`) --
  mirroring kotoba-lang/kami-app-sarutahiko-factory's split between an I/O
  fixture loader (`kotoba.sarutahiko-factory.fixtures`) and pure
  process-graph consistency checks
  (`kotoba.sarutahiko-factory.construction-order/reveals-resolve?`,
  `kotoba.sarutahiko-factory.prod-order/stations-resolve-cells?`).

  `load-bom` / `load-prod-order` are the only I/O in this ns (`:clj`-only,
  `clojure.java.io/resource` + `clojure.edn/read-string`, same as
  `kotoba.sarutahiko-factory.fixtures`'s rationale for staying `.clj`
  rather than `.cljc` for its loader -- there is no browser-side loader to
  bundle yet). Every other fn here is pure data -- given a `bom` map and a
  `prod-order` map (however they were obtained), they only inspect and
  cross-reference `:part/id` / `:station/parts` strings."
  #?(:clj (:require [clojure.edn :as edn]
                    [clojure.java.io :as io])))

;; ---- I/O: resource loaders (JVM only) --------------------------------------

#?(:clj
   (defn- load-edn [path]
     (if-let [r (io/resource path)]
       (edn/read-string (slurp r))
       (throw (ex-info "missing pressureequip assembly resource" {:path path})))))

#?(:clj
   (defn load-bom
     "The `:bom/*` map for `:unit/industrial-refrigeration-compressor`
     (`resources/pressureequip/compressor-unit-bom.edn`)."
     []
     (load-edn "pressureequip/compressor-unit-bom.edn")))

#?(:clj
   (defn load-prod-order
     "The `:prod-order/*` map for `:unit/industrial-refrigeration-
     compressor` (`resources/pressureequip/compressor-unit-prod-order.edn`)."
     []
     (load-edn "pressureequip/compressor-unit-prod-order.edn")))

;; ---- pure data accessors ---------------------------------------------------

(defn bom-parts [bom] (:bom/parts bom))

(defn part-ids
  "The set of `:part/id`s a BOM declares."
  [bom]
  (into #{} (map :part/id) (bom-parts bom)))

(defn stations [prod-order] (:prod-order/stations prod-order))

;; ---- pure consistency checks ------------------------------------------------

(defn seq-contiguous?
  "True when station `:station/seq` values are exactly `1..n` in order
  (mirrors `kotoba.sarutahiko-factory.prod-order/seq-contiguous?`)."
  [prod-order]
  (= (mapv :station/seq (stations prod-order))
     (vec (range 1 (inc (count (stations prod-order)))))))

(defn unresolved-parts
  "The subset of every station's `:station/parts` ids that aren't present
  in `known-part-ids` (e.g. `(part-ids (load-bom))`). The BOM<->process-
  graph analogue of
  `kotoba.sarutahiko-factory.construction-order/unresolved-reveals`."
  [prod-order known-part-ids]
  (let [known (set known-part-ids)]
    (into #{} (mapcat (fn [s] (remove known (:station/parts s)))) (stations prod-order))))

(defn step-parts-consistent?
  "True when every station's `:station/parts` names a real BOM part id --
  the assembly analogue of
  `kotoba.sarutahiko-factory.construction-order/reveals-resolve?`."
  [prod-order known-part-ids]
  (empty? (unresolved-parts prod-order known-part-ids)))

(defn used-part-ids
  "The set of part ids referenced by at least one station's
  `:station/parts`."
  [prod-order]
  (into #{} (mapcat :station/parts) (stations prod-order)))

(defn unused-bom-parts
  "The subset of a BOM's part ids that NO station's `:station/parts`
  references -- an orphan BOM line (bought/stocked but never installed by
  this process graph)."
  [bom prod-order]
  (remove (used-part-ids prod-order) (part-ids bom)))

(defn bom-parts-all-used?
  "True when every BOM part id is consumed by at least one station."
  [bom prod-order]
  (empty? (unused-bom-parts bom prod-order)))

(defn programme-seconds
  "Total nominal process time: the sum of every station's
  `:station/cycle-s` (mirrors
  `kotoba.sarutahiko-factory.construction-order/programme-days`, seconds
  instead of days since this station schedule is `:station/cycle-s`-keyed
  like kami-app-sarutahiko-factory's own `prod-order.edn`)."
  [prod-order]
  (reduce + 0.0 (map :station/cycle-s (stations prod-order))))

(defn stations-with-op
  "Every station whose `:station/op` equals `op`."
  [prod-order op]
  (filter #(= op (:station/op %)) (stations prod-order)))

(defn evidence-produced
  "The set of non-nil `:station/evidence-produced` values across every
  station -- the assembly-graph side of this repo's evidence cross-
  reference to `pressureequip.facts/catalog`'s `:required-evidence`
  strings (data/comment-level only; see compressor-unit-prod-order.edn's
  `st:pressure-test` comment)."
  [prod-order]
  (into #{} (keep :station/evidence-produced) (stations prod-order)))
