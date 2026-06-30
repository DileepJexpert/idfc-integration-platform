# Charter v1.0 — tiered build plan (engine + Designer)

Companion to `JOURNEY_DAG_CHARTER.md`. Maps every charter element to **current
code** and the **concrete changes** per tier, so the build is incremental and the
green build is never broken for long. Two consumers, one schema (§7).

## 0. Where we are vs. the charter (delta)

| Charter element | Today (`origination-journey` + `journey-dag-designer`) | Action |
|---|---|---|
| Node types: task, branch, parallel, join, wait, timer, human, foreach, subjourney, terminal | task, branch, terminal; `join` is a `joinOn` **field** (allOf only); parallel is implicit (task with >1 `next`) | add 6 node types as first-class |
| Common: `condition`, `onFailure`, `policies` | `next`, `joinOn`, `meter`(marker), `compensation`(node-id), `optional` | add `condition`, `onFailure`, `policies` block |
| task: `capability`+`operation`, `input`/`output` expr | `capabilityKey`; whole payload+collectedResults passed; result keyed by capabilityKey | add `operation`, `input`/`output` expression mapping |
| branch: `arms[].when` + **required `default`** | `arms[].expression`, no `default` (engine throws if no arm matches) | rename→`when` (or alias), add `default` |
| policies enforced | `meter` is a bare string; retry/timeout/CB/meter-N **not** enforced | implement policy executors (T1 retry/timeout, T2 meter/CB) |
| typed `context` + CEL | implicit payload map; simple `ExpressionEvaluator` | typed context doc; CEL-compatible evaluator (both repos) |
| saga (reverse order, multi-step) | single-node compensation (engine runs one comp node on ERROR) | T2: ordered, idempotent, retried saga |
| async `wait`/`timer` | none | T3 |
| run-state store | in-memory / Aerospike `JourneyInstance` | charter says Postgres + CAS frontier; **decision below** |

## 1. Contract migration strategy (the crux)

The §7 schema **renames** fields the locked contract uses
(`capabilityKey`→`capability`+`operation`, `expression`→`when`, adds `default`,
`policies`, `context`, `pools`). That breaks the schema lock + every test on both
sides at once. Two ways to land it:

- **(A) Flag-day v2 (recommended).** Treat §7 as **journey schema v2**. Add a
  top-level `"schema": 2` (or use the existing `version`) and a v2 loader/serializer
  alongside v1. Migrate the locked `loan-origination` fixture to v2 in one commit
  across both repos (regenerate via `tool/emit_contract.dart`, copy byte-identical
  to the engine). Old v1 fixtures can be dropped since nothing in production yet.
- **(B) Backward-compatible aliases.** Accept both `capabilityKey`/`capability`,
  `expression`/`when`, optional `default`. Lower blast radius, but the stored JSON
  drifts from the clean §7 shape and the Designer must emit one canonical form
  anyway — so the alias layer is throwaway.

**Recommendation: (A).** Nothing is in production; a clean v2 is cheaper than
carrying aliases. The schema is defined **fully** at v2/T1 (all node types +
policies parse & validate) even though the engine executes types tier by tier.

## 2. Tier 1 — full schema + core execution

### Designer (`journey-dag-designer`) — build against the FULL schema now
- **Models** (`lib/domain/models/`): replace the 3-variant `DagNode` with the full
  sealed union — `task, branch, parallel, join, wait, timer, human, foreach,
  subjourney, terminal`; add `condition`, `onFailure`, `policies` (`retry`,
  `timeout`, `circuitBreaker`, `meter`), `compensation` as `{operation,input}`,
  `pools`, `context.schemaRef`. (freezed regen.)
- **`ConfigSerializer`**: emit/parse the §7 shape exactly (the byte-for-byte
  contract). New round-trip + golden fixtures.
- **`DagValidator`**: implement all 9 §9 rules (we already have reachability,
  acyclic, terminal-reachable, joinOn-predecessor, compensation-exists,
  unknown-capability; **add** branch-`default` coverage, `wait` timeout+onTimeout,
  expression-parses-against-context).
