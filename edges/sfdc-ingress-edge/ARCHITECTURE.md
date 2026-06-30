# SFDC Ingress Edge — Architecture

> **Module:** `edges/sfdc-ingress-edge` · **Type:** protocol edge (inbound, Slice-1 reference) · **Port:** `8080` (`${SERVER_PORT:8080}`) · **Runtime:** Spring Boot (Java, hexagonal)

## 1. Purpose & Context

The SFDC ingress edge is the **channel door for Salesforce (the assisted channel)**: it receives SFDC outbound notifications over REST, and is deliberately thin — it authenticates, validates, dedupes, normalizes, routes, and fast-ACKs, with **no business logic** of its own. Its single contract with the rest of the platform is the `CanonicalEnvelope` (in `shared-domain`): the edge maps the SFDC-specific HTTP shape onto that canonical envelope and publishes it to the engine's origination Kafka topic, so the engine "cannot tell which door" an event entered through. It then closes the loop by consuming the engine's decision topic (`orig.decision.v1`), filtering for `source == SFDC`, and pushing the decision back to SFDC. **Exactly-once is anchored on Aerospike `CREATE_ONLY`**: two concurrent identical notifications contend on an atomic insert keyed by `notificationId`, exactly one wins, and every subsequent status change is a generation compare-and-set (`EXPECT_GEN_EQUAL`) — so a decision is pushed back to SFDC exactly once, never zero or twice (the C1 ownership guard).

## 2. High-Level Block Diagram

```mermaid
flowchart LR
    SFDC["SFDC (Salesforce)<br/>outbound notifications + decision sink"]
    EDGE["sfdc-ingress-edge<br/>(Spring Boot, port 8080)"]
    AERO["Aerospike<br/>idempotency store<br/>(CREATE_ONLY + gen-CAS)"]
    ORIG["Kafka: origination topics<br/>orig.sfdc.pl.v1 / lap.v1 / bl.v1 / commercial.v1"]
    DEC["Kafka: decision topic<br/>orig.decision.v1"]
    ENGINE["origination engine /<br/>journey (downstream)"]

    SFDC -- "POST /api/v1/sfdc/notifications" --> EDGE
    EDGE -- "publish CanonicalEnvelope" --> ORIG
    ORIG --> ENGINE
    ENGINE -- "JourneyDecision (source=SFDC)" --> DEC
    DEC -- "consume + filter source==SFDC" --> EDGE
    EDGE -- "pushDecision (callback)" --> SFDC
    EDGE <-- "insertIfAbsent / CAS status" --> AERO
```

## 3. Low-Level Block Diagram

```mermaid
flowchart TB
    subgraph IN["Inbound Adapters"]
        CTRL["SfdcIngressController<br/>@PostMapping /api/v1/sfdc/notifications"]
        DECCTRL["SfdcDecisionController<br/>@PostMapping /api/v1/sfdc/decisions (manual)"]
        DECCONS["SfdcDecisionConsumer<br/>@KafkaListener orig.decision.v1<br/>filter source==SFDC"]
    end

    subgraph APP["Application / Domain"]
        ING["SfdcIngressService<br/>dedupe → normalize → route → publish → fast-ACK"]
        DEDUP["DedupeService<br/>4-path verdict + composite key"]
        NORM["Normalizer<br/>SfdcInboundEvent → CanonicalEnvelope"]
        DECSVC["DecisionService<br/>CAS into DECIDED → push (C1)"]
        POL["EdgePolicies / EdgeDisposition"]
    end

    subgraph PORTS["Outbound Ports"]
        IDEMP["IdempotencyStorePort"]
        PUB["MessagePublisherPort"]
        SFDCP["SfdcResponsePort"]
        BLOB["BlobStorePort"]
        ORGP["OrgConfigPort"]
        AUTHP["AuthTokenPort"]
    end

    subgraph OUT["Outbound Adapters"]
        AEROADP["AerospikeIdempotencyStore<br/>CREATE_ONLY + gen-CAS"]
        INMEM["InMemoryIdempotencyStore<br/>(test fake)"]
        KAFKAPUB["KafkaMessagePublisher<br/>→ origination topic + DLQ"]
        MOCKSFDC["MockSfdcResponseAdapter<br/>pushDecision (Kong later)"]
        MOCKS3["MockS3BlobStoreAdapter"]
        MOCKORG["ConfigOrgConfigStore"]
        MOCKAUTH["MockAuthTokenAdapter"]
    end

    %% ingest path
    CTRL --> ING
    CTRL --> AUTHP
    ING --> DEDUP
    ING --> NORM
    ING --> POL
    DEDUP --> IDEMP
    ING --> IDEMP
    ING --> ORGP
    ING --> BLOB
    ING --> PUB

    %% decision-return path
    DECCONS --> DECSVC
    DECCTRL --> DECSVC
    DECSVC --> IDEMP
    DECSVC --> SFDCP

    %% ports to adapters
    IDEMP -.-> AEROADP
    IDEMP -.-> INMEM
    PUB -.-> KAFKAPUB
    SFDCP -.-> MOCKSFDC
    BLOB -.-> MOCKS3
    ORGP -.-> MOCKORG
    AUTHP -.-> MOCKAUTH
```

