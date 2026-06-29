# Slice 1 — Claude Code Opening Prompt (Aerospike, final — paste this)

> Paste the block below as your FIRST message to Claude Code, after putting these spec files in `docs/`:
> SLICE1_PUNCH_LIST.md, SLICE1_SFDC_EDGE_FLOW.md, CLAUDE.md, SLICE1_CLAUDE_CODE_KICKOFF.md,
> SLICE1_CORRECTIONS.md, IDFC_INTEGRATION_REGISTRY.md, IDFC_TARGET_ARCHITECTURE_SOLUTION.md.
> Datastore is AEROSPIKE (the only datastore the org has) — NOT Postgres.

---

You are scaffolding a Gradle multi-project monorepo for the IDFC integration platform and implementing its
first module for real. Read these specs in docs/ first, in order: SLICE1_PUNCH_LIST.md (the build gate; wins
on any conflict), SLICE1_SFDC_EDGE_FLOW.md (the flow), CLAUDE.md (the spec), SLICE1_CLAUDE_CODE_KICKOFF.md
(the structure), SLICE1_CORRECTIONS.md (mandatory corrections to all of the above), and
IDFC_INTEGRATION_REGISTRY.md (what external systems exist and how adapters are wired — note Slice 1 needs NO
real external URLs; everything beyond the edge is mocked).

PART A — SCAFFOLD THE WHOLE REPO (all modules as empty, runnable stubs):
- Confirm Java 21 + Spring Boot 3.4.5 are the intended pinned versions and resolve cleanly before scaffolding;
  if either is unavailable/superseded in a way that breaks resolution, flag it and propose the nearest stable
  pin rather than silently changing it.
- Root: settings.gradle including every module in the kickoff section 3; root build.gradle with Java 21
  toolchain, Spring Boot 3.4.5 + dependency management, versions in gradle.properties, a shared convention
  applied to all modules (common deps, test, testcontainers, micrometer, otel, container-image build).
- Create EVERY module under edges/ capabilities/ orchestration/ platform/ shared/ exactly as in the kickoff
  section 2.
- Each STUB module = own build.gradle (applies conventions) + a Spring Boot Application class that starts and
  serves /actuator/health + one placeholder test. Nothing more. The whole repo must compile; ./gradlew build
  must pass.
- Add docker-compose.yml (AEROSPIKE + kafka), README.md (repo map + how to run), CODEOWNERS placeholder, and
  put the spec files in docs/.

PART B — IMPLEMENT edges/sfdc-ingress-edge FOR REAL (production-grade), per the specs. Hexagonal; domain has
NO framework imports; ALL externals (Salesforce, Hydra, Kong, S3, FinnOne, SFDC-response) are behind OUT
ports and MOCKED via Spring profiles for local run. NO real external URLs are needed or used in Slice 1 — the
real adapters and their config-driven endpoints are later slices (see IDFC_INTEGRATION_REGISTRY.md).

NON-NEGOTIABLE (from the punch list — hard requirements):
- IDEMPOTENCY FIRST. AEROSPIKE store (the only datastore we have — use it, not Postgres):
  - atomic insert-if-absent via RecordExistsAction.CREATE_ONLY so two concurrent identical requests yield
    exactly ONE winner;
  - every status transition is compare-and-set via GenerationPolicy.EXPECT_GEN_EQUAL (read generation, write
    only if generation unchanged), never read-then-write;
  - confirm the namespace behaves atomically for CREATE_ONLY + generation-check under cluster config (note in
    README as an ops confirm).
- COMPOSITE DEDUP KEY: primary notificationId + fallback (sfdcRecordId + applicationRef), both from day one —
  the fallback is load-bearing (a user resend after a perceived failure arrives with a NEW notificationId but
  the SAME application and must NOT double-book). applicationRef MUST be derived from an SFDC-payload field
  that is STABLE across a resend of the same business application. If no such stable field exists in the
  payload, STOP and flag — do not invent it or derive it from anything request-scoped (timestamps,
  correlationId, message id). The composite key's double-book protection depends entirely on this stability.
- correlationId = TRACE ONLY, never a dedup input; store the original on the record; log a resend's
  correlationId as resendOf=<original>; thread it through Kafka headers + the push-back.
- The FOUR dedupe paths (new / in-flight / decided / failed); only the CAS transition INTO DECIDED triggers
  the push-back; resend reads never push.
- ACK boundary (C2): ACK+DLQ only on provably-permanent errors; do NOT ACK on possibly-transient (let SFDC
  redeliver); on unknown-org -> refresh config, re-check once, THEN classify; unclassified defaults to transient.
