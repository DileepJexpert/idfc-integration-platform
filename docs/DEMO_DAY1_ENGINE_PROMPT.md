# Demo Day-1 — Orchestration Engine prompt

> Seed prompt for the first real slice of the `origination-journey` orchestration
> engine. The engine reads the journey registry/config and EXECUTES journeys at
> runtime; the Journey DAG Designer only authors that config. This doc is a
> working seed — extend it when the engine slice is scoped. The **DAG CONFIG
> SCHEMA** section below is already binding because the contract is locked.

## DAG CONFIG SCHEMA

The engine consumes journeys expressed as a DAG. The canonical config JSON is a
**shared contract** with the DAG Designer's `ConfigSerializer`. Node shapes
(field names exactly as emitted):

- `task`   — `{ "type":"task", "id", "capabilityKey", "next":[...], "joinOn":[...], "meter"?, "compensation"?, "optional"? }`
- `branch` — `{ "type":"branch", "id", "joinOn":[...], "arms":[ { "expression", "next" } ] }`
- `terminal`— `{ "type":"terminal", "id", "action"?, "emit":[...] }`

Top level: `{ "key", "startNodeId", "nodes":[...], "layout": { id: {x,y} } }`.
`capabilityKey` references a real backend capability module (`customer-party`,
`kyc`, `bureau`, `scoring`, `lending-origination`, `lending-servicing`,
`payments`). `layout` is authoring metadata; the engine may ignore it but MUST
tolerate it.

### CONTRACT LOCK (authoritative)

The engine MUST load the file
`src/main/resources/journeys/loan-origination.journey.json` — this is the EXACT
JSON produced by the DAG Designer's `ConfigSerializer` and is the authoritative
schema. Add a test asserting it parses into a valid `JourneyDefinition` and that
the parsed graph matches the expected nodes/edges/branch. If the engine cannot
load this file, the schema is MISALIGNED — STOP and flag it. Do NOT "fix" a
mismatch by bending the engine's parser to a shape the frontend does not emit; a
mismatch is a co-lock decision (the fix may belong on either side). Use the field
names EXACTLY as they appear in the file (`type`, `id`, `capabilityKey`, `next`,
`arms`, `expression`, `startNodeId`, etc.) — do not rename or add required fields
the frontend does not emit.

> Down payment already in place: `ContractFixtureTest` (in this module's tests)
> loads and structurally validates that fixture today. When the real
> `JourneyDefinition` loader lands, upgrade that test to parse into the domain
> type and assert the graph — it stays the engine half of the two-sided drift
> check.

## Scoping (do not import SaaS concepts)

Journeys bind by **(businessLine × product × partner)**. `businessLine` equals
the SFDC edge `type` routing dimension (`PERSONAL_LOAN`, `LAP`, …). There is **no
`tenant`** and **no `cell`** in this domain.
