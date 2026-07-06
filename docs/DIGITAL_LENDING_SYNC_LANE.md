# Digital-Lending SYNC lane

The digital-lending partner calls (INDMONEY `impsFT`, SAVEIN `callLmsUtilities`) are
**synchronous**: the caller BLOCKS for the result on the same HTTP call. This lane is a
separate execution path from the async journey engine — **no journeyInstanceId, no Kafka
request/response topics, no engine state store**. The shape both calls share:

| Aspect | Value |
|---|---|
| Transport | HTTP POST, `Content-Type: application/json` |
| Auth | `Authorization: Bearer ory_at_...` (Ory/Hydra) — validated at the edge, **fail closed** |
| Headers | `correlationId`, `transactionId`, `source` (partner) |
| `source` | `INDMONEY` / `SAVEIN` — a **config attribute** (trace/authz), **never a routing key**; one lane, one code path |
| Pattern | **SYNCHRONOUS** — the response comes back on the same call |

## Where it lives (architecture)

The **digital-partner-edge** (`:8081`) hosts BOTH synchronous doors alongside its async
partner-origination path. The sync capabilities are **libraries invoked in-thread**, not
standalone services:

```
POST /api/v1/impsFT | /api/v1/callLmsUtilities   (edge :8081)
  -> ConfiguredBearerTokenValidator (fail-closed Ory/Hydra allow-list; prod = Kong/Hydra introspection)
  -> SyncCapabilityInvoker.invoke(capabilityKey, operation, payload, ctx)   [shared:shared-sync]
  -> the SyncInvocable capability, IN-THREAD (no engine, no Kafka)
       imps-disbursal -> ImpsFtPort   (real HTTP, mandatory timeouts)
       lms-utilities  -> LmsUtilityPort (real HTTP) -> HouseEnvelopeMapper (shared)
```

- `shared:shared-sync` — the contracts: `SyncInvocable`, `SyncCapabilityInvoker`, `SyncRequestContext`,
  `SyncTechnicalException`, `SyncErrorResponse`, `BearerTokenValidator`, and the reusable
  `HouseEnvelopeMapper` for the `{metadata, resource_data[]}` house response.
- `capabilities:imps-disbursal`, `capabilities:lms-utilities` — the capabilities, each a library that
  implements `SyncInvocable` and ships a `@Configuration` module the edge `@Import`s.
- Dispatch is by **capabilityKey** only; `source` (INDMONEY/SAVEIN) is trace/authz, never routing.
- A business "no" is a normal result body; a downstream technical failure (timeout/5xx/unreachable)
  is a **uniform 5xx** with an `errorClass` (never a fake success).

Local dev accepts `Authorization: Bearer dev-sync-token` (fail-closed allow-list — prod swaps in real
Ory/Hydra introspection). Vendors are the compose WireMocks (`mock-imps :9110`, `mock-lms :9111`); real
backends later = host-config swaps.

```bash
docker compose -f docker-compose.infra.yml up -d          # mock-imps :9110 + mock-lms :9111
./gradlew :edges:digital-partner-edge:bootRun --args='--spring.profiles.active=local'   # :8081
```

---

## `imps-disbursal` (IMPS fund transfer)  ✅

**`POST /api/v1/impsFT`** · source `INDMONEY` · sync.

- **status `S`** = the transfer succeeded → returned as-is.
- **non-`S`** (with `errCode`/`errMessage`) = a **business "no"** (e.g. invalid account) → returned as a
  **200** error envelope, NOT a technical exception.
- **timeout / 5xx / unreachable** = a **technical error** → uniform **502** + `errorClass`
  (`PERMANENT`/`TRANSIENT`/`AMBIGUOUS`) + an internal alert. A read timeout on a money movement is
  **AMBIGUOUS** (the money may have moved) — never reported as success.
- **`idempotentId`** (required) prevents a double transfer: a repeat returns the PRIOR result and does
  not re-call the backend. Only definitive outcomes (success / business decline) are cached; a
  technical failure is not, so an ambiguous transfer stays retryable. (Dev store is in-memory; prod is
  the shared Aerospike store — a host-config swap.)