## 4. Flow Diagram

**4a. Inbound ingest**

```mermaid
sequenceDiagram
    participant SFDC
    participant Ctrl as SfdcIngressController
    participant Auth as AuthTokenPort
    participant Svc as SfdcIngressService
    participant Dedup as DedupeService
    participant Store as IdempotencyStorePort<br/>(AerospikeIdempotencyStore)
    participant Norm as Normalizer
    participant Pub as MessagePublisherPort<br/>(KafkaMessagePublisher)
    participant Kafka as orig.sfdc.&lt;line&gt;.v1

    SFDC->>Ctrl: POST /api/v1/sfdc/notifications<br/>(SfdcNotificationRequest, X-Auth-Token)
    Ctrl->>Auth: authenticate(authToken)
    Ctrl->>Ctrl: request.isStructurallyValid()
    Ctrl->>Svc: ingest(SfdcInboundEvent)
    Svc->>Dedup: resolve(event)
    Dedup->>Store: insertIfAbsent(record)  %% CREATE_ONLY
    Store-->>Dedup: INSERTED (winner) | ALREADY_EXISTS
    Dedup-->>Svc: DedupeResult (NEW / IN_FLIGHT / DECIDED / FAILED)
    Note over Svc: NEW → processForPublish
    Svc->>Store: compareAndSetStatus(record, IN_FLIGHT)  %% gen-CAS (C4)
    Svc->>Norm: toEnvelope(event, routing, payloadRef, origCorrId)
    Norm-->>Svc: CanonicalEnvelope (source=SFDC)
    Svc->>Pub: publish(envelope, routing, headers)
    Pub->>Kafka: send(topic, key=notificationId, JSON)  %% sync, acks=all
    Pub-->>Svc: ok
    Svc-->>Ctrl: EdgeResult(ACK_PROCESSED)
    Ctrl-->>SFDC: 200 OK EdgeResponse<br/>(503 if RETRY_TRANSIENT → SFDC redelivers)
```

**4b. Decision return**

```mermaid
sequenceDiagram
    participant Kafka as orig.decision.v1
    participant Cons as SfdcDecisionConsumer
    participant Svc as DecisionService
    participant Store as IdempotencyStorePort<br/>(AerospikeIdempotencyStore)
    participant Sfdc as SfdcResponsePort<br/>(MockSfdcResponseAdapter)

    Kafka->>Cons: onMessage(decisionJson)
    Cons->>Cons: parse source, notificationId, outcome, loanId
    Note over Cons: filter — return early unless source == "SFDC"
    Cons->>Svc: applyDecision(notificationId, Decision, correlationId)
    Svc->>Store: findByNotificationId(notificationId)
    alt status already DECIDED
        Svc-->>Cons: false (already decided; no push — C1)
    else transition into DECIDED
        Svc->>Store: compareAndSetStatus(record, DECIDED, decision)  %% gen-CAS on notificationId
        alt CAS applied (we own the transition)
            Svc->>Sfdc: pushDecision(decidedRecord, correlationId)
            Sfdc-->>Svc: pushed
            Svc-->>Cons: true (transitioned + pushed exactly once)
        else CAS lost (concurrent winner)
            Svc-->>Cons: false (cas-lost; no push — C1)
        end
    end
```

## 5. Key Classes & Files

### Inbound ingest path

