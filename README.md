# cloud-itonami-isic-2813

Open Business Blueprint for **ISIC Rev.5 2813**: manufacture of other
pumps, compressors, taps and valves -- unit intake, ASME BPVC/PED
hydrostatic-and-pneumatic pressure-test acceptance testing and
pressure-test-certificate issuance for a community pressure-equipment
plant.

This repository publishes a pressure-equipment-manufacturing actor --
unit intake, per-jurisdiction design-rules/conformity verification,
hydrostatic/pneumatic pressure-test screening, robot unit-dispatch
and pressure-test-certificate finalization -- as an OSS business that
any qualified pump, compressor or valve plant can fork, deploy, run,
improve and sell, so a plant keeps its own construction and
pressure-test history instead of renting a closed MES / quality
SaaS.

Built on this workspace's
[`langgraph`](https://github.com/kotoba-lang/langgraph)
StateGraph runtime (portable `.cljc`, supervised superstep loop,
interrupts, Datomic/in-mem checkpoints) -- the same actor pattern as
every prior actor in this fleet -- here it is **Pressure Equipment
Advisor ⊣ Pressure Equipment Governor**.

## Scope note: manufacturing capital equipment, not the process plant that uses it

This repository is scoped to **building** pumps, compressors, taps
and valves themselves (centrifugal/reciprocating pumps, screw/
reciprocating compressors, gate/ball/safety valves -- design-rules
verification, pressure testing, pressure-test-certificate evidence).
It is not a process/power/oil-gas vertical that merely *operates*
pressure equipment to run other plant. Distinct from:

- `cloud-itonami-isic-2410` — basic iron and steel **manufacturing**
- `cloud-itonami-isic-2511` — structural metal products **manufacturing**
- `cloud-itonami-isic-2811` — engines and turbines **manufacturing**
- `cloud-itonami-isic-2822` — metal-forming machinery and machine tools **manufacturing**
- `cloud-itonami-isic-2824` — mining/quarrying/construction machinery **manufacturing**
- `cloud-itonami-isic-2910` — motor vehicles **manufacturing**
- `cloud-itonami-isic-3011` — ships and floating structures **manufacturing**

## Robotics premise

All cloud-itonami verticals are designed on the premise that a **robot
performs the physical domain work**. Here robots (final assembly,
fit-up, hydrostatic/pneumatic pressure-test rig operation) operate
under an actor that proposes actions and an independent **Pressure
Equipment Governor** that gates them. The governor never issues a
pressure-test certificate itself; `:high`/`:safety-critical` actions
(`:actuation/dispatch-unit`, `:actuation/issue-pressure-test-
certificate`) require human sign-off.

## Core contract

```text
unit intake + design-rules verify + pressure-test screen
  -> Pressure Equipment Advisor proposal
  -> Pressure Equipment Governor (HARD holds un-overridable)
  -> phase gate (actuation always escalates)
  -> human approval for high stakes
  -> append-only ledger + draft records
```

## Actuation honesty

Dispatching a final-assembly robot action and issuing a pressure-test
certificate produce **unsigned draft records and ledger facts only**.
This actor does not talk to real plant control systems or
conformity-marking portals. Signature and hardware dispatch are the
pressure-equipment plant's own acts.

## Ops

| Op | Effect |
|---|---|
| `:unit/intake` | normalize unit directory patch (phase 3 may auto-commit when clean) |
| `:design-rules/verify` | per-jurisdiction design/conformity evidence checklist (always human) |
| `:pressure-test/screen` | ASME BPVC/PED hydrostatic/pneumatic pressure-test screen (HARD hold if unresolved) |
| `:actuation/dispatch-unit` | draft unit-dispatch record (always human) |
| `:actuation/issue-pressure-test-certificate` | draft pressure-test-certificate record (always human) |
| `:issue-maintenance-notice` | draft maintenance/recall-notice record referencing a prior dispatch (always human; independently verified against dispatch history) |
| `:discover/tsukuru-factory-candidates` | READ-ONLY candidate-discovery query against the REAL `orgs/etzhayyim/com-etzhayyim-tsukuru` factory registry (auto-commits when governor-clean; never writes to this actor's own SSoT beyond the audit ledger) |

## Tsukuru factory-discovery bridge (read-only, by design)

`pressureequip.tsukuru-bridge` + `pressureequip.tsukuru-discovery` are
this actor's **first bridge to a REAL, independently operated
external system** (`orgs/etzhayyim/com-etzhayyim-tsukuru`, a real
B2B factory-direct-ordering platform) rather than a fictional sibling
cloud-itonami actor. It is built on this workspace's shared
[`kotoba.lang.atproto-client`](https://github.com/kotoba-lang/atproto-client)
and exposes exactly ONE query: `:discover/tsukuru-factory-candidates`
(ISIC code + capability -> matching `com.etzhayyim.tsukuru.factory`
records).

This actor will **never** implement a real `production-order`/
`progress`/`quality`/`settlement` op toward tsukuru. tsukuru's own
`production-order` lexicon requires `buyerDid` to be a REAL etzhayyim
member (active Adherent SBT, purchasing principal per its own Gate
G14) with a member DID-signed consent before order capture (Gate G1)
-- `cloud-itonami-isic-2813` is a FICTIONAL, governor-gated Open
Business Blueprint actor and structurally cannot be one. Automating
an order/payment flow on tsukuru's behalf would impersonate a
purchasing principal that does not exist. The closed allowlist of
exactly this one op **is** the safety boundary, independently
enforced in code by `pressureequip.governor/tsukuru-query-contains-
order-fields-violations` (a HARD, un-overridable hold if a
production-order/settlement/buyer field ever appears in a discovery
request or proposal) -- see that check's docstring and the
superproject ADR for the full rationale.

`io.github.kotoba-lang/atproto-client` is an opt-in dependency
(`deps.edn`'s `:tsukuru-bridge` alias, mirroring `:visualize`'s
isolation) -- a plain `clojure -M:test`/`clojure -M:dev:test` never
needs an AT-Proto client on the classpath. Bridge-level tests (mock
`IHttp`, never the real `tsukr8u0.etzhayyim.com`) run with:

```bash
clojure -M:tsukuru-bridge
```

## Social / regulatory hand-off

```clojure
(require '[pressureequip.store :as store]
         '[pressureequip.export :as export])

(def db (store/seed-db))
(export/audit-package db)           ;; EDN maps for conformity/regulator hand-off
(export/package->csv-bundle db)     ;; CSV bundle (units/ledger/dispatches/pressure-test-certificates)
```

Operator console (static sample): `docs/samples/operator-console.html`.

## Develop

```bash
clojure -M:dev:test
clojure -M:lint
clojure -M:dev:run
```

## License

AGPL-3.0-or-later — see `LICENSE`.

## Operator console (Pages)

After enabling GitHub Pages (Settings → Pages → GitHub Actions), the
static console is at:

https://cloud-itonami.github.io/cloud-itonami-isic-2813/

Local: open `docs/index.html` or `docs/samples/operator-console.html`.

## Export audit package (CLI)

```bash
clojure -M:dev:export
# or: clojure -M:dev:export /tmp/audit-2813
```

Writes CSV files under `out/audit-package/` (or the given directory).
