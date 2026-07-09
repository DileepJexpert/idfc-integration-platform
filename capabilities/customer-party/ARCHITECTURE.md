# Customer / Party — Architecture

> **Module:** `capabilities/customer-party` · **Type:** capability · **Port:** 8090 · **Runtime:** Spring Boot (Java, hexagonal)

## 1. Purpose & Context

The customer-party capability resolves (and dedups) a customer against the bank's customer source of truth, Posidex, returning the customer's CRN, customer id, name, and status. It is a capability microservice in the IDFC integration platform's hexagonal architecture: the orchestration engine invokes it over Kafka for a single DAG task node, passing the applicant identity in the `CapabilityRequest` payload. The capability resolves the customer and replies with a `CapabilityResponse` so the engine can store the result and advance the journey. Customer / Party is an integration — it resolves against the master, it does not own it.

## 2. High-Level Block Diagram

```mermaid
flowchart LR
    ENGINE[Orchestration Engine]
    REQ[["cap.customer-party.request.v1"]]
    RES[["cap.customer-party.response.v1"]]
    CP[customer-party capability]
    POSIDEX[(Posidex<br/>customer source of truth)]

    ENGINE -->|publishes CapabilityRequest| REQ
    REQ --> CP
    CP -->|publishes CapabilityResponse| RES
    RES --> ENGINE
    CP -->|HTTP POST /posidex/resolve| POSIDEX
```

## 3. Low-Level Block Diagram

```mermaid
flowchart TB
    subgraph Inbound
        CONSUMER[CustomerPartyRequestConsumer<br/>@KafkaListener]
    end

    subgraph AppDomain["Application / Domain"]
        SERVICE[CustomerPartyService<br/>handle CapabilityRequest]
        PROFILE[CustomerProfile<br/>domain record]
    end

    subgraph Ports["Outbound Ports"]
        POSIDEXPORT[PosidexPort<br/>resolve]
        RESPPORT[CapabilityResponsePort<br/>publish]
    end

    subgraph Adapters
        HTTP[PosidexHttpAdapter<br/>real - RestClient]
        MOCK[MockPosidexAdapter<br/>mock]
        PUB[KafkaCapabilityResponsePublisher<br/>KafkaTemplate]
    end

    CONSUMER --> SERVICE
    SERVICE --> POSIDEXPORT
    SERVICE --> PROFILE
    POSIDEXPORT -. real .-> HTTP
    POSIDEXPORT -. mock .-> MOCK
    CONSUMER --> RESPPORT
    RESPPORT --> PUB
```

## 4. Flow Diagram

```mermaid
sequenceDiagram
    participant Engine as Orchestration Engine
    participant Consumer as CustomerPartyRequestConsumer
    participant Service as CustomerPartyService
    participant Port as PosidexPort
    participant Posidex as Posidex (HTTP/mock)
    participant Pub as CapabilityResponsePort

    Engine->>Consumer: cap.customer-party.request.v1 (CapabilityRequest JSON)
    Consumer->>Consumer: objectMapper.readValue -> CapabilityRequest
    Consumer->>Service: handle(request)
    Service->>Port: resolve(request.payload())
    Port->>Posidex: resolve identity
    Posidex-->>Port: CustomerProfile (crn, customerId, name, status)
    Port-->>Service: CustomerProfile
    Service-->>Consumer: CapabilityResponse (OK + result)
    Consumer->>Pub: publish(response)
    Pub->>Engine: cap.customer-party.response.v1 (CapabilityResponse JSON)
```

On any `RuntimeException` from `posidex.resolve(...)`, `CustomerPartyService.handle()` returns a `CapabilityResponse` with `CapabilityStatus.ERROR` and an empty result, and the engine fails the journey.

## 5. Key Classes & Files

