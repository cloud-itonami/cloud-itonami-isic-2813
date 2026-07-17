(ns pressureequip.scene
  "Parametric 3D scene builder for `pressureequip.facts/unit-types` entries,
  producing data in the kami.webgpu.ir v1 render-IR shape (kotoba-lang/webgpu
  `kami.webgpu.ir`, that ns's docstring is the canonical contract):

    {:globals   {:sky {:horizon […] :sun-dir […] :sun […]} :eye […] :target […]}
     :instances [{:pos […] :color […] :size […] :yaw θ :geo (:box|:cylinder)
                  :metallic m :roughness r :emissive e} …]}

  This ns is deliberately dependency-free (no `:require` beyond
  `clojure.core`) so it stays on the always-on `src` classpath without
  pulling kami-engine's rendering stack into this repo's governance/
  actuation dependency graph. `deps.edn`'s `:visualize` alias is the ONLY
  place `io.github.kotoba-lang/webgpu` is declared — `test-visualize/
  pressureequip/scene_test.cljc` is what actually `:require`s
  `kami.webgpu.ir` / `kami.webgpu.geometry` and exercises this ns's output
  against the real API (`ir/valid?`, `geom/box`, `geom/cylinder`).

  HONESTY / SCOPE NOTE: every dimension below is an ILLUSTRATIVE parametric
  primitive derived from `:cooling-capacity-kw` / `:power-requirement-kw` by
  the simple linear formulas in `unit->dimensions`, chosen only to look like
  a plausible packaged refrigeration-compressor unit at this capacity class.
  This is NOT a manufacturer spec sheet, NOT a measured/sourced value, and
  NOT a CAD-fidelity model — it is a box + cylinder + disc composition, per
  ADR-2800000000-follow-on scope (parametric primitives only; CAD-level
  geometry and CFD are explicitly out of scope)."
  )

;; ---- palette --------------------------------------------------------------
;; A small, identification-purpose palette — not an attempt to encode every
;; nuance of real equipment finishes.
(def housing-color
  "Light industrial casing grey, in the general family of RAL 7035 (a very
  common powder-coat for packaged HVAC/refrigeration sheet-metal enclosures)."
  [0.72 0.73 0.75])

(def compressor-color
  "Dark grey/near-black — screw/reciprocating compressor blocks are typically
  a dark cast-iron or dark-painted finish, vibration-isolated inside the
  housing."
  [0.18 0.19 0.22])

(def fan-color
  "Pale green. Refrigerant cylinder/label colour charts (as published by
  several refrigerant manufacturers, e.g. Chemours/Honeywell product
  literature) commonly associate R-448A/R-449A-class low-GWP HFO/HFC blends
  with a pale-green cylinder band — this is a widely-cited INDUSTRY
  CONVENTION, not a single unified regulatory standard, so it is used here
  only as a simple, defensible identification colour for the condenser-fan
  discs (the component most directly tied to `:refrigerant` in the scene)."
  [0.55 0.80 0.55])

(def panel-color
  "Near-black control-panel enclosure — typical for NEMA/IP-rated electrical
  enclosures."
  [0.15 0.16 0.18])

;; ---- parametric dimensions --------------------------------------------------

(defn unit->dimensions
  "unit (a `pressureequip.facts/unit-types` value) -> a map of illustrative
  component dimensions in metres, derived from `:cooling-capacity-kw` /
  `:power-requirement-kw` by simple proportional formulas. See ns docstring —
  these are placeholders sized to look plausible, not vendor data."
  [{:keys [cooling-capacity-kw power-requirement-kw]
    :or {cooling-capacity-kw 0.0 power-requirement-kw 0.0}}]
  (let [housing-length (+ 2.5 (/ cooling-capacity-kw 100.0))   ;; 350 kW -> 6.0 m
        housing-width  (+ 1.2 (/ cooling-capacity-kw 250.0))   ;; 350 kW -> 2.6 m
        housing-height (+ 1.6 (/ power-requirement-kw 120.0))  ;; 90 kW  -> 2.35 m
        compressor-r   (max 0.28 (* 0.10 (/ power-requirement-kw 30.0)))
        compressor-h   (* 0.62 housing-length)
        fan-r          (max 0.35 (* 0.20 housing-width))
        fan-h          0.14
        panel-w 0.7 panel-h (* 0.55 housing-height) panel-d 0.35]
    {:housing [housing-length housing-height housing-width]
     :compressor-r compressor-r :compressor-h compressor-h
     :fan-r fan-r :fan-h fan-h
     :panel [panel-w panel-h panel-d]}))