- Edge poison breaker (C5): bound redeliveryCount per dedup key. C2<->C5 handoff: a failure is treated as
  TRANSIENT (do NOT ACK; let SFDC redeliver) UNTIL redeliveryCount for that key reaches N. On reaching N it is
  RECLASSIFIED as permanent-poison -> ACK + DLQ-as-poison + alert. redeliveryCount increments once per
  redelivery of the same dedup key and is persisted on the idempotency record. N is config (default N=5).
- Edge is THIN: authenticate, validate, dedupe, normalize -> canonical envelope, route by (source=SFDC,
  type=business-line), fast-ACK. NO business logic in the edge.
- Backpressure harness: fast-ACK -> Kafka; a FinnOne-bound consumer with a BOUNDED pool (max N concurrent)
  against a MOCK stored proc; a 10x burst must manifest as Kafka queue depth, with FinnOne concurrency never
  exceeding N, and the backlog draining to zero after the burst.
- Harvest contracts, not anti-patterns: secrets via config/Vault placeholder (never inlined), actuator locked
  down, no PII in logs, org/routing as config data.
- The concurrency test MUST run against a REAL Aerospike (Testcontainers Aerospike image), not an in-memory
  fake — CREATE_ONLY atomicity is the thing under test and an in-memory map would pass falsely.

STACK: Java 21, Spring Boot 3.4.5, Gradle, AEROSPIKE (idempotency store — the only datastore we have), Kafka,
Testcontainers, Resilience4j, OpenTelemetry, Micrometer. Runs fully locally via docker-compose (Aerospike +
Kafka) with everything beyond the edge mocked.

SCOPE FENCE: implement ONLY edges/sfdc-ingress-edge. All other modules stay empty stubs. Do NOT implement real
capabilities (KYC/Bureau/Scoring/Lending), real FinnOne integration, or journey logic — those are later slices.
If a task drifts into a real capability, STOP and flag it.

BUILD ORDER (follow strictly; observe the two STOP gates):
1. PART A scaffold — show me settings.gradle, root build.gradle, gradle.properties, the convention plugin,
   docker-compose.yml (Aerospike + Kafka), and ONE example stub module. Confirm ./gradlew build passes for the
   whole repo. STOP for my OK.
2. Domain model + ports for sfdc-ingress-edge.
3. AEROSPIKE idempotency store: the record (punch list section D fields, incl. redeliveryCount) in an Aerospike
   set + atomic insert-if-absent (CREATE_ONLY) + CAS via generation-check. Use NATIVE Aerospike TTL for expiry:
   TTL MUST exceed SFDC's max retry/redelivery window (a key expiring before SFDC can still resend = silent
   double-book); default 30 days, config-driven — no scheduled purge job needed, Aerospike expires records
   natively. The store lives INSIDE sfdc-ingress-edge but is accessed ONLY through IdempotencyStorePort — no
   caller touches the concrete AerospikeIdempotencyStore — so the later extraction to platform/platform-
   idempotency is a move, not a rewrite. Then WRITE AND PASS the concurrency test (concurrent identical
   notificationId -> exactly one winner) against a REAL Aerospike via Testcontainers BEFORE anything else. This
   is the correctness gate — STOP for my OK after it passes.
4. DedupeService (the 4 paths + composite key resolution).
5. Thin edge: REST inbound, validate, normalize, route (seed map from config), fast-ACK.
6. Kafka publisher + canonical envelope + S3 claim-check (mock blob).
7. FinnOne backpressure harness + 10x burst test.
8. Error / ACK / DLQ / poison paths (C2, C3, C5).
9. Parity oracle harness — compares against RECORDED Mule fixtures (captured request->canonical-envelope
   pairs), NOT a live Mule instance (Mule is not in docker-compose). Provide a fixtures format and a few sample
   fixtures; parity = resolved-payload + routing-decision + dedup-verdict match within the section F allowlist.
10. README + run instructions + demo scenarios.

Ask me if any spec is ambiguous rather than guessing — but the punch list LOCKED items are not negotiable.
Build so later small changes (business-line codes, the real SFDC retry-window TTL, servicing details) are
CONFIG or single-point changes, not rewrites.

Note on A2 (do not treat as settled): "FinnOne is the single lending SoR / LMS is a wrapper" is an OPEN
CONFIRM, not a decision — it does not affect Slice 1 (servicing is a stub), so do not encode any assumption
about servicing's system-of-record anywhere in this build.

Start with step 1.