| File | Role |
| --- | --- |
| `src/main/java/.../party/CustomerPartyApplication.java` | Spring Boot entry point (`@SpringBootApplication`). |
| `src/main/java/.../party/adapter/in/kafka/CustomerPartyRequestConsumer.java` | Inbound Kafka adapter; `@KafkaListener` on the request topic; deserializes `CapabilityRequest`, calls the service, publishes the response. |
| `src/main/java/.../party/application/CustomerPartyService.java` | Framework-free application service; `handle(CapabilityRequest)` resolves the customer and maps it to a `CapabilityResponse`. |
| `src/main/java/.../party/domain/model/CustomerProfile.java` | Domain record: `crn`, `customerId`, `name`, `status`. |
| `src/main/java/.../party/domain/port/PosidexPort.java` | Outbound port: `CustomerProfile resolve(Map<String,Object> identity)`. |
| `src/main/java/.../party/domain/port/CapabilityResponsePort.java` | Outbound port: `void publish(CapabilityResponse)`. |
| `src/main/java/.../party/adapter/out/posidex/PosidexHttpAdapter.java` | Real Posidex adapter; HTTP `POST /posidex/resolve` via `RestClient`. |
| `src/main/java/.../party/adapter/out/posidex/MockPosidexAdapter.java` | Mock Posidex adapter; deterministic profile derived from the applicant's PAN. |
| `src/main/java/.../party/adapter/out/kafka/KafkaCapabilityResponsePublisher.java` | Outbound Kafka adapter; publishes the response JSON keyed by `journeyInstanceId`. |
| `src/main/java/.../party/config/CustomerPartyConfiguration.java` | Bean wiring; selects mock/real Posidex by config; builds the producer factory, `KafkaTemplate`, and response publisher. |
| `src/main/java/.../party/config/PosidexProperties.java` | `@ConfigurationProperties(prefix = "idfc.customer-party.posidex")`; `mode` defaults to `mock`, `isReal()` selects the real adapter. |
| `src/main/resources/application.yml` | Base config: server port, Kafka serde, Posidex mode/url, actuator. |

## 6. Interfaces

- **Inbound:** consumes `cap.customer-party.request.v1` (Kafka, JSON String serde) via `CustomerPartyRequestConsumer` with consumer group `${idfc.capability.group:customer-party}`. The topic is derived from the capability key `customer-party` through `CapabilityTopics.request(...)`.
- **Outbound:**
  - Produces `cap.customer-party.response.v1` (topic derived from the response's `capabilityKey` via `CapabilityTopics.response(...)`) through `KafkaCapabilityResponsePublisher`.
  - Vendor port: `PosidexPort.resolve(...)` — real adapter calls Posidex over HTTP `POST /posidex/resolve`; mock adapter resolves locally.
  - No separate domain/integration events are emitted beyond the `CapabilityResponse`.
- **Contract:** the shared capability contract in `shared:shared-domain` — `CapabilityRequest`, `CapabilityResponse`, `CapabilityStatus`, with topic names from `CapabilityTopics`. This capability implements its own `CustomerPartyService.handle(CapabilityRequest)` method directly; it does **not** use the shared-capability framework (`CapabilityDispatcher` / `Capability`).

## 7. Configuration & How to Run

- **Server port:** `8090` (`SERVER_PORT` override).
- **Spring profiles:**
  - `local` (`application-local.yml`): Kafka at `localhost:29092` (docker host listener); Posidex `mode: real`, `url: http://localhost:19101` (docker-compose mock vendor).
  - `eks` (`application-eks.yml`): production posture — Posidex `mode: real`, `url: ${POSIDEX_URL}` injected from the cluster ConfigMap/Secret.
  - default (no profile, `application.yml`): Kafka at `localhost:9092`, Posidex `mode: mock` (`POSIDEX_MODE`), `url: http://localhost:19101` (`POSIDEX_URL`).
- **Key `application.yml` settings:** `spring.application.name=customer-party`; Kafka String key/value serde, `auto-offset-reset=earliest`; `idfc.customer-party.posidex.{mode,url}`; actuator exposes only `health,info,prometheus`.
- **How to run:**
  - IntelliJ: run `CustomerPartyApplication` (optionally set the active profile `local` or `eks` via `SPRING_PROFILES_ACTIVE`).
  - Gradle: `./gradlew :capabilities:customer-party:bootRun` (add `-Dspring.profiles.active=local` to run against the docker-compose infra started with `docker compose -f docker-compose.infra.yml up -d`).