;; ---- instance constructors (mirror kami.webgpu.ir/instance's map shape) ----

(defn- box-instance
  "An instance map for a `:geo :box` primitive — same field shape
  `kami.webgpu.ir/instance` returns, plus `:geo` (kami.webgpu.ir/default-
  geometry's `:box` key)."
  [color size pos & {:keys [yaw metallic roughness emissive]
                      :or {yaw 0 metallic 0.2 roughness 0.6 emissive 0.0}}]
  {:geo :box :pos pos :color color :size size :yaw yaw
   :metallic metallic :roughness roughness :emissive emissive})

(defn- cylinder-instance
  "An instance map for a `:geo :cylinder` primitive. `:size` follows
  `kami.webgpu.ir/instance-size`'s `[w h d]` convention with the footprint
  diameter in both `w`/`d` (a cylinder's plan-view bounding box is square)."
  [color r h pos & {:keys [yaw metallic roughness emissive]
                     :or {yaw 0 metallic 0.3 roughness 0.5 emissive 0.0}}]
  {:geo :cylinder :pos pos :color color :size [(* 2.0 r) h (* 2.0 r)] :yaw yaw
   :metallic metallic :roughness roughness :emissive emissive})

;; ---- the public scene builder ----------------------------------------------

(defn unit->render-ir
  "unit (e.g. `(pressureequip.facts/unit-type-by-id
  :unit/industrial-refrigeration-compressor)`) -> a kami.webgpu.ir v1
  render-IR map: a boxy housing (casing) + a cylindrical compressor body,
  mounted inside/against the housing, + two disc-shaped condenser fans
  (short wide cylinders) on top + a small control-panel box on the front
  face. Pure function, ground contact at y=0. See ns docstring for the
  fidelity/scope disclaimer."
  [unit]
  (let [{:keys [housing compressor-r compressor-h fan-r fan-h panel]}
        (unit->dimensions unit)
        [housing-length housing-height housing-width] housing
        [panel-w _panel-h panel-d] panel
        housing-y     (/ housing-height 2.0)
        compressor-y  (/ compressor-h 2.0)
        compressor-x  (- (/ housing-length 2.0) compressor-r 0.3)
        fan-y         (+ housing-height (/ fan-h 2.0))
        fan-offset-x  (/ housing-length 4.0)
        panel-x       (- (/ housing-length 2.0) (/ panel-w 2.0) 0.15)
        panel-z       (+ (/ housing-width 2.0) (/ panel-d 2.0) 0.02)]
    {:globals
     {:sky {:horizon [0.55 0.62 0.70] :sun-dir [0.4 0.8 0.35] :sun [1.0 0.98 0.9]}
      :eye [(+ housing-length 2.5) (+ housing-height 2.0) (+ housing-width 3.0)]
      :target [0 housing-y 0]}
     :instances
     [(box-instance housing-color housing [0 housing-y 0])
      (cylinder-instance compressor-color compressor-r compressor-h
                          [compressor-x compressor-y 0]
                          :metallic 0.35 :roughness 0.45)
      (cylinder-instance fan-color fan-r fan-h
                          [(- fan-offset-x) fan-y 0]
                          :metallic 0.15 :roughness 0.35 :emissive 0.05)
      (cylinder-instance fan-color fan-r fan-h
                          [fan-offset-x fan-y 0]
                          :metallic 0.15 :roughness 0.35 :emissive 0.05)
      (box-instance panel-color panel [panel-x housing-y panel-z]
                    :metallic 0.1 :roughness 0.7)]}))
