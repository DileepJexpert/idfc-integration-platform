# Bureau Capability (Slice 2)

ONE capability that fetches and normalizes credit-bureau data
(CIBIL / Multi-Bureau / Commercial / Bureau-Score), replacing the 4–5× duplicated
bureau-fetch code across `scorecard-analyser`, `cibil-management`,
`dl-eligibility`, and the `fico-*` services. It is the **first capability** and so
establishes the reusable **capability pattern**. Authoritative spec:
[`docs/BUREAU_CAPABILITY_BUILD_DOC.md`](docs/BUREAU_CAPABILITY_BUILD_DOC.md).

**Stateless data-fetch only** — it fetches + normalizes + returns. It does NOT
score, decide eligibility, or do KYC, and never calls another capability.

## Hexagonal layout (mirrors the SFDC edge)

```
domain/model      canonical, framework-free types (the superset request/response, §2.3)
domain/port/in    FetchBureauData (the one use-case)
domain/port/out   one port per bureau: Cibil / MultiBureau / Commercial / ScorecardInfra
                  + optional BureauCachePort (defined, unwired — see caching below)
domain/service    BureauService — parallel fan-out → normalize → merge → status (pure)
adapter/in/rest   POST /api/v1/bureau/fetch (canonical API)
adapter/out/mock  profile-switched mock vendor adapters (canned CANONICAL data)
config            Spring wiring (Clock, bounded fan-out executor, FetchBureauData)
```

Callers and the service depend only on ports; vendors are adapters. **Adding a
bureau = a new adapter + config + enum value, never a new service.**

## Canonical API

```
POST /api/v1/bureau/fetch
  { applicant{ firstName, middleName?, lastName, dob, pan, aadharRef?, addresses[],
               phone, email, gstDetails?, businessDetails? },
    bureauTypes: ["CIBIL"|"MULTI_BUREAU"|"COMMERCIAL"|"BUREAU_SCORE"],
    purpose: "ELIGIBILITY"|"LIMIT_ENHANCEMENT"|"UNDERWRITING",
    consentRef }
->{ bureauResults[ { type, score?, report{...normalized...}, rawRef?, fetchedAt, source } ],
    status: "SUCCESS"|"PARTIAL"|"FAILED", correlationId }
```

One bureau failing yields `PARTIAL` (the rest still return); all failing yields `FAILED`.

## Run locally (mocks)

```bash
./gradlew :capabilities:bureau-capability:bootRun        # default profile = mock vendors
curl -s localhost:8080/actuator/health
curl -s -XPOST localhost:8080/api/v1/bureau/fetch -H 'Content-Type: application/json' -d '{
  "applicant": {"firstName":"Asha","lastName":"Rao","dob":"1990-01-01","pan":"ABCPR1234F",
                "phone":"9999999999","email":"asha@example.com"},
  "bureauTypes": ["CIBIL","MULTI_BUREAU"],
  "purpose": "ELIGIBILITY",
  "consentRef": "consent-001" }'
```

The mock adapters return deterministic **canonical** data (score derived from PAN);
they mirror the canonical contract, not any vendor wire format.

## Tests & the parity gate

```bash
./gradlew :capabilities:bureau-capability:test
```

- `BureauServiceTest` — fan-out / merge / SUCCESS·PARTIAL·FAILED, per-type dispatch.
- `BureauModelTest` — canonical model invariants.
- `BureauParityTest` — the **correctness gate**: recorded fixtures
  (`src/test/resources/parity/`) → capability output, compared by
  `BureauParityOracle` within the allowlist (`fetchedAt`, `rawRef`, `source`).
  Includes a negative fixture proving a real diff fails parity.

## Status — Slice 2 progress

| Step | State |
|---|---|
| 1 — module + hexagonal skeleton | ✅ |
| 2 — canonical domain + ports | ✅ (reviewed) |
| 3 — HARVEST real vendor contracts | ⏳ needs absorbed-service contracts (not in this repo) |
| 4 — core service (fan-out/merge) | ✅ |
| 5 — adapters: **mock** ✅ / **real** ⏳ (gated on harvest) | partial |
| 6/7 — Aerospike cache + platform-idempotency extraction | ⏸ deferred: caching is an open input (default **no cache**), so no extraction yet (per roadmap rule 2 — extract only when a second consumer truly needs it) |
| 8 — parity harness | ✅ mechanism + synthetic fixtures (real fixtures captured during harvest/cutover) |
| 9 — strangler cutover of one caller | ⛔ not started (default target: `dl-eligibility`) |

## Open inputs (PART 6)

1. **Harvest** the real per-vendor request/response fields + endpoints/auth from the
   absorbed services → finalizes the real adapters and the `BureauReport` field map.
2. **Consent/DPDP**: what `consentRef` must carry for a compliant pull.
3. **Caching policy**: cache bureau pulls? TTL? (drives the `platform-idempotency` extraction.)
4. **First cutover caller**: default `dl-eligibility`.
