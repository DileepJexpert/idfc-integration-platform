# Bureau Capability — Build Doc (Slice 2) + the reusable CAPABILITY PATTERN

| | |
|---|---|
| **Slice** | 2 — the first capability; the TEMPLATE every capability reuses |
| **Repo** | same monorepo `idfc-integration-platform`, module `capabilities/bureau-capability` (currently a stub) |
| **Precondition** | Slice 1 (SFDC edge) green; idempotency pattern proven |
| **Builds against** | IDFC_microservice_consolidation_context.md §7B (Bureau boundary LOCKED), SKILL.md, this doc |

> What Bureau is: ONE capability that fetches credit-bureau data (CIBIL / Multi-Bureau / Commercial Bureau),
> replacing the **4-5x duplicated** bureau-fetch code today scattered across scorecard-analyser,
> cibil-management, dl-eligibility, and the fico-* services (each with its OWN CIBIL HTTP client). This is
> the single highest-value consolidation. It is also the FIRST capability, so it establishes the pattern
> all other capabilities copy.

---

## PART 1 — THE CAPABILITY PATTERN (reusable; read once, applies to every capability)

Every capability is built the same way. Bureau is the worked example; KYC/Lending/Payments fill the same shape.

### 1.1 Shape (hexagonal, like the edge)
```
capabilities/<name>-capability/
  domain/
    model/        # capability's OWN concepts + value objects (framework-free)
    port/in/      # the capability's use-case interface(s)
    port/out/     # one OUT port per external system the capability needs (vendor, core, store)
    service/      # the capability logic (pure; the "superset" of absorbed behavior)
  adapter/
    in/rest/      # how the capability is invoked (REST; later, Kafka from orchestration)
    in/kafka/     # (when orchestration drives it async)
    out/<vendor>/ # ONE adapter per external system (CIBIL, Multi-Bureau, ...) — profile-switched mock/real
    out/cache/    # if the capability caches (Aerospike)
  config/
```

### 1.2 The five rules of a capability
1. **One business function, one owner, owns its data.** Bureau owns "fetch + normalize bureau data."
2. **Vendors are ADAPTERS, never the capability.** CIBIL, Experian, Equifax, Commercial Bureau, the internal
   scorecard infra = adapters behind OUT ports. Adding a bureau = a new adapter + config, not a new service.
3. **Capability is invoked; it never calls another capability.** Orchestration composes; Bureau just answers
   "give me bureau data for this applicant." (For Slice 2, invoked via REST directly; later via orchestration.)
4. **Canonical contract in, canonical contract out.** The capability exposes ONE clean API (the superset of
   what the absorbed services needed); adapters translate vendor-specific shapes to/from canonical.
5. **Built as the SUPERSET of the absorbed services, parity-proven.** Nothing the old duplicated calls did is
   lost; the capability returns identical data; anti-patterns (inlined keys, etc.) are cleaned on the way in.

### 1.3 The capability build method (every slice)
```
ABSORB      → the boundary (context file) says which services' bureau-logic this capability absorbs
HARVEST     → run the local-LLM SERVICE_ANALYSIS / read OpenAPI/DTOs on those services to get the REAL
              request/response fields + the REAL vendor endpoints/auth (config keys, not values)
BUILD CORE  → the capability as the SUPERSET of those behaviors, behind ONE canonical API
ADAPTERS    → one per vendor, mock (local) + real (prod, URL from config); mock mirrors the real contract
PARITY      → run the new capability beside the old duplicated calls; same applicant → same bureau data
              (within an allowlist of legit diffs); parity gate must pass before cutover
CUTOVER     → strangler: route ONE caller (e.g. dl-eligibility) to the new Bureau capability; verify; then
              the next caller; then retire the old per-service bureau code
```

### 1.4 Platform reuse (the first extraction)
This is the slice where **platform-idempotency gets extracted** from the edge (its first reuse). If the
capability needs dedup/caching with the same Aerospike CAS+TTL semantics, it depends on the
`platform-idempotency` module (moved out of the edge, accessed via the same port). Move, not rewrite.