| File | Role |
|------|------|
| `src/main/java/.../adapter/in/rest/SfdcIngressController.java` | REST `@PostMapping /api/v1/sfdc/notifications`; auth + structural validation → `SfdcIngressService.ingest`; maps `EdgeDisposition` to HTTP 200/503; schema-invalid → ACK + DLQ (C2) |
| `src/main/java/.../adapter/in/rest/SfdcNotificationRequest.java` | Inbound DTO (HTTP shape of an SFDC outbound message); `isStructurallyValid()` |
| `src/main/java/.../adapter/in/rest/EdgeResponse.java` | Fast-ACK response body (notificationId, disposition, reason, correlationId) |
| `src/main/java/.../domain/model/SfdcInboundEvent.java` | Validated framework-free inbound event; `hasApplicationFallback()` |
| `src/main/java/.../application/SfdcIngressService.java` | Orchestration: dedupe → normalize → route → claim-check → publish → fast-ACK; C2/C4/C5 error handling |
| `src/main/java/.../application/DedupeService.java` | 4-path dedupe verdict; primary `notificationId` CREATE_ONLY gate + composite `sfdcRecordId+applicationRef` application-pointer fallback |
| `src/main/java/.../application/DedupeResult.java`, `DedupePath.java` | Dedupe verdict (NEW / IN_FLIGHT / DECIDED / FAILED) |
| `src/main/java/.../application/Normalizer.java` | Pure map `SfdcInboundEvent` → `CanonicalEnvelope` (`SCHEMA_VERSION = sfdc-ingress.v1`, `source=SFDC`) |
| `src/main/java/.../application/EdgeDisposition.java`, `EdgeResult.java`, `EdgePolicies.java` | ACK/no-ACK semantics + poison/retry policy ceilings |
| `src/main/java/.../adapter/out/kafka/KafkaMessagePublisher.java` | Publishes envelope JSON to origination topic (keyed by `notificationId`, sync `acks=all`); `publishToDlq` |
| `src/main/java/.../adapter/in/kafka/FinnOneBackpressureConsumer.java` | §G backpressure harness consumer (bounded to N) off origination topics — not the decision path |

### Decision-return path

| File | Role |
|------|------|
| `src/main/java/.../adapter/in/kafka/SfdcDecisionConsumer.java` | `@KafkaListener` on `orig.decision.v1`; filters `source==SFDC`; calls `DecisionService.applyDecision(notificationId, …)` |
| `src/main/java/.../adapter/in/rest/SfdcDecisionController.java` | Manual `@PostMapping /api/v1/sfdc/decisions` driving the same `DecisionService` |
| `src/main/java/.../application/DecisionService.java` | C1 ownership guard: CAS into DECIDED on `notificationId`; pushes back exactly once on the winning transition |
| `src/main/java/.../adapter/out/mock/MockSfdcResponseAdapter.java` | `SfdcResponsePort` impl: logs `sfdc.push-decision` (real Kong delivery is a later slice) |
| `src/main/java/.../domain/model/Decision.java` | Opaque decision value (outcome, applicationId, termsJson) |

### Idempotency

| File | Role |
|------|------|
| `src/main/java/.../domain/port/IdempotencyStorePort.java` | OUT port: `insertIfAbsent` (CREATE_ONLY), `compareAndSetStatus` (gen-CAS), `linkApplication`, retry/redelivery CAS |
| `src/main/java/.../adapter/out/aerospike/AerospikeIdempotencyStore.java` | Real store: `RecordExistsAction.CREATE_ONLY`, `GenerationPolicy.EXPECT_GEN_EQUAL`, native TTL, hot-key (KEY_BUSY) retry |
| `src/main/java/.../domain/model/IdempotencyRecord.java`, `RecordStatus.java`, `ApplicationKey.java`, `domain/port/CasResult.java` | Record + lifecycle (`RECEIVED→IN_FLIGHT→DECIDED|FAILED`) + CAS result |
| `src/main/java/.../config/AerospikeProperties.java`, `config/EdgeBeanConfiguration.java` | Aerospike client/store wiring (concrete adapter exposed only as the port) |
| `src/test/java/.../support/InMemoryIdempotencyStore.java` | In-memory fake mirroring CREATE_ONLY + gen-CAS for unit tests (not a substitute for the real concurrency gate) |

### Shared contract / config

| File | Role |
|------|------|
| `shared/shared-domain/.../envelope/CanonicalEnvelope.java` | THE shared origination contract emitted by every edge |
| `shared/shared-domain/.../envelope/SourceSystem.java` | `SFDC` / `DIGITAL` channel discriminator |
| `src/main/java/.../config/KafkaConfig.java`, `config/EdgeProperties.java` | Bounded listener factory + topic auto-create; routing/known-orgs as data |
| `src/main/resources/application.yml` (+ `-local.yml`, `-eks.yml`) | Port, Kafka, Aerospike, routing, topics |

## 6. Interfaces

### Inbound

