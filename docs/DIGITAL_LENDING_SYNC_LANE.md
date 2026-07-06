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

Internals: a fail-closed `BearerTokenValidator` → `SyncCapabilityInvoker` (dispatches by
capabilityKey, in-thread) → a `SyncInvocable` capability → its `…Port` real HTTP adapter with
**mandatory connect+read timeouts**. A business "no" is a normal result body; a downstream
technical failure (timeout/5xx/unreachable) is a **uniform 5xx** (never a fake success).

---

## Built: `imps-disbursal` (IMPS fund transfer)  ✅

**`POST /api/v1/impsFT`** · source `INDMONEY` · sync · module `capabilities:imps-disbursal` (port 8113).

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

### Run it locally

```bash
docker compose -f docker-compose.infra.yml up -d          # brings up mock-imps on :9110
./gradlew :capabilities:imps-disbursal:bootRun --args='--spring.profiles.active=local'   # :8113
```
Local dev accepts `Authorization: Bearer dev-imps-token` (fail-closed allow-list; prod uses real
Ory/Hydra introspection). The vendor host defaults to the compose `mock-imps` on `:9110`.

### The real INDMONEY call (verbatim shape)

```bash
curl -sS -X POST http://localhost:8113/api/v1/impsFT \
  -H 'Authorization: Bearer dev-imps-token' \
  -H 'Content-Type: application/json' \
  -H 'correlationId: abcd-abcd-abcd-dddd' \
  -H 'transactionId: abcd-abcd-abcd-abcd' \
  -H 'source: INDMONEY' \
  --data '{
    "custBankAccNo": "2026040915306622",
    "idempotentId":  "2026040915306622",
    "ifscCode":      "UTIB0000001",
    "reqId":         "INDMONEY202601121110A101",
    "source":        "INDMONEY",
    "loanNo":        "110855952",
    "isDisbursalFlag": "Y"
  }'
# -> 200 { "reqId":"…", "status":"S", "transactionId":"003712585052", "custBankAccNo":"…",
#          "customerName":"Bene AC Holder ", "errCode":"", "errMessage":"" }
```

Levers against `mock-imps` (set `custBankAccNo`): `BAD-ACCOUNT` → 200 business decline (`status:F`,
`errCode:E01`); `SLOW-ACC` → read timeout → 502 `AMBIGUOUS`; `SERVER-ERROR` → 502 `TRANSIENT`.
Repeat the same `idempotentId` → the second call returns the prior result, the backend is hit once.

Proof: `ImpsDisbursalSyncIT` (real app + in-JVM stub, Docker-free) covers happy path, business-no,
technical timeout/5xx, idempotency, and fail-closed auth; `ImpsDisbursalServiceTest` covers the
idempotency/caching policy at the unit level.

---

## Next: `lms-utilities` (multi-op LMS query)  ⏳

**`POST /api/v1/callLmsUtilities`** · source `SAVEIN` · sync · `requestCode`-dispatched (`OFFER_CHECK`
now; unknown `requestCode` fails closed). Response is the **house envelope**
`{ metadata{…}, resource_data[…] }` — the SAME convention Karza uses — mapped by ONE shared
`HouseEnvelopeMapper` reused across LMS, Karza, and future sync services. Empty `resource_data` = a
business "no offer" (a clean empty result, not an error). To be built as slice 2 (mock-lms on :9111).