---

## PART 2 — BUREAU SPECIFICS

### 2.1 What Bureau absorbs (from context §7B — LOCKED)
Today every credit service has its OWN bureau call. Bureau extracts and unifies them:
- `scorecard-analyser` — 2 CIBIL APIs (`d2c-lentra-sys/.../records`, `multi-bureaux-applicant-sys/.../records/applicant`)
- `cibil-management` — direct CIBIL (`multibureauapplication`, Basic auth) + Bureau Score
- `dl-eligibility-check` — Multi-Bureau (`services.mBUrl`) + Commercial Bureau (CBA)
- the fico-* services — Bureau Scorecard (BIL) via internal scorecard infra (`scorecard.dev-infinity`)

These callers STOP calling bureaus directly and call the Bureau capability instead. The internal scorecard
infra (`scorecard.dev-infinity.idfcfirst.com`) — which already partially fronts the bureaus — becomes
Bureau's primary backing adapter; direct CIBIL/Multi-Bureau become additional adapters.

### 2.2 Bureau is STATELESS data-fetch (important boundary fact)
Per §7B, the bureau-fetch is pure data retrieval — NO business decisioning (that lives in
Scoring/Decisioning, a different capability). So Bureau:
- fetches raw bureau data, normalizes it to a canonical bureau response, returns it
- does NOT score, decide eligibility, or apply rules — it is a DATA capability
- MAY cache (bureau pulls are rate-limited/charged) — optional, Aerospike, short TTL, dedup on applicant+purpose

### 2.3 Canonical API (the superset)
```
POST /api/v1/bureau/fetch
  request  (canonical, superset of what absorbed services sent):
    { applicant: { firstName, middleName, lastName, dob, pan, aadharRef?, addresses[],
                   phone, email, gstDetails?, businessDetails? },
      bureauTypes: [ "CIBIL" | "MULTI_BUREAU" | "COMMERCIAL" | "BUREAU_SCORE" ],   // which to pull
      purpose: "ELIGIBILITY" | "LIMIT_ENHANCEMENT" | "UNDERWRITING",                // audit/consent
      consentRef: string }                                                          // DPDP/consent
  response (canonical, normalized across vendors):
    { bureauResults: [ { type, score?, report{...normalized...}, raw?, fetchedAt, source } ],
      status, correlationId }
```
Adapters translate this canonical shape to/from each vendor's specific format. Callers never see vendor shapes.

### 2.4 OUT ports & adapters
| OUT port | Adapter (real) | Config keys (names; values in Vault/env) | Mock (local) |
|---|---|---|---|
| `CibilBureauPort` | CibilAdapter | `bureau.cibil.url`, auth→Vault (Basic) | MockCibilAdapter |
| `MultiBureauPort` | MultiBureauAdapter | `bureau.multibureau.url`, auth→Vault | MockMultiBureauAdapter |
| `CommercialBureauPort` | CommercialBureauAdapter (CBA) | `bureau.commercial.url` | MockCommercialBureauAdapter |
| `ScorecardInfraPort` | ScorecardInfraAdapter (BIL) | `scorecard.base-url` (scorecard.dev-infinity) | MockScorecardInfraAdapter |
| `BureauCachePort` (optional) | AerospikeBureauCache (reuse platform-idempotency semantics) | aerospike config | in-memory |

**This is the slice where the first REAL external auth appears** (CIBIL Basic auth). So `platform-auth`'s
config-driven credential handling gets its first real use — secrets via Vault, never inlined (the anti-pattern
the absorbed services had — clean it here).

