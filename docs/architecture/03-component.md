# L3 — Component (inside each container)

**Zoom:** the parts inside a deployable. **Audience:** developers.
**Question answered:** *What are the pieces of the engine / a capability / the sync lane / an edge, and who owns what?*

Four internals matter: the **engine** (the async brain), an **async capability** (the hexagonal worker),
the **sync lane** (the in-thread path), and an **edge** (the normaliser). Class names are the real ones.

---

## 3.1 The Orchestration Engine

The engine walks the DAG and owns **no business logic** — it traverses, invokes, persists, and enforces
policy. Business decisions live in capabilities; the DAG shape lives in the registry.

```mermaid
flowchart TB
  IN[["orig.* consumer"]]:::in --> ORCH
  RESP[["cap.*.response consumer"]]:::in --> ORCH
  ORCH[JourneyOrchestrator<br/>start run · resume on response]:::c
  REG["JourneyRegistry<br/>+ JourneyDefinitionLoader<br/>(loads DAG from journey-registry, version-pinned)"]:::c
  ENGINE[JourneyEngine<br/>walk nodes · apply policies]:::c
  EXPR[ExpressionEvaluator<br/>context expressions: input/output/when]:::c
  STORE[("JourneyInstanceStore<br/>Aerospike or in-memory<br/>run context + node history")]:::store
  OPS[OpsRunStoreAdapter → ops-query]:::c
  OUTREQ[["cap.*.request producer"]]:::out
  OUTDEC[["orig.decision producer"]]:::out

  ORCH --> REG
  ORCH --> ENGINE --> EXPR
  ENGINE <--> STORE
  ENGINE --> OUTREQ
  ENGINE --> OUTDEC
  ORCH --> OPS

  classDef in fill:#e8f0fe,stroke:#4a76d4;
  classDef out fill:#fce8e6,stroke:#d93025;
  classDef c fill:#e6f4ea,stroke:#34a853;
  classDef store fill:#eef,stroke:#77c;
```

- **JourneyOrchestrator** — `onOrigination(envelope)` starts a run; a `cap.*.response` resumes the run at
  the node that was waiting. **Persist-before-publish**: state is saved before the next request is emitted,
  and responses are keyed by `journeyInstanceId` so a resume lands on the right run.
- **JourneyEngine + ExpressionEvaluator** — from the current node, evaluate `input` → dispatch, apply
  `output` to `context`, evaluate `branch` `when`/`default`, honour policies (`retry`, `timeout`, `meter`
  pool, `compensation`, `onFailure: dlq`). Terminal `status` (`completed|rejected|failed`) maps to the
  shared ops vocabulary.
- **JourneyInstanceStore** — the durable run: context document + per-node stats (attempts, failure class,
  deadlines). The **audit source of truth** and what the ops view reads.
- **Fail-closed routing** — an unmapped envelope `type` (no `type-to-journey` row) does **not** default to
  a journey; it dead-letters. Unknown terminal status never defaults to APPROVED.

---

## 3.2 An async Capability (hexagonal, on the shared shell)

Every async capability is the SAME shell (`shared-capability`) wrapped around a small hexagon: **business
logic in the middle, I/O at the edges behind ports.** No brand/vendor branching in code — differences are
config rows.

```mermaid
flowchart LR
  REQ[["cap.KEY.request.v1"]]:::in --> SHELL
  SHELL[shared-capability shell<br/>resolve operation · idempotent on runId+nodeId]:::c
  CAP{{"Capability<br/>key + operations()"}}:::core
  PORT[/"out-port<br/>e.g. ImpsFtPort · BrandValidationPort"/]:::port
  ADP[adapter<br/>real HTTP client + auth + timeouts<br/>+ ErrorClass classification]:::c
  CFG[[Properties<br/>vendor url · auth · brand/row config]]:::plat
  V[(Vendor<br/>WireMock in dev)]:::ext
  RESP[["cap.KEY.response.v1<br/>OK = business result · ERROR = technical class"]]:::out

  SHELL --> CAP --> PORT --> ADP --> V
  CFG -.-> CAP
  CFG -.-> ADP
  CAP --> RESP

  classDef in fill:#e8f0fe,stroke:#4a76d4;
  classDef out fill:#fce8e6,stroke:#d93025;
  classDef c fill:#fef7e0,stroke:#f9ab00;
  classDef core fill:#e6f4ea,stroke:#34a853;
  classDef port fill:#fff,stroke:#333,stroke-dasharray:5 3;
  classDef plat fill:#f3e8fd,stroke:#a142f4;
  classDef ext fill:#eee,stroke:#999;
```

