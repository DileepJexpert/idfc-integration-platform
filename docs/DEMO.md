# Full-flow demo — wiring, run, and checklist

End-to-end origination across the platform: a REST call to the edge fans out
through Kafka to the engine and the capability fleet, branches on the credit
decision, books the loan on approval, and emits a decision.

```
curl ─▶ sfdc-ingress-edge ──orig.sfdc.pl.v1──▶ origination-journey (ENGINE)
                                                   │  walks the locked DAG
            cap.<key>.request.v1  ◀───────────────┤
                ├─▶ customer-party ─▶ Posidex(mock)
                ├─▶ kyc            ─▶ NSDL(mock)
                ├─▶ bureau         ─▶ CIBIL(mock)   {bureauScore}
                ├─▶ scoring        ─▶ FICO(mock) + DecisionRule  {decision}
                └─▶ lending-origination ─▶ FinnOne(Oracle SP, JDBC)  {loanId}
            cap.<key>.response.v1 ─────────────────▶ engine advances
                                                   │
                                       orig.decision.v1  (APPROVED+loanId | REJECTED)
```

## Topic wiring

| Topic | Producer | Consumer |
|---|---|---|
| `orig.sfdc.pl.v1` (+ lap/bl/commercial) | sfdc-ingress-edge | origination-journey (`idfc.engine.origination-topics`) |
| `cap.<key>.request.v1` | engine | the matching capability (`CapabilityTopics.request`) |
| `cap.<key>.response.v1` | each capability | engine (pattern `cap\..*\.response\.v1`) |
| `orig.decision.v1` | engine | *(see seam #1)* |

PERSONAL_LOAN routes to `orig.sfdc.pl.v1`, which is exactly the engine's default
origination topic — confirmed aligned. Capability keys match the real module
names and the locked journey contract (`scoring`, not `scoring-decisioning`).

## Run

```bash
./demo.sh infra       # infra ONLY (Aerospike+Kafka+mocks) — pull-only, no build
./demo.sh up          # infra (wait healthy) -> ./gradlew bootBuildImage -> services
# layers also stand alone:
#   docker compose -f docker-compose.infra.yml up -d      # infra
#   ./gradlew bootBuildImage                               # build idfc/* images
#   docker compose -f docker-compose.services.yml up -d    # services (infra must be up)
./demo.sh approved    # high-score application -> APPROVED + loanId
./demo.sh rejected    # applicationRef contains LOW -> CIBIL 540 -> REJECTED
./demo.sh decisions   # tail orig.decision.v1
./demo.sh burst       # 10x burst on the edge (FinnOne stays bounded, backlog drains)
./demo.sh down
```

Branch driver: the demo keys high/low on **applicationRef** (CIBIL mock returns
780 by default, 540 when applicationRef matches `/LOW/i`). See seam #2 for why
this is applicationRef and not the inline PAN.

## Seams found during wiring (noted, not silently bent)

1. **Engine decision → edge push-back.** The engine publishes the final decision
   to `orig.decision.v1`. The edge today exposes an HTTP decision endpoint
   (`POST /api/v1/sfdc/decisions`), not a Kafka consumer, so the loop back into
   the edge's SFDC push-back is **not yet closed in code** — a scoped follow-up
   (add a thin decision consumer in the edge that calls `DecisionService`, keyed
   by notificationId). The full-flow proof asserts on `orig.decision.v1`, the
   engine's authoritative output.
2. **Applicant data via S3 claim-check.** The edge's canonical envelope carries a
   `payloadRef` (S3 claim-check), not the inline PAN, so capabilities can't read
   the PAN in the live path. The engine now always surfaces the envelope's
   identity fields (applicationRef, type, …) to capabilities, and the demo
   branches on `applicationRef`. Resolving the real applicant payload from S3 is
   a documented follow-up.
3. **kyc node in the contract.** The locked journey includes a `kyc` task, so the
   demo runs a `kyc` capability + NSDL mock even though the original prompt called
   KYC optional. (The contract is authoritative.)

## What's verified now vs Docker-gated

- ✅ **Verified, Docker-free:** `./gradlew build` is green across the whole
  monorepo — every module compiles, all unit suites pass, and all bootable jars
  assemble (so `bootBuildImage` can produce the demo images). `:full-flow-it:test`
  wires the REAL engine + the five REAL capability services over an in-memory bus
  and proves both outcomes (APPROVED+loanId, REJECTED) through the real
  DecisionRule, bureau score, and booking. `docker compose config` validates the
  full topology.
  - Note: the edge's `adapter/out/*` (Aerospike idempotency store, Kafka
    publisher, and the S3/org-config/auth/SFDC/FinnOne mocks) was reconstructed
    after an un-anchored `.gitignore` `out/` rule had silently dropped every
    `adapter/out/` directory from earlier commits (now fixed → `/out/`).
- ⏳ **Docker-gated (run where Docker is available):** the live `docker compose`
  stack (Kafka + Aerospike + 7 services + WireMock + Oracle-XE) via `./demo.sh`.
  The per-module `integrationTest` tasks (Testcontainers Kafka, tag `integration`)
  are excluded from the fast build.

## Tomorrow's testing checklist

- [ ] `docker compose up` → all services + mocks healthy (`docker compose ps`).
- [ ] Approved path: `./demo.sh approved` → loanId returned, decision on `orig.decision.v1`.
- [ ] Rejected path: `./demo.sh rejected` → REJECTED, no booking.
- [ ] Resend/dedupe still works at the edge (Slice-1 behavior intact).
- [ ] `./demo.sh burst` → queue depth grows, FinnOne concurrency bounded, drains.
- [ ] DAG Designer renders this exact journey (config-not-code); capability keys match.
- [ ] `:full-flow-it:test` green (this is the CI gate for the choreography).
