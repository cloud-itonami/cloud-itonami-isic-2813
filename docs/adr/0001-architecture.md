# ADR-0001: Pressure Equipment Advisor ⊣ Pressure Equipment Governor architecture

- Status: Accepted (2026-07-10)
- Repository: `cloud-itonami-isic-2813` (ISIC Rev.5 `2813`)

## Context

Pressure-equipment manufacturing (final assembly, ASME BPVC Section
VIII / PED 2014/68/EU hydrostatic-and-pneumatic pressure-test
acceptance testing, design-rules/conformity marking, pressure-test-
certificate issuance) needs the same governed-actor pattern as the
rest of the cloud-itonami fleet: an untrusted advisor proposes; an
independent governor may HOLD; high-stakes actuation never
auto-commits.

This vertical continues the classic heavy-industry manufacturing
cluster after `cloud-itonami-isic-2410` (basic iron and steel),
`cloud-itonami-isic-2811` (engines and turbines), `cloud-itonami-
isic-2910` (motor vehicles), `cloud-itonami-isic-3011` (ships and
floating structures), `cloud-itonami-isic-2511` (structural metal
products), `cloud-itonami-isic-2822` (metal-forming machinery and
machine tools) and `cloud-itonami-isic-2824` (mining/quarrying/
construction machinery) -- and is another entry in the fleet's
capital-equipment manufacturing sub-cluster alongside 2822/2824 (the
machines/equipment that make or serve other machines), distinct from
the transport-equipment sub-cluster (2811/2910/3011).

## Decision

1. Namespaces live under `pressureequip.*` with the standard
   facts / registry / store / governor / phase / advisor / operation / sim
   shape.
2. Entity is a pressure-equipment **unit** (a pump, compressor or
   valve), not a vehicle, hull block, steel heat or machine tool.
3. Dual actuation on the same entity:
   - `:actuation/dispatch-unit` (robot final-assembly/shipment dispatch draft)
   - `:actuation/issue-pressure-test-certificate` (ASME BPVC/PED hydrostatic/pneumatic pressure-test-certificate draft)
4. Double-actuation guards use dedicated booleans
   (`:unit-dispatched?`, `:pressure-test-certified?`), never a status
   lifecycle (ADR-2607071320 / 6492 lesson).
5. `unit-test-pressure-out-of-range?` continues the fleet's two-sided
   range check family (after testlab / conservation / water /
   steelworks / turbine / automotive / machinetool / heavyequip),
   applied here to a unit's own measured hydrostatic/pneumatic
   acceptance test pressure against its own recorded spec bounds --
   the direct analog of ASME BPVC UG-99's required minimum
   hydrostatic test pressure (>= 1.3x MAWP, temperature-corrected)
   and the code's overstress ceiling.
6. Pressure-test defect unresolved is evaluated unconditionally so
   `:pressure-test/screen` itself can HARD-hold (parksafety
   ADR-2607071922 Decision 5 discipline).
7. Spec-basis catalog seeds JPN (METI/KHK/JIS B 8501·B 8265, 高圧ガス
   保安法) / USA (ASME BPVC Section VIII + ASME B31.3 + API 610/674-
   676) / GBR (HSE PSSR 2000 + BS EN 13445) / DEU (PED 2014/68/EU +
   DIN EN 13445) only. Missing jurisdictions are uncovered, never
   fabricated.

## Consequences

(+) Pressure-equipment manufacturing gains a forkable OSS operating
stack with auditable governor holds.
(+) Reuses langgraph + store dual-backend parity without new physics.
(−) No physical plant digital-twin tick in this repo (follow-up
domain data is out of scope here).
(−) Design-conformity-authority coverage is a starting catalog, not
exhaustive.

## Related

- Superproject fleet ADR for this promotion (pressure-equipment-2813-coverage)
- Sibling architecture: `cloud-itonami-isic-2410` docs/adr/0001,
  `cloud-itonami-isic-2811` docs/adr/0001, `cloud-itonami-isic-2910`
  docs/adr/0001, `cloud-itonami-isic-3011` docs/adr/0001,
  `cloud-itonami-isic-2511` docs/adr/0001, `cloud-itonami-isic-2822`
  docs/adr/0001, `cloud-itonami-isic-2824` docs/adr/0001
