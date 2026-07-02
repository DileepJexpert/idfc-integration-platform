# IDFC Integration Platform

A Gradle multi-project monorepo for IDFC's integration platform. **Slice 1**
implements the **SFDC ingress edge** for real; every other module is an empty,
runnable stub. Stack: **Java 21**, **Spring Boot 3.4.5**, **Aerospike** (the only
datastore the org has — idempotency store), **Kafka**, Testcontainers,
Resilience4j, Micrometer + OpenTelemetry. Everything beyond the edge (Salesforce,
Hydra, Kong, S3, FinnOne) is **mocked behind OUT ports** — Slice 1 needs **zero
real external URLs**.

> Specs live in [`docs/`](docs/): the punch list (`SLICE1_PUNCH_LIST.md`, the
> build gate — wins on any conflict), the integration registry, and the kickoff
> prompt. Read the punch list first.

## Production gates (tracked — mocked/asserted locally, real before prod)

Deliberate local simplifications. Each is a **gate**: it must be replaced by the
real thing before production, and nothing in the codebase may quietly assume
otherwise.

- **Secrets** — env-var tokens (fail-closed at startup; dev values only in
  `application-local.yml`/compose). Production: Vault.
- **Edge auth** — single static token per edge/partner (`X-Auth-Token`,
  `X-Partner-Token`, `X-Registry-Token`). Production: Hydra + Kong two-token.
- **Actor identity** — the registry's `X-User-Id` header is **asserted, not
  authenticated**: anyone holding the service token can claim any user id. The
  server-side maker-checker rules are real; the identity they act on is not yet.
  Production: SSO/OIDC-verified identity propagated to the registry.
- **Transport security** — plain HTTP/PLAINTEXT Kafka locally. Production: TLS
  everywhere + Kafka ACLs.
- **SFDC/FinnOne/Karza/S3** — mocked behind OUT ports (see the integration
  registry for per-system contracts).

## Tracked follow-ups (deliberate stopgaps — labelled, not forgotten)

- **`Inbound_Wrapper` → `loan-origination`** (engine `type-to-journey`): SFDC's
  CASA account-creation SVCNAME deliberately routes to the loan-origination DAG
  as the end-to-end plumbing demo. Pre-A2 this happened invisibly via the
  empty-map fallback; now it is this explicit row. **Swap to a real
  account-creation journey when one is authored** — a one-row config change.
- **S3 claim-check resolution**: capabilities receive the envelope's identity
  fields; resolving the real applicant payload from the `payloadRef` S3 pointer
  is still mocked (see `docs/DEMO.md`).

## Build conventions (house style)

- **Kotlin DSL** Gradle scripts (`*.gradle.kts`) throughout; shared logic lives in
  the `buildSrc` convention plugins (`idfc.java-conventions`,
  `idfc.library-conventions`, `idfc.spring-boot-app-conventions`).
- **group** `com.idfcfirstbank`; Java base package `com.idfcfirstbank.integration.*`.
- Spring Boot apps use `org.springframework.boot` + `io.spring.dependency-management`;
  `shared/*` libraries use `java-library`.
- **Dependency management:** pure Spring — the public
  `org.springframework.boot:spring-boot-dependencies` BOM (plus the Testcontainers
  and OpenTelemetry BOMs) pins versions; artifacts resolve from Maven Central.

## Repository map

```
buildSrc/                 Gradle convention plugins (java / library / spring-boot-app)
docker-compose.yml        Local runtime: Aerospike + Kafka ONLY (everything else is mocked)
infra/aerospike/          Single-node Aerospike config for docker-compose
shared/
  shared-domain           library stub (canonical domain types — later slices)
  shared-observability    library stub (OTel/Micrometer helpers)
platform/
  platform-idempotency    app stub — FUTURE home of the extracted Aerospike store
  platform-auth           app stub — Hydra + Kong two-token auth
  platform-config         app stub — org-config-as-data store
  platform-messaging      app stub — shared Kafka helpers
edges/
  sfdc-ingress-edge       *** IMPLEMENTED FOR REAL (Slice 1) ***
  sfdc-egress-edge        app stub
capabilities/             app stubs: kyc, bureau, scoring, lending-origination,
                          lending-servicing, customer-party, payments
orchestration/
  origination-journey     app stub
```