- **REST:** `POST /api/v1/sfdc/notifications` (`SfdcIngressController`) — the primary SFDC door; header `X-Auth-Token`, body `SfdcNotificationRequest`; returns `EdgeResponse` with HTTP 200 (ACK) or 503 (transient → SFDC redelivers); 401 on auth failure.
- **REST (manual):** `POST /api/v1/sfdc/decisions` (`SfdcDecisionController`) — manual decision callback for the same `DecisionService`.
- **Kafka (consumed):** `orig.decision.v1` (group `sfdc-ingress-edge-decisions`) via `SfdcDecisionConsumer` — the engine's decision topic; only `source == SFDC` messages are acted on.

### Outbound

- **Kafka (produced):** origination topics `orig.sfdc.pl.v1`, `orig.sfdc.lap.v1`, `orig.sfdc.bl.v1`, `orig.sfdc.commercial.v1` (one per business line, resolved via `OrgConfigPort` routing); DLQ `orig.sfdc.dlq.v1`. Keyed by `notificationId`, value = `CanonicalEnvelope` JSON, `acks=all`, synchronous send.
- **SFDC callback:** `SfdcResponsePort.pushDecision(...)` (mocked by `MockSfdcResponseAdapter`; real Kong delivery later).
- **Aerospike:** `IdempotencyStorePort` → `AerospikeIdempotencyStore` (namespace `idfc`, sets `idem` + `idem_app`).

### Contract

- **`CanonicalEnvelope`** (shared-domain): `transactionId, schemaVersion (sfdc-ingress.v1), source (SFDC), type, notificationId, orgId, sfdcRecordId, applicationRef, correlationId, originalCorrelationId, payloadRef, payloadContentType, occurredAt`.
- **JourneyDecision (on `orig.decision.v1`):** JSON consumed by `SfdcDecisionConsumer` — fields `source`, `notificationId`, `outcome`, `loanId`, `correlationId`. `source` (filter to `SFDC`) and `notificationId` (CAS key) are load-bearing; `sfdcRecordId`/`applicationRef` of the originating SFDC record live on the stored idempotency record keyed by that `notificationId`.

## 7. Configuration & How to Run

- **Server port:** `8080` (override via `SERVER_PORT`).
- **Spring profiles:**
  - `local` (`application-local.yml`) — Kafka via host listener `localhost:29092` against docker-compose infra.
  - `eks` (`application-eks.yml`) — production posture; endpoints injected from cluster ConfigMap/Secret env vars.
  - default `application.yml` — `localhost:9092` Kafka, `localhost:3000` Aerospike.
- **Aerospike vs in-memory toggle:** the runtime wiring (`EdgeBeanConfiguration`) binds `IdempotencyStorePort` to `AerospikeIdempotencyStore`; the in-memory `InMemoryIdempotencyStore` is a **test-only** fake under `src/test`. The Aerospike client sets `failIfNotConnected = false` so the context can start for Kafka-only paths even without a live cluster.
- **Key config values (`application.yml` → `idfc.*`):**
  - `idfc.aerospike`: `namespace=idfc`, `record-set=idem`, `app-pointer-set=idem_app`, `ttl-seconds=2592000` (30d; must exceed the SFDC retry window).
  - `idfc.edge.dlq-topic=orig.sfdc.dlq.v1`; `poison-redelivery-threshold=5` (C5); `max-journey-retry=1` (C3); `finnone.max-concurrency=4` (§G backpressure cap).
  - `idfc.edge.routing`: type→topic→journey rows — PERSONAL_LOAN→`orig.sfdc.pl.v1`, LAP→`orig.sfdc.lap.v1`, BUSINESS_LOAN→`orig.sfdc.bl.v1`, COMMERCIAL→`orig.sfdc.commercial.v1` (all → `origination-journey`).
  - **Decision topic / group** (`SfdcDecisionConsumer`): `idfc.edge.decision-topic` (default `orig.decision.v1`), `idfc.edge.decision-group` (default `sfdc-ingress-edge-decisions`).
  - `idfc.edge.known-orgs`: `ORG1, IDFC_RETAIL, IDFC_BUSINESS` (unknown org → refresh+recheck → DLQ, C2).
- **Run (local):**
  1. Start infra: `docker compose -f docker-compose.infra.yml up -d` (Kafka on `29092`, Aerospike on `3000`).
  2. `./gradlew :edges:sfdc-ingress-edge:bootRun --args='--spring.profiles.active=local'`.
  3. Smoke test: `POST http://localhost:8080/api/v1/sfdc/notifications` with header `X-Auth-Token: dev-token` and a body carrying `notificationId`, `orgId`, `type`, `payload`.
  - Health: `GET /actuator/health` (exposure limited to `health,info,prometheus`).