**Worked example — `device-validation`:** `DeviceValidationCapability` exposes four operations
(`decideActivities`, `validate`, `block`, `unblock`); `DeviceValidationVendorClient` is the real HTTP
adapter (per-brand auth, timeouts, 4xx→PERMANENT / 5xx→TRANSIENT / read-timeout→AMBIGUOUS);
`DeviceValidationProperties` holds the brand rows (flags, `validate-by`, pass-path). Adding a brand = a
row. The capability code has **zero brand `if`s** — proven by the HISENSE "add a brand with no code change"
test.

---

## 3.3 The Sync lane (in-thread, hosted on the digital edge)

The caller **blocks** for the result. No engine, no Kafka, no run-state. The contracts live in
`shared-sync`; the capabilities are libraries the edge `@Import`s.

```mermaid
flowchart LR
  HTTP[["POST /api/v1/impsFT<br/>/callLmsUtilities"]]:::in --> CTRL
  CTRL[Controller<br/>ImpsFtController · LmsUtilitiesController]:::c
  BEARER[ConfiguredBearerTokenValidator<br/>fail-closed Ory/Hydra allow-list]:::c
  INV[SyncCapabilityInvoker<br/>dispatch by capabilityKey, in-thread]:::core
  SVC[SyncInvocable capability<br/>ImpsDisbursalService · LmsUtilitiesService]:::cap
  PORT[/"ImpsFtPort · LmsUtilityPort"/]:::port
  MAP[HouseEnvelopeMapper<br/>metadata + resource_data → normalized]:::c
  V[(IMPS / LMS backend)]:::ext

  CTRL --> BEARER
  CTRL --> INV --> SVC --> PORT --> V
  SVC -. LMS .-> MAP

  classDef in fill:#e8f0fe,stroke:#4a76d4;
  classDef c fill:#fef7e0,stroke:#f9ab00;
  classDef core fill:#e6f4ea,stroke:#34a853;
  classDef cap fill:#fef7e0,stroke:#f9ab00;
  classDef port fill:#fff,stroke:#333,stroke-dasharray:5 3;
  classDef ext fill:#eee,stroke:#999;
```

- **Dispatch by capabilityKey only** — `source` (INDMONEY/SAVEIN) is trace/authz, never routing.
- **imps-disbursal** — idempotent (a repeated `idempotentId` returns the prior result, never double-transfers);
  `status:S` success / non-S business decline (200) / timeout-5xx technical (uniform 502, AMBIGUOUS on a
  money read-timeout).
- **lms-utilities** — `requestCode`-dispatched (`OFFER_CHECK` now; unknown → 422 fail-closed); response
  mapped by the shared `HouseEnvelopeMapper`; empty `resource_data` on SUCCESS = a clean "no offer".

---

## 3.4 An Edge (normaliser) — SFDC ingress

An edge's whole job: turn a channel-specific message into the **one canonical envelope** and route it —
schema-agnostic, opaque-payload, fail-closed, idempotent.

```mermaid
flowchart LR
  IN[["POST /api/v1/sfdc/outbound-messages<br/>(X-Auth-Token, fail closed)"]]:::in --> PARSE
  PARSE[SfdcOutboundMessageParser<br/>SOAP → notifications]:::c
  NORM[OutboundNotificationMapper + Normalizer<br/>SVCNAME__c → type · CDATA → opaque payload]:::c
  ROUTE["Routing (config rows)<br/>type → topic + downstream-journey"]:::c
  CLAIM[SfdcIngressService<br/>claim state machine · idempotency · poison breaker]:::core
  PUB[["publish orig.*.v1 (confirmed)"]]:::out
  DLQ[["orig.sfdc.dlq.v1<br/>unknown type/org → fail closed"]]:::out

  PARSE --> NORM --> ROUTE --> CLAIM --> PUB
  ROUTE -. unmapped .-> DLQ

  classDef in fill:#e8f0fe,stroke:#4a76d4;
  classDef out fill:#fce8e6,stroke:#d93025;
  classDef c fill:#e8f0fe,stroke:#4a76d4;
  classDef core fill:#e6f4ea,stroke:#34a853;
```

- **Opaque payload** — the edge carries the business body (CDATA) inline without parsing it; each
  `SVCNAME`'s downstream handler interprets it. The edge stays schema-agnostic.
- **Edge-generated correlationId** — the run key is minted by the edge (the inbound `correlationid` header
  is not trusted as the key); search ops by `notificationId` / `sfdcRecordId`.
- **Fail closed** — unknown `SVCNAME` (no routing row) or unknown org → DLQ, never a silent default.

→ Next: **[L4 — Journeys](04-journeys.md)** (each flow, node-by-node).