### Inside `edges/sfdc-ingress-edge` (hexagonal)

```
domain/model              framework-free aggregate + value objects (IdempotencyRecord,
                          CanonicalEnvelope, RecordStatus, ApplicationKey, Decision, ...)
domain/port               OUT ports — the ONLY way callers reach externals
domain/exception          permanent vs transient error taxonomy (C2)
application                DedupeService, SfdcIngressService, DecisionService, Normalizer
                          (Spring-free, unit-testable)
adapter/in/rest           thin REST inbound + decision callback
adapter/in/kafka          FinnOne backpressure harness consumer (bounded to N)
adapter/out/aerospike     AerospikeIdempotencyStore (CREATE_ONLY + generation CAS + TTL)
adapter/out/kafka         KafkaMessagePublisher (canonical envelope + headers)
adapter/out/mock          profile-switched mocks: S3, org-config, auth, SFDC push, FinnOne
config                    Spring wiring + @ConfigurationProperties
parity                    parity oracle harness (§F) + fixtures under test resources
```

The domain depends only on ports; no caller imports `AerospikeIdempotencyStore`,
so extracting it to `platform-idempotency` later is a **move, not a rewrite**.

## How to run

### Build the whole repo (Docker-free)

```bash
./gradlew build          # compiles every module + runs the fast unit suite
```

### Run the edge locally

```bash
docker compose up -d                 # starts Aerospike + Kafka
./gradlew :edges:sfdc-ingress-edge:bootRun
# health: curl localhost:8080/actuator/health
```

### The correctness gate + integration tests (need Docker)

```bash
./gradlew :edges:sfdc-ingress-edge:integrationTest
```

This runs the Testcontainers-backed tests against a **real Aerospike** and a
**real Kafka** — it is intentionally separate from `build` so the default build
stays fast and Docker-free.

> **Docker in a fresh sandbox:** if the daemon isn't running, start it with
> `dockerd &` first. The `integrationTest` task already points Testcontainers at
> `/var/run/docker.sock`, disables Ryuk, and pins the Docker API version, so no
> other setup is needed. On a standard CI/dev box with Docker running, none of
> this applies.

## Demo scenarios

With the edge running (`bootRun`) and `docker compose up`:

```bash
TOKEN="dev-token"
URL=localhost:8080/api/v1/sfdc

# 1. New application -> normalized, routed to orig.sfdc.pl.v1, fast-ACK (200, ACK_PROCESSED)
curl -s -XPOST $URL/notifications -H "X-Auth-Token: $TOKEN" -H 'Content-Type: application/json' -d '{
  "notificationId":"ntf-1","correlationId":"corr-a","sfdcRecordId":"rec-1",
  "applicationRef":"APP-1","orgId":"ORG1","type":"PERSONAL_LOAN","payload":{"amount":500000}}'

# 2. Resend, SAME notificationId, NEW correlationId -> idempotent, no second publish (ACK_DUPLICATE_INFLIGHT)
curl -s -XPOST $URL/notifications -H "X-Auth-Token: $TOKEN" -H 'Content-Type: application/json' -d '{
  "notificationId":"ntf-1","correlationId":"corr-b","sfdcRecordId":"rec-1",
  "applicationRef":"APP-1","orgId":"ORG1","type":"PERSONAL_LOAN","payload":{"amount":500000}}'

# 3. Resend, NEW notificationId, SAME application -> composite fallback blocks a double-book
curl -s -XPOST $URL/notifications -H "X-Auth-Token: $TOKEN" -H 'Content-Type: application/json' -d '{
  "notificationId":"ntf-2","correlationId":"corr-c","sfdcRecordId":"rec-1",
  "applicationRef":"APP-1","orgId":"ORG1","type":"PERSONAL_LOAN","payload":{"amount":500000}}'

# 4. Unknown business line -> config refresh + recheck -> ACK + DLQ (ACK_DLQ_PERMANENT)
curl -s -XPOST $URL/notifications -H "X-Auth-Token: $TOKEN" -H 'Content-Type: application/json' -d '{
  "notificationId":"ntf-3","correlationId":"corr-d","sfdcRecordId":"rec-9",
  "applicationRef":"APP-9","orgId":"ORG1","type":"NO_SUCH_LINE","payload":{}}'

# 5. Deliver a decision -> CAS into DECIDED pushes back to SFDC EXACTLY once (C1)
curl -s -XPOST $URL/decisions -H "X-Correlation-Id: corr-e" -H 'Content-Type: application/json' -d '{
  "notificationId":"ntf-1","outcome":"APPROVED","applicationId":"LN-1","terms":"{}"}'
# repeat #5 -> {"pushed":false} (already decided; no second push)
```

