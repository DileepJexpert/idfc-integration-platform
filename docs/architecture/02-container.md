# L2 — Container (deployables)

**Zoom:** the runnable pieces. **Audience:** architects, ops, SRE.
**Question answered:** *What runs where, and how do the pieces talk?*

A "container" here = a thing you deploy/run (a Spring Boot service) or a shared library that several
services build on. There are two communication styles — **async over Kafka** (the engine lane) and
**sync in-thread HTTP** (the sync lane).

```mermaid
flowchart LR
  subgraph EDGES["Edges (intake doors)"]
    SFDCIN[sfdc-ingress-edge<br/>:8080]:::svc
    DIG[digital-partner-edge<br/>:8081<br/>+ SYNC doors]:::svc
    FB[file-batch-edge]:::svc
    SFDCOUT[sfdc-egress-edge]:::svc
  end

  subgraph ENGINE["Orchestration"]
    ENG[origination-journey<br/>engine + ops API :8082]:::eng
  end

  subgraph PLAT["Platform services / libs"]
    REG[journey-registry<br/>:8104]:::plat
    OPSQ[[ops-query lib]]:::lib
    IDEM[[platform-idempotency]]:::lib
    MSG[[platform-messaging]]:::lib
    CFG[[platform-config]]:::lib
    AUTH[[platform-auth]]:::lib
    RCR[[route-config-registry]]:::lib
  end

  subgraph CAPS["Capabilities (Kafka-invoked services)"]
    C1[kyc]:::cap
    C2[bureau]:::cap
    C3[scoring]:::cap
    C4[lending-origination]:::cap
    C5[verification]:::cap
    C6[device-validation]:::cap
    C7[mandate]:::cap
    C8[communications]:::cap
    C9[fusion-hcm]:::cap
    C10[customer-party · payments · lending-servicing · echo]:::cap
  end

  subgraph SYNCCAPS["Sync capabilities (LIBRARIES in the digital edge)"]
    S1[[imps-disbursal]]:::lib
    S2[[lms-utilities]]:::lib
    SS[[shared-sync<br/>invoker · house mapper]]:::lib
  end

  subgraph INFRA["Infrastructure"]
    KAFKA{{Kafka}}:::infra
    AERO[(Aerospike<br/>run-state + idempotency)]:::infra
    VENDORS[(Vendors<br/>WireMock in dev)]:::infra
  end

  SFDCIN & DIG & FB -->|publish orig.* envelopes| KAFKA
  KAFKA -->|orig.*| ENG
  ENG <-->|cap.KEY.request / cap.KEY.response| KAFKA
  KAFKA <--> C1 & C2 & C3 & C4 & C5 & C6 & C7 & C8 & C9 & C10
  ENG -->|orig.decision.v1| KAFKA --> SFDCOUT
  ENG -->|load DAG over HTTP| REG
  ENG --- OPSQ
  ENG <--> AERO
  C1 & C4 & C5 & C6 & C9 -->|real HTTP, timeouts| VENDORS
  DIG -->|in-thread| S1 & S2
  S1 & S2 --- SS
  S1 & S2 -->|real HTTP, timeouts| VENDORS

  classDef svc fill:#e8f0fe,stroke:#4a76d4;
  classDef eng fill:#e6f4ea,stroke:#34a853,stroke-width:2px;
  classDef cap fill:#fef7e0,stroke:#f9ab00;
  classDef plat fill:#f3e8fd,stroke:#a142f4;
  classDef lib fill:#f6f6f6,stroke:#888,stroke-dasharray:4 3;
  classDef infra fill:#eef,stroke:#77c;
```

## The async engine lane — message flow

The heart of the platform. One request threads these topics:

```mermaid
sequenceDiagram
  participant Ch as Channel
  participant Edge
  participant Eng as Engine
  participant Cap as Capability
  participant V as Vendor
  Ch->>Edge: SOAP / REST / file
  Edge->>Eng: publish orig.<line>.v1 (canonical envelope)
  Eng->>Eng: load journey DAG (registry), create run (Aerospike)
  loop each task node
    Eng->>Cap: cap.<key>.request.v1
    Cap->>V: real HTTP (behind a port)
    V-->>Cap: response DATA
    Cap->>Eng: cap.<key>.response.v1 (OK=business result | ERROR=technical class)
    Eng->>Eng: write context, evaluate next / branch, persist
  end
  Eng->>Ch: orig.decision.v1 (egress → channel)
```

**Topic naming (two families):**

| Family | Pattern | Named by | Example |
|---|---|---|---|
| Origination / entry | `orig.<source-or-line>.v1` | the source / business line | `orig.sfdc.pl.v1`, `orig.device-validation.v1` |
| Capability | `cap.<key>.request.v1` / `cap.<key>.response.v1` | the capability | `cap.device-validation.request.v1` |
| Decision | `orig.decision.v1` | — | terminal decision, keyed by `applicationRef` |
| Dead-letter | `orig.<line>.v1.dlq` | — | poison / permanent-failure quarantine |

## The sync lane — no Kafka, no engine

```mermaid
sequenceDiagram
  participant P as Partner
  participant Edge as Digital edge
  participant Inv as SyncCapabilityInvoker
  participant Cap as Sync capability (lib)
  participant V as Vendor
  P->>Edge: POST /api/v1/impsFT | /callLmsUtilities (Bearer)
  Edge->>Edge: validate Bearer (fail closed)
  Edge->>Inv: invoke(capabilityKey, op, payload, ctx)
  Inv->>Cap: in-thread
  Cap->>V: real HTTP (timeouts)
  V-->>Cap: DATA
  Cap-->>P: mapped result ON THE SAME CALL (200 business | 5xx technical)
```

## State & guarantees (where the durability lives)

- **Run-state store** (Aerospike): every journey instance's context + node history — the audit source of
  truth and what the ops view reads. In-memory variant for tests.
- **Idempotency store** (Aerospike, `platform-idempotency`): dedupes intake resends and money movement.
- **Confirmed delivery** (`platform-messaging`): a publish is "done" only when the broker acks; failures
  dead-letter. No swallow-and-commit.
- **Journey registry**: the versioned DAG store (maker-checker); the engine pins the version it runs.

## Shared libraries (build-time, not deployables)

| Library | Gives every consumer |
|---|---|
| `shared-domain` | the canonical envelope + common value objects (the shared contract) |
| `shared-capability` | the homogeneous async capability shell (consume request → execute → produce response, idempotent) |
| `shared-sync` | the sync-lane contracts (invoker, request context, technical exception, house-envelope mapper) |
| `shared-observability` | OTel / Micrometer wiring |

→ Next: **[L3 — Component](03-component.md)** (inside the engine, a capability, the sync lane, an edge).