- **Editor UI**: node palette gains the new types; inspector panels expose
  `policies`, `condition`, `onFailure`, `input`/`output`. (Builds on the editor
  already shipped.)
- **Expression**: CEL-compatible subset evaluator (see §4) for live validation +
  the simulation engine.

### Engine (`origination-journey`) — execute the T1 subset
- **Model/loader** (`domain/model/JourneyNode`, `adapter/out/loader/JourneyDefinitionLoader`):
  parse the **full** §7 grammar (unknown-to-T1 types load but are rejected by a
  capability-gate until their tier ships).
- **`JourneyEngine`**: add `parallel` (fan-out) + `join` node execution (today join
  is a field); evaluate `condition`; route `onFailure` (`node id | fail`); apply
  `retry`/`timeout` policy on task dispatch. Keep the async Kafka invoke/result loop.
- **Context**: carry a typed `context` document on `JourneyInstance` (replaces the
  ad-hoc `payload`+`collectedResults`); `input`/`output` map via the evaluator.
- **Tests**: port `JourneyEngineTest` + `full-flow-it` to the v2 contract.

### Run-state store decision (§8 says Postgres)
Today: in-memory + Aerospike `JourneyInstanceStore` (durable, CAS via generation).
The charter says Postgres. **Recommendation:** keep the `JourneyInstanceStore`
**port** and add a `PostgresJourneyInstanceStore` adapter (frontier + context +
CAS) selected by `idfc.engine.state-store=postgres`, alongside the existing
aerospike/in-memory. No engine-logic change — just another out-adapter. Defer to
end of T1 (the port already abstracts it).

## 3. Tiers 2–4 (gated on §13 inputs)

- **T2:** `meter` backpressure (a real semaphore/pool keyed by `pool` name, bounded
  by `pools[].maxConcurrent`; excess parks on the Kafka topic) — this is the Diwali
  story; ordered/idempotent/retried saga over `compensation`; `onFailure: compensate`;
  `circuitBreaker`; `join policy: anyOf | quorum(n)`. **Needs:** `finnone_pool` N (§13.2).
- **T3:** `wait` (park state + `correlation` registry + resume on correlated Kafka
  event) and `timer` (`delay`/`at`). **Needs:** callback event contracts (§13.3).
- **T4:** `subjourney` (call + version pin), `foreach` (bounded fan-out), `human`
  (maker-checker task + form + outcomes). **Needs:** capability registry (§13.4).

## 4. Expression language (binds both consumers)
**Recommendation:** a **CEL-compatible safe subset** — field access, `== != < <= > >=`,
`&& || !`, string/number/bool literals, `[]`/`.` navigation — implemented identically
in `origination-journey` (`ExpressionEvaluator`) and the Designer
(`lib/domain/services`). No new heavy dependency, deterministic, authorable. Document
the grammar in one place and test the SAME cases on both sides (a cross-repo fixture,
like the schema lock). Swap to full `cel-java` + a Dart CEL port later only if the
subset proves limiting.

## 5. Open inputs (block T2/T3 — §13)
1. `loan-origination` **context schema** (for expression validation). 2. `finnone_pool`
**N**. 3. Digio/Ingenico/NPCI **callback contracts** + correlation keys. 4. **Capability
registry** (capability/operation names = Designer palette). 5. **CEL vs subset** confirm.

## 6. Suggested commit sequence
1. (this) charter + plan committed.
2. T1-a: §7 models + serializer + validator in the Designer; migrate the fixture to
   v2; Designer tests green.
3. T1-b: engine v2 loader/model + `parallel`/`join`/`condition`/`onFailure`/retry/
   timeout; port engine + full-flow tests; build green.
4. T1-c: `PostgresJourneyInstanceStore` adapter behind the existing port.
5. T2 / T3 / T4 once §13 inputs land — one tier per change set.
