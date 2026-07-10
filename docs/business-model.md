# Business Model: Manufacture of Other Pumps, Compressors, Taps and Valves

## Classification
- Repository: `cloud-itonami-isic-2813`
- ISIC Rev.5: `2813` — manufacture of other pumps, compressors, taps and valves — unit fabrication, ASME BPVC/PED hydrostatic-pneumatic pressure testing and pressure-test-certificate evidence
- Social impact: industrial-safety, supply-resilience, industrial-jobs

## Customer
- independent pump, compressor and valve manufacturers needing auditable design-rules and production records
- contract plants producing casings, impellers, cylinders and trim for multiple OEM pressure-equipment brands
- plant operators needing verifiable build and pressure-test history for produced pump/compressor/valve units
- market regulators (notified bodies under PED/CE marking, ASME authorized inspectors, HSE-adjacent competent persons) needing verifiable design-conformity and pressure-test evidence
- process/power/oil-gas EPC contractors that cannot accept closed, unauditable manufacturing-execution platforms

## Offer
- design-rules and jurisdiction-scope version management
- robotics-assisted final assembly, fit-up and hydrostatic/pneumatic pressure-test rig operation records
- unit hydrostatic/pneumatic acceptance-test-pressure chain-of-custody history
- pressure-test-certificate drafts and disclosure records
- role-based access and immutable audit ledger
- CSV/EDN audit package export for inspectors

## Revenue
- self-host setup fee
- managed hosting subscription per plant / production line
- support retainer with SLA
- final-assembly/pressure-test robot integration and maintenance

## Trust Controls
- out-of-spec units are blocked; a pressure-test certificate is mandatory for release paths; unit history is immutable
- a robot action the governor refuses is never dispatched to hardware
- every dispatch, hold, approval and disclosure path is auditable
- sensitive design and production data stays outside Git
- a fabricated design-rules citation, incomplete evidence, an
  out-of-window unit test pressure, or an unresolved pressure-test
  defect -- each forces a hold, not an override
- pressure-test-certificate issuance is logged and escalated, and
  cannot be finalized twice for the same unit
