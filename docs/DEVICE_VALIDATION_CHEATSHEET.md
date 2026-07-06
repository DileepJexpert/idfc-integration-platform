# Device-Validation (brand-as-config) — Kafka UI Cheat Sheet

Copy-paste companion for the `device-validation` journey. Full detail lives in
`MANUAL_TEST_GUIDE.md` → *device-validation*. This is just the levers + payloads.

Device validation runs up to THREE config-gated activities — **validate**, **block**, **unblock**.
Each runs only on the intersection of (the request's `status` asks for it) AND (the brand supports it).
Outcomes are **valid / invalid** (not approve/decline).

---

## 0. Prereqs (pick ONE run path)

**Full-stack (real HTTP → WireMock; you publish only the start envelope):**
```bash
docker compose -f docker-compose.infra.yml up -d          # infra + mock-devicevalidation :9106 + Kafka UI :8085

# engine on local (classpath) — application-local.yml carries the orig.device-validation.v1 door + journey
./gradlew :orchestration:origination-journey:bootRun \
  --args='--spring.profiles.active=local --idfc.engine.journey-source=classpath --idfc.engine.state-store=in-memory'

# the capability app (real HTTP to :9106)
./gradlew :capabilities:device-validation:bootRun --args='--spring.profiles.active=local'   # :8110
```
> NOTE: the one-click `./run-services.sh` starts the engine on `local` with `IDFC_ENGINE_JOURNEY_SOURCE=classpath`, so the `orig.device-validation.v1` door + journey ARE active — that is the simplest path. The `bootRun` above does the same thing explicitly.

**Engine-only (no capability app; you hand-publish every hop):** start just the engine (same command,
capability app not needed). You publish the start envelope AND each `cap.device-validation.response.v1`.

---

## 1. The door

| | |
|---|---|
| **Start topic** | `orig.device-validation.v1` |
| **Message key** | the `correlationId` |
| **Value** | the CanonicalEnvelope below |
| **Instance id** | `"ji-" + correlationId` (used as the key for `cap.device-validation.response.v1`) |
| **Response topic** (engine-only) | `cap.device-validation.response.v1`, key = `journeyInstanceId` |
| **Decision topic** (terminal) | `orig.decision.v1`, key = `applicationRef` |

---

## 2. Start envelope — ONE template

Change `correlationId` (+ its derived ids), `payload.brand`, the device id (`imei`/`serial`/`deviceId`),
and optionally `payload.status` between cases. Key the Kafka message with the same `correlationId`.

```json
{
  "transactionId": "CORR-t",
  "schemaVersion": "demo.v1",
  "source": "FILE_DEMO",
  "type": "DEVICE_VALIDATION",
  "notificationId": "CORR-n",
  "orgId": "DEMO-ORG",
  "sfdcRecordId": "DEVICE",
  "applicationRef": "CORR-app",
  "correlationId": "CORR",
  "originalCorrelationId": "CORR",
  "payloadContentType": "application/json",
  "occurredAt": "2026-07-03T10:00:00Z",
  "payload": { "brand": "BRAND", "deviceId": "DEVICE", "status": "1" }
}
```
Replace `CORR` → your correlationId, `BRAND` → brand, `DEVICE` → device id. `type` MUST stay
`DEVICE_VALIDATION` (except the poison test). `status` is optional: **`"1"` = validate+block**
(disbursal), **`"2"` = unblock** (closure); **absent → `"1"`**. A serial-brand can send `"serial"`
instead of `"deviceId"`; the generic `deviceId` works for either (fallback).

---

## 3. Brand config (what each brand does)

| Brand | validate | block | unblock | validate-by | auth | pass body (valid) |
|---|---|---|---|---|---|---|
| SAMSUNG | ✅ | ✅ | ✅ | imei | OAUTH | `{"respCode":"0"}` |
| GODREJ | — | ✅ | — | serial | NA | `{"status":"OK"}` |
| BOSCH | ✅ | ✅ | ✅ | serial | BAUTH | `{"result":{"code":"S"}}` |
| APPLE | — | ✅ | — | imei | OAUTH | `{"respCode":"0"}` (real SFDC door, svcName `Post_Disbursal_Apple`) |
| HISENSE | — | ✅ | — | imei | OAUTH | `{"responseStatus":"-4"}` (runtime-added brand, §7) |
| *anything else* | — | — | — | — | — | **fail-closed → FAILED (PERMANENT)** |

**Which hops run** = requested (from `status`) ∩ supported (the flags):
- `status "1"` + SAMSUNG → validate **and** block. + GODREJ/APPLE → block only. + BOSCH → validate and block.
- `status "2"` + SAMSUNG/BOSCH → unblock only. + GODREJ/APPLE → *nothing runs* (unblock flag off).

## 4. device-id levers (full-stack)

| device id | Effect |
|---|---|
| `DEV-1`, `DEV-2`, … (any normal) | vendor returns pass body → **valid** |
| `DEV-DECLINE` | vendor 200 with non-pass body → **business invalid** (COMPLETED_DECLINED) |
| `DEV-FAIL` | vendor **HTTP 422** → **FAILED / PERMANENT** |

---

## 5. Permutation quick-table (full-stack)

Set key = correlationId. Expected = ops `status`.

| # | correlationId | brand | device id | status | Expected status |
|---|---|---|---|---|---|
| 1 | `corr-dv-valid-samsung` | SAMSUNG | DEV-1 | 1 | `COMPLETED_APPROVED` (validate+block) |
| 2 | `corr-dv-valid-godrej` | GODREJ | DEV-2 | 1 | `COMPLETED_APPROVED` (block only) |
| 3 | `corr-dv-valid-hisense` | HISENSE | DEV-9 | 1 | `COMPLETED_APPROVED` (needs HISENSE rows, §7) |
| 4 | `corr-dv-unblock-samsung` | SAMSUNG | DEV-1 | **2** | `COMPLETED_APPROVED` (unblock only) |
| 5 | `corr-dv-invalid-validate` | SAMSUNG | DEV-DECLINE | 1 | `COMPLETED_DECLINED` (invalid at validate) |
| 6 | `corr-dv-invalid-block` | GODREJ | DEV-DECLINE | 1 | `COMPLETED_DECLINED` (invalid at block) |
| 7 | `corr-dv-fail-permanent` | SAMSUNG | DEV-FAIL | 1 | `FAILED_SFDC_NOTIFIED` / PERMANENT |
| 8 | `corr-dv-unknown-brand` | NOKIA | DEV-7 | 1 | `FAILED_SFDC_NOTIFIED` / PERMANENT (fail-closed at n_decide) |
| 9 | `corr-dv-fail-transient` | GODREJ | DEV-5 | 1 | `FAILED_SFDC_NOTIFIED` / TRANSIENT — stop :9106 or stub 5xx |
| 10 | `corr-dv-fail-ambiguous` | GODREJ | DEV-6 | 1 | `FAILED_SFDC_NOTIFIED` / AMBIGUOUS — stub `fixedDelay > 10000ms` |
| 11 | `corr-dv-valid-samsung` (resend) | SAMSUNG | DEV-1 | 1 | dedup → still ONE run |
| 12 | `corr-dv-badtype` | SAMSUNG | DEV-1 | 1 | **no run**; poison → `orig.device-validation.v1.dlq` (set `type:"DEVICE_VALIDATE"`) |
| 13 | `corr-dv-stuck` | SAMSUNG | DEV-1 | 1 | `RUNNING`→(900s)→`FAILED_SFDC_NOTIFIED` `__timeout__` — engine-only, publish no response |

---

## 6. Engine-only: the response messages you hand-publish

Publish to `cap.device-validation.response.v1`, key = `ji-<correlationId>`, in node order. The first hop
is always `n_decide` (its result carries the `run*` plan); then only the hops whose `run*` is true.

**Valid, validate+block (SAMSUNG, status 1):** n_decide → n_validate → n_block, all valid:
```json
{ "journeyInstanceId": "ji-corr-dv-valid-samsung", "correlationId": "corr-dv-valid-samsung", "nodeId": "n_decide",   "capabilityKey": "device-validation", "status": "OK", "result": { "brand": "SAMSUNG", "validateBy": "imei", "authType": "OAUTH", "runValidate": true, "runBlock": true, "runUnblock": false }, "errorClass": null }
{ "journeyInstanceId": "ji-corr-dv-valid-samsung", "correlationId": "corr-dv-valid-samsung", "nodeId": "n_validate", "capabilityKey": "device-validation", "status": "OK", "result": { "brand": "SAMSUNG", "valid": true, "authType": "OAUTH", "vendor": { "respCode": "0" } }, "errorClass": null }
{ "journeyInstanceId": "ji-corr-dv-valid-samsung", "correlationId": "corr-dv-valid-samsung", "nodeId": "n_block",    "capabilityKey": "device-validation", "status": "OK", "result": { "brand": "SAMSUNG", "valid": true, "authType": "OAUTH", "vendor": { "respCode": "0" } }, "errorClass": null }
```

**Valid, block-only (GODREJ, status 1):** n_decide (`runValidate:false`) → n_block. Do NOT send n_validate.
```json
{ "journeyInstanceId": "ji-corr-dv-valid-godrej", "correlationId": "corr-dv-valid-godrej", "nodeId": "n_decide", "capabilityKey": "device-validation", "status": "OK", "result": { "brand": "GODREJ", "validateBy": "serial", "authType": "NA", "runValidate": false, "runBlock": true, "runUnblock": false }, "errorClass": null }
{ "journeyInstanceId": "ji-corr-dv-valid-godrej", "correlationId": "corr-dv-valid-godrej", "nodeId": "n_block", "capabilityKey": "device-validation", "status": "OK", "result": { "brand": "GODREJ", "valid": true, "authType": "NA", "vendor": { "status": "OK" } }, "errorClass": null }
```

**Valid, unblock-only (SAMSUNG, status 2):** n_decide (`runUnblock:true`, others false) → n_unblock.
```json
{ "journeyInstanceId": "ji-corr-dv-unblock-samsung", "correlationId": "corr-dv-unblock-samsung", "nodeId": "n_decide",  "capabilityKey": "device-validation", "status": "OK", "result": { "brand": "SAMSUNG", "validateBy": "imei", "authType": "OAUTH", "runValidate": false, "runBlock": false, "runUnblock": true }, "errorClass": null }
{ "journeyInstanceId": "ji-corr-dv-unblock-samsung", "correlationId": "corr-dv-unblock-samsung", "nodeId": "n_unblock", "capabilityKey": "device-validation", "status": "OK", "result": { "brand": "SAMSUNG", "valid": true, "authType": "OAUTH", "vendor": { "respCode": "0" } }, "errorClass": null }
```

**Invalid at validate:** send n_decide OK, then n_validate with `"valid": false`.
**Invalid at block:** send n_decide OK, then n_block with `"valid": false`.

**Failures (status ERROR, node fails immediately — no retry in this journey):** send n_decide OK, then the failing node with `status:"ERROR"`, `result:{}`, and the right `errorClass`:
- PERMANENT → `"errorClass": "PERMANENT"` (also brand fail-closed at n_decide for an unknown brand)
- TRANSIENT → `"errorClass": "TRANSIENT"`
- AMBIGUOUS → `"errorClass": "AMBIGUOUS"`

Example (n_block transient on GODREJ):
```json
{ "journeyInstanceId": "ji-corr-dv-fail-transient", "correlationId": "corr-dv-fail-transient", "nodeId": "n_block", "capabilityKey": "device-validation", "status": "ERROR", "result": {}, "errorClass": "TRANSIENT" }
```

---

## 7. Runtime-added brand (HISENSE, the brand-as-config headline)

Start the capability app with extra CLI rows — no rebuild:
```bash
./gradlew :capabilities:device-validation:bootRun --args='--spring.profiles.active=local \
  --device-validation.brands.HISENSE.validate=false \
  --device-validation.brands.HISENSE.block=true \
  --device-validation.brands.HISENSE.unblock=false \
  --device-validation.brands.HISENSE.validate-by=imei \
  --device-validation.brands.HISENSE.auth-type=OAUTH \
  --device-validation.brands.HISENSE.pass-path=responseStatus \
  --device-validation.brands.HISENSE.pass-value=-4'
```
Then send permutation 3. Without these rows, HISENSE hits the fail-closed path (#8).

---

## 8. Verify (ops-query, engine :8082)

Headers: `X-Ops-Token: dev-ops-token`, `X-User-Id: ops.analyst@bank`

```
GET http://localhost:8082/ops/runs/search?key=<correlationId | notificationId | sfdcRecordId>
GET http://localhost:8082/ops/runs/{runId}
GET http://localhost:8082/ops/runs?journeyKey=device-validation
GET http://localhost:8082/ops/runs?status=COMPLETED_APPROVED     (also COMPLETED_DECLINED / FAILED_SFDC_NOTIFIED / RUNNING)
GET http://localhost:8082/ops/runs?stuckOnly=true                (#13 during its stuck window)
```
Detail fields to check: `status`, `terminalNodeId` (`n_valid`/`n_invalid`/failing node/`__timeout__`),
`terminalOutcome` (APPROVED/REJECTED/ERROR — the shared ops vocabulary; a `n_valid` terminal reports
APPROVED, `n_invalid` reports REJECTED), and for failures `nodeStats[].failureClass`
(PERMANENT vs TRANSIENT vs AMBIGUOUS — the only way to tell those three apart at the same status).

---

## 9. Fastest smoke test

1. Infra up + engine on `local` (classpath) + `device-validation` app — or just `./run-services.sh`.
2. Kafka UI → topic `orig.device-validation.v1` → Produce: key `corr-dv-valid-samsung`, value = §2 template
   with `CORR=corr-dv-valid-samsung`, `BRAND=SAMSUNG`, `DEVICE=DEV-1`, `status=1`.
3. `GET /ops/runs/search?key=corr-dv-valid-samsung` → expect `COMPLETED_APPROVED`.