## How the punch-list requirements are met (DoD)

| Requirement | Where |
|---|---|
| Idempotency first; atomic insert-if-absent (CREATE_ONLY) | `AerospikeIdempotencyStore.insertIfAbsent` |
| Every status transition is CAS (EXPECT_GEN_EQUAL) — C4 | `AerospikeIdempotencyStore.compareAndSetStatus` |
| Composite dedup key (primary `notificationId` + fallback `sfdcRecordId+applicationRef`) | `DedupeService`, `ApplicationKey` |
| `correlationId` = trace only, never a dedup input; `resendOf` logged | `DedupeService`, `SfdcIngressService.logResend` |
| The 4 dedupe paths; only CAS into DECIDED pushes back — C1 | `DedupePath`, `DecisionService.applyDecision` |
| ACK boundary — C2 (permanent ACK+DLQ; transient no-ACK; unclassified=transient) | `SfdcIngressService`, `EdgeDisposition`, exception taxonomy |
| Unknown-org/type refresh-and-recheck — C2 | `SfdcIngressService.resolveRoutingWithRecheck` / `ensureKnownOrgWithRecheck` |
| C3 retry ceiling; C5 edge poison/redelivery breaker | `EdgePolicies`, `SfdcIngressService.onTransientFailure` |
| Native TTL ≥ SFDC retry window (30d default, config-driven) | `AerospikeProperties`, store create policy |
| Seed routing map as config-as-data (§E) | `application.yml` `idfc.edge.routing`, `SeededOrgConfigAdapter` |
| FinnOne backpressure harness (cap N, 10x burst) — §G | `FinnOneBackpressureConsumer`, `MockFinnOneStoredProc`, `FinnOneBackpressureBurstIT` |
| Parity oracle vs recorded fixtures + resolved-payload equivalence — §F | `parity/ParityOracle`, `ParityOracleTest`, fixtures in test resources |
| THE correctness test (concurrent identical id → one winner; CAS → one push) | `AerospikeIdempotencyStoreConcurrencyIT` |

## Aerospike ops confirm (production)

Run the idempotency namespace as a **strong-consistency (SC)** namespace on a real
cluster so `CREATE_ONLY` + generation-check stay atomic under partition / cluster
reconfiguration. The local single-node config (`infra/aerospike/aerospike.conf`)
is for development only. Set the record TTL to **≥ SFDC's confirmed maximum
retry/redelivery window** — a key that expires before SFDC can still resend is a
silent double-book.

## Open confirms (tracked, non-blocking to build)

- **A1 — id stability on resend.** Owner: SFDC/Apex. Is `notificationId`
  reused/stable on resend, and is `correlationId` new per request? The composite
  fallback key makes either answer non-breaking, but A1 still **gates sign-off**,
  and the real value sets the store TTL. `applicationRef` MUST be a payload field
  that is **stable across a resend of the same business application**; if a future
  payload lacks one, that is a STOP/flag — do not invent it.
- **A2 — screen behaviour.** Default assumed record-driven; confirm. Does not
  affect Slice 1 (servicing is a stub) — no system-of-record assumption is encoded.

## Scope fence

Only `edges/sfdc-ingress-edge` is implemented. No real capabilities
(KYC/Bureau/Scoring/Lending), no real FinnOne integration, no journey logic —
those are later slices. Adding a business line or org is a **config** change.
