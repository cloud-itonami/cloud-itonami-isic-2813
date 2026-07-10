# Operator Guide

## First Deployment
1. Register pressure-equipment engineers, plants, units, personnel and robots.
2. Import historical unit / pressure-test / design-rules records.
3. Run read-only validation and robot mission dry-runs.
4. Configure design-rules evidence checklists and human sign-off paths.
5. Publish a dry-run audit export.

## Minimum Production Controls
- governor gate on every robot action before dispatch
- human sign-off for `:high`/`:safety-critical` robot actions (e.g. final assembly on safety-critical units, pressure-test-certificate issuance)
- audit export for every dispatch, sign-off and disclosure
- backup manual process

## Certification
Certified operators must prove robot-safety integrity, evidence-backed
records and human review for safety-affecting actions.

## Operating states
intake : design-rules-verify : pressure-test-screen : approve : dispatch-unit : issue-pressure-test-certificate : audit

## Audit export (social operation)

After a production session, export the append-only package for
conformity inspectors or internal compliance:

```clojure
(require '[pressureequip.store :as store]
         '[pressureequip.export :as export])
(export/audit-package store)        ; EDN maps
(export/package->csv-bundle store)  ; CSV files as string map
```

Drafts remain **unsigned** — signing and submission to a conformity-
marking authority are the pressure-equipment manufacturer's own acts
(see README Actuation honesty).

Static UI sample: `docs/samples/operator-console.html`.
