# Shared Domain — Architecture

> **Module:** `shared/shared-domain` · **Type:** library · **Port:** n/a (library) · **Runtime:** Spring Boot / Java library · **Status:** implemented

## 1. Purpose & Context

`shared-domain` is the schema backbone of the platform: the canonical contracts that every channel edge, the orchestration engine, and every capability depend on. It carries **NO framework imports** (pure Java records/enums) so it can be shared everywhere without coupling. It defines two contract families: the **edge→engine envelope** (`CanonicalEnvelope` + `SourceSystem`) and **THE CAPABILITY CONTRACT** (`CapabilityRequest` / `CapabilityResponse` + `CapabilityStatus` / `ErrorClass` / `CapabilityTopics`). These types exist precisely so different doors emit the identical shape — proven by construction, not by a fixture that can drift.

## 2. High-Level Block Diagram

```mermaid
flowchart LR
    SFDC[sfdc-ingress-edge] --> SD
    DP[digital-partner-edge] --> SD
    ENG[orchestration engine] --> SD
    ECHO[capabilities/echo] --> SD
    MAN[capabilities/mandate] --> SD
    CAPFW[shared-capability framework] --> SD
    SD[(shared-domain<br/>canonical contracts<br/>NO framework imports)]
```

## 3. Low-Level Block Diagram

```mermaid
flowchart TB
    subgraph envelope["package …shared.domain.envelope"]
        CE["CanonicalEnvelope (record)<br/>transactionId, schemaVersion, source,<br/>type, orgId, applicationRef, correlationId,<br/>payloadRef, payloadContentType, occurredAt …"]
        SS["SourceSystem (enum)<br/>SFDC | DIGITAL"]
        CE --> SS
    end
    subgraph capability["package …shared.domain.capability"]
        CRQ["CapabilityRequest (record)<br/>journeyInstanceId, correlationId, capabilityKey,<br/>nodeId, payload, collectedResults,<br/>operation, idempotencyKey"]
        CRS["CapabilityResponse (record)<br/>journeyInstanceId, correlationId, nodeId,<br/>capabilityKey, status, result, errorClass<br/>+ ok(req,out) / error(req,errorClass)"]
        CST["CapabilityStatus (enum)<br/>OK | ERROR"]
        ECL["ErrorClass (enum)<br/>TRANSIENT | PERMANENT"]
        CTP["CapabilityTopics (util)<br/>cap.&lt;key&gt;.request.v1 / .response.v1"]
        CRS --> CST
        CRS --> ECL
        CRQ -. derives topics .-> CTP
    end
    SDM["SharedDomain (module anchor / marker)"]
```

## 4. Flow Diagram

How the contract types flow edge → engine → capability and back:

```mermaid
sequenceDiagram
    participant Edge as Channel Edge
    participant Eng as Orchestration Engine
    participant Cap as Capability
    Edge->>Eng: CanonicalEnvelope (source=SFDC|DIGITAL) on origination topic
    Note over Eng: identical envelope shape regardless of door
    Eng->>Cap: CapabilityRequest on cap.<key>.request.v1<br/>(journeyInstanceId, nodeId, payload, operation, idempotencyKey)
    Cap-->>Eng: CapabilityResponse on cap.<key>.response.v1<br/>(status=OK, result) or (status=ERROR, errorClass)
    Note over Eng: correlates by journeyInstanceId + nodeId,<br/>stores result, advances DAG / applies retry policy
```

## 5. Key Types / Classes & Files

| File | Role |
| --- | --- |
| `src/main/java/.../envelope/CanonicalEnvelope.java` | The canonical origination envelope — THE shared contract between every edge and the engine; S3 claim-check via `payloadRef`. |
| `src/main/java/.../envelope/SourceSystem.java` | Enum of the channel a request entered through: `SFDC`, `DIGITAL`. |
| `src/main/java/.../capability/CapabilityRequest.java` | THE CAPABILITY CONTRACT (request half) — authoritative wire shape the engine emits per task node. |
| `src/main/java/.../capability/CapabilityResponse.java` | THE CAPABILITY CONTRACT (response half) — with `ok(...)` / `error(...)` factories echoing routing identity. |
| `src/main/java/.../capability/CapabilityStatus.java` | Terminal outcome enum: `OK` / `ERROR`. |
| `src/main/java/.../capability/ErrorClass.java` | Failure classification for the engine's retry policy: `TRANSIENT` / `PERMANENT`. |
| `src/main/java/.../capability/CapabilityTopics.java` | Single topic-naming convention: `cap.<key>.request.v1` / `cap.<key>.response.v1`. |
| `src/main/java/.../SharedDomain.java` | Module anchor / marker class. |

## 6. Interfaces / Dependents

- **Depended on by:** every edge (`sfdc-ingress-edge`, `digital-partner-edge`), the orchestration engine, every capability (`echo`, `mandate`, …), and `shared-capability` (which re-exports it via `api(project(":shared:shared-domain"))`).
- **What they import:** `CanonicalEnvelope` + `SourceSystem` (edges/engine), and `CapabilityRequest` / `CapabilityResponse` / `CapabilityStatus` / `ErrorClass` / `CapabilityTopics` (engine + capabilities + framework).
- **Outbound:** none — pure data types, no runtime dependencies, no framework imports.

## 7. Configuration & How to Run / Use

This is a **library**, not a runnable service — there is no server port and nothing to start. Consume it as a Gradle dependency:

```kotlin
dependencies {
    implementation(project(":shared:shared-domain"))
}
```

Group/version: `com.idfcfirstbank` (Java 21, Spring Boot 3.4.5 toolchain). Build via `idfc.library-conventions`.