```bash
curl -sS -X POST http://localhost:8081/api/v1/impsFT \
  -H 'Authorization: Bearer dev-sync-token' \
  -H 'Content-Type: application/json' \
  -H 'correlationId: abcd-abcd-abcd-dddd' \
  -H 'transactionId: abcd-abcd-abcd-abcd' \
  -H 'source: INDMONEY' \
  --data '{
    "custBankAccNo": "2026040915306622", "idempotentId": "2026040915306622",
    "ifscCode": "UTIB0000001", "reqId": "INDMONEY202601121110A101",
    "source": "INDMONEY", "loanNo": "110855952", "isDisbursalFlag": "Y"
  }'
# -> 200 { "reqId":"…", "status":"S", "transactionId":"003712585052", "custBankAccNo":"…",
#          "customerName":"Bene AC Holder ", "errCode":"", "errMessage":"" }
```

Levers against `mock-imps` (set `custBankAccNo`): `BAD-ACCOUNT` → 200 business decline (`status:F`,
`errCode:E01`); `SLOW-ACC` → read timeout → 502 `AMBIGUOUS`; `SERVER-ERROR` → 502 `TRANSIENT`.
Repeat the same `idempotentId` → the second call returns the prior result, the backend is hit once.

---

## `lms-utilities` (multi-op LMS query)  ✅

**`POST /api/v1/callLmsUtilities`** · source `SAVEIN` · sync · **`requestCode`-dispatched**.

- `requestCode` selects the operation (`OFFER_CHECK` now; siblings — balance, foreclosure, schedule —
  are **config** rows in `lms-utilities.known-request-codes` + a stub, not code). An **unknown
  `requestCode` fails closed** → `422` (`UNKNOWN_REQUEST_CODE`), it never reaches the backend.
- Response is the **house envelope** `{ metadata{…}, resource_data[…] }`, normalized by the ONE shared
  `HouseEnvelopeMapper` (the same convention Karza uses) into `{ status, message, resourceData[] }`.
- `metadata.status: SUCCESS` with an **empty** `resource_data` is a legitimate business **"no offer"** —
  a clean empty result (`200`), NOT an error. A downstream failure is a uniform `502`.

```bash
curl -sS -X POST http://localhost:8081/api/v1/callLmsUtilities \
  -H 'Authorization: Bearer dev-sync-token' \
  -H 'Content-Type: application/json' \
  -H 'correlationId: abc123456783456745673456' \
  -H 'source: SAVEIN' \
  --data '{
    "entityName": "PBLINE",
    "agreementId": "pbline_5eb16741c06cf6dd368e0cea7f41f838",
    "crnNo": "pbline_5eb16741c06cf6dd368e0cea7f41f838",
    "requestCode": "OFFER_CHECK"
  }'
# -> 200 { "status":"SUCCESS", "message":"OFFER_CHECK processed successfully",
#          "resourceData":[ { "LOAN_AMOUNT":"500000", "ROI":"14", "RISK_SEGMENT":"LOW RISK", … } ] }
```

Levers against `mock-lms`: `crnNo: NO-OFFER-CRN` → 200 SUCCESS with empty `resourceData` (the no-offer
case); a `requestCode` not in `known-request-codes` → 422 fail-closed.

---

## Proof

- `DigitalSyncLaneIT` (edge, Docker-free: real sync assembly + in-JVM stubs) — both doors end-to-end:
  IMPS happy/business-no/timeout/idempotency/auth/missing-id, LMS offer/no-offer/unknown-requestCode/
  auth/missing-requestCode. The existing async-edge tests stay green (the async path is untouched).
- `ImpsDisbursalServiceTest`, `LmsUtilitiesServiceTest` — the capability policy at the unit level
  (idempotency/caching for IMPS; requestCode fail-closed + no-offer-is-not-an-error for LMS).

## What is "later" (config, not code)

- Real IMPS/LMS backend hosts + credentials → `*.vendor-base-url` / `*.vendor-auth-token` config swap.
- Real Ory/Hydra (Kong) token introspection → replace `ConfiguredBearerTokenValidator` behind the same
  `BearerTokenValidator` interface.
- New LMS `requestCode`s → a row in `lms-utilities.known-request-codes` + a stub, same dispatch path.
- Durable cross-JVM IMPS idempotency → the shared Aerospike store behind `ImpsIdempotencyStorePort`.