### 2.5 What Bureau does NOT do (scope fence)
- NO scoring/decisioning (that's Scoring/Decisioning capability — a different slice).
- NO KYC (CKYC/CERSAI ≠ bureau — different capability).
- NO orchestration (it's invoked; for Slice 2, invoked via REST directly by a test harness or one caller —
  the real orchestration engine is slice 4).
- Does NOT call other capabilities.

---

## PART 3 — BUILD ORDER (Slice 2)

1. **Fill the `capabilities/bureau-capability` module** (it's a stub today): real build.gradle (conventions),
   hexagonal package structure, Spring Boot app.
2. **Domain + ports** — canonical Bureau request/response model; OUT ports (Cibil, MultiBureau, Commercial,
   ScorecardInfra, optional Cache); IN port (`FetchBureauData`).
3. **HARVEST contracts** — before writing adapters, run the local-LLM SERVICE_ANALYSIS (or read OpenAPI/DTOs)
   on scorecard-analyser, cibil-management, dl-eligibility to get the REAL vendor request/response fields per
   bureau. Map each to/from the canonical shape. (This is the build-time field harvest — design says which
   services, code gives the fields.)
4. **Bureau service (core)** — fan out to the requested `bureauTypes` (parallel), normalize each vendor
   response to canonical, merge, return. Pure; unit-tested with mock adapters. (Mirrors what the old
   orchestrators did — but ONCE, shared.)
5. **Adapters** — mock first (local profile, canned canonical responses), then real (prod, vendor shape ↔
   canonical, URL/auth from config). Mock must mirror real contract.
6. **Optional cache** — Aerospike, dedup on (applicant-key + purpose + bureauType), short TTL; reuse the
   platform-idempotency CAS/TTL semantics (this is the extraction-reuse point).
7. **platform-idempotency extraction** — if caching is used, MOVE the Aerospike store from the edge into
   `platform/platform-idempotency` and have both the edge and Bureau depend on it via the port. Verify the
   edge's tests still pass (move, not rewrite).
8. **PARITY harness** — recorded fixtures: (applicant → expected canonical bureau response) captured from the
   old duplicated calls; assert the new capability returns equivalent data within an allowlist (timestamps,
   raw-blob ordering, source labels). This is the cutover gate.
9. **One real caller cutover (strangler)** — point ONE absorbed caller (suggest dl-eligibility) at the Bureau
   capability instead of its own CIBIL call; verify identical behavior; leave the others until later.
10. **README** — run locally (mocks), run parity, the demo, and the cutover status (which callers migrated).

**STOP gates:** (a) after step 2 (domain+ports) for my OK; (b) after step 8 (parity passes) — parity is the
correctness gate for a capability, like the concurrency test was for the edge.

---

## PART 4 — SCOPE FENCE
Implement ONLY `capabilities/bureau-capability` (+ the platform-idempotency extraction if caching is used).
Do NOT build Scoring, KYC, the orchestration engine, or migrate more than ONE caller. Adding a bureau vendor =
a new adapter + config, never a new service. If a task drifts into scoring/decisioning or another capability,
STOP and flag.

---

## PART 5 — THE OPENING PROMPT for Claude Code (paste; specs in docs/)
```
You are building Slice 2 of the IDFC integration platform: the Bureau capability, in the EXISTING monorepo
idfc-integration-platform, module capabilities/bureau-capability (currently a stub). Read in docs/ first:
BUREAU_CAPABILITY_BUILD_DOC.md (authoritative), IDFC_microservice_consolidation_context.md section 7B (the
LOCKED Bureau boundary), SKILL.md (method), and the Slice-1 sfdc-ingress-edge module (the proven pattern to
mirror).

WHAT BUREAU IS: ONE capability that fetches credit-bureau data (CIBIL / Multi-Bureau / Commercial Bureau /
Bureau-Score via the internal scorecard infra), replacing the 4-5x DUPLICATED bureau-fetch code today in
scorecard-analyser, cibil-management, dl-eligibility, and the fico-* services. It is STATELESS data-fetch —
it normalizes and returns bureau data; it does NOT score, decide, or do KYC.

CAPABILITY PATTERN (this is the FIRST capability — get it right; others reuse it):
- Hexagonal: domain (framework-free) + IN port (FetchBureauData) + OUT ports (one per vendor) + service (the
  superset logic) + adapters (one per vendor, mock[local]/real[prod], URL+auth from config never inlined).
- ONE canonical request/response (the superset of what absorbed services sent/received); adapters translate
  vendor shapes to/from canonical; callers never see vendor shapes.
- Vendors are ADAPTERS (CIBIL, Multi-Bureau, Commercial, scorecard-infra). Adding a bureau = adapter+config.
- The capability is INVOKED; it never calls another capability. For Slice 2 it's invoked via REST.
- Built as the SUPERSET of absorbed behavior, PARITY-PROVEN against the old duplicated calls; anti-patterns
  (inlined CIBIL keys/Basic-auth in code) cleaned — secrets via config/Vault placeholder.

CANONICAL API: POST /api/v1/bureau/fetch — request{applicant{...}, bureauTypes[], purpose, consentRef} ->
response{bureauResults[{type,score?,report,raw?,fetchedAt,source}], status, correlationId}. (See doc section 2.3.)

STACK: same as the edge — Java 21, Spring Boot 3.4.5, Gradle (Kotlin DSL, buildSrc conventions), Aerospike
(only if caching — reuse the edge's CAS/TTL semantics), Kafka (later, for orchestration — not Slice 2),
Testcontainers, Resilience4j, Micrometer+OTel. Mocks behind OUT ports for local run; NO real vendor URLs in
the repo (config only).

BUILD ORDER (follow doc section 3; observe STOP gates):
1. Fill the module (build.gradle conventions, hexagonal structure, Spring Boot app).
2. Domain + ports (canonical model; OUT ports Cibil/MultiBureau/Commercial/ScorecardInfra/optional Cache; IN
   port FetchBureauData). STOP for my OK.
3. HARVEST: before adapters, identify (from the absorbed services' contracts) the real per-vendor request/
   response fields and map each to/from canonical. If a field is ambiguous, flag it — don't invent.
4. Bureau service (core): fan out to requested bureauTypes in parallel, normalize each to canonical, merge,
   return. Pure, unit-tested with mock adapters.
5. Adapters: mock first (canned canonical), then real (vendor<->canonical, URL/auth from config).
6. Optional Aerospike cache (dedup on applicant-key+purpose+bureauType, short TTL) reusing the edge's store
   semantics.
7. If caching is used: EXTRACT the Aerospike store from sfdc-ingress-edge into platform/platform-idempotency
   and have BOTH the edge and Bureau depend on it via the port — verify the edge's existing tests still pass
   (move, not rewrite).
8. PARITY harness: recorded fixtures (applicant -> expected canonical bureau response captured from the old
   duplicated calls); assert equivalence within an allowlist (timestamps, raw ordering, source labels). STOP
   for my OK after parity passes — this is the capability correctness gate.
9. Strangler cutover of ONE caller (dl-eligibility) to the Bureau capability; verify identical behavior; leave
   other callers for later.
10. README: run locally, run parity, demo, cutover status.

SCOPE FENCE: implement ONLY capabilities/bureau-capability (+ the platform-idempotency extraction if caching).
Do NOT build Scoring/Decisioning, KYC, the orchestration engine, or migrate more than one caller. If a task
drifts into another capability, STOP and flag.

Note: Bureau != Scoring (decisioning is a different capability) and Bureau != KYC (CKYC/CERSAI is not bureau).
Keep Bureau a pure data-fetch capability.

Start with step 1, then step 2; STOP after domain+ports for my review.
```

---

## PART 6 — open inputs (don't block start; mocks + harvest de-risk)
1. Real per-vendor contracts (CIBIL/Multi-Bureau/Commercial/scorecard-infra request+response fields) — harvested
   at build time from the absorbed services (step 3).
2. Consent/DPDP: what `consentRef` must carry for a compliant bureau pull (confirm with compliance).
3. Caching policy: are bureau pulls cacheable, and for how long (cost/freshness tradeoff)? Default: short TTL or
   no cache until confirmed.
4. Which caller to cut over first: default dl-eligibility (cleanest single consumer).
