# Device-Financing (brand-as-config) — Kafka UI Cheat Sheet

Copy-paste companion for the `device-financing` demo journey. Full detail lives in
`MANUAL_TEST_GUIDE.md` → *Demo: device-financing*. This is just the levers + payloads.

---

## 0. Prereqs (pick ONE run path)

**Full-stack (real HTTP → WireMock; you publish only the start envelope):**
```bash
docker compose -f docker-compose.infra.yml up -d          # infra + mock-devicefin :9106 + Kafka UI :8085

# engine on local (classpath) — application-local.yml carries the orig.demo.device.v1 door + journey
./gradlew :orchestration:origination-journey:bootRun \
  --args='--spring.profiles.active=local --idfc.engine.journey-source=classpath --idfc.engine.state-store=in-memory'

# the demo capability app (real HTTP to :9106)
./gradlew :capabilities:device-financing:bootRun --args='--spring.profiles.active=local'   # :8110
```
> NOTE: the one-click `./run-services.sh` starts the engine on `local` with `IDFC_ENGINE_JOURNEY_SOURCE=classpath`, so the `orig.demo.device.v1` door + journey ARE active — that is the simplest path. The `bootRun` above does the same thing explicitly (there is no separate `demo` profile any more).

**Engine-only (no capability app; you hand-publish every hop):** start just the engine (same command,
capability app not needed). You publish the start envelope AND each `cap.device-financing.response.v1`.

---

## 1. The door

| | |
|---|---|
| **Start topic** | `orig.demo.device.v1` |
| **Message key** | the `correlationId` |
| **Value** | the CanonicalEnvelope below |
| **Instance id** | `"ji-" + correlationId` (used as the key for `cap.device-financing.response.v1`) |
| **Response topic** (engine-only) | `cap.device-financing.response.v1`, key = `journeyInstanceId` |
| **Decision topic** (terminal) | `orig.decision.v1`, key = `applicationRef` |

---

## 2. Start envelope — ONE template, change 4 things

Only `correlationId` (+ its derived ids), `payload.brand`, `payload.deviceId` change between cases.
Key the Kafka message with the same `correlationId`.

```json
{
  "transactionId": "CORR-t",
  "schemaVersion": "demo.v1",
  "source": "FILE_DEMO",
  "type": "DEVICE_FINANCING",
  "notificationId": "CORR-n",
  "orgId": "DEMO-ORG",
  "sfdcRecordId": "DEVICE",
  "applicationRef": "CORR-app",
  "correlationId": "CORR",
  "originalCorrelationId": "CORR",
  "payloadContentType": "application/json",
  "occurredAt": "2026-07-03T10:00:00Z",
  "payload": { "brand": "BRAND", "deviceId": "DEVICE" }
}
```
Replace `CORR` → your correlationId, `BRAND` → brand, `DEVICE` → deviceId (also used as `sfdcRecordId`).
`type` MUST stay `DEVICE_FINANCING` (except the poison test).

---

## 3. Brand config (what each brand does)

| Brand | auth | validation-required | pass body (approve) |
|---|---|---|---|
| SAMSUNG | OAUTH | **yes** (validate + block) | `{"respCode":"0"}` |
| GODREJ | NA | no (block only) | `{"status":"OK"}` |
| BOSCH | BAUTH | **yes** (validate + block) | `{"result":{"code":"S"}}` |
| HISENSE | OAUTH | no (block only) | `{"responseStatus":"-4"}` (runtime-added brand) |
| *anything else* | — | — | **fail-closed → FAILED (PERMANENT)** |

## 4. deviceId levers (full-stack)

| deviceId | Effect |
|---|---|
| `DEV-1`, `DEV-2`, … (any normal) | vendor returns pass body → **approved** |
| `DEV-DECLINE` | vendor 200 with decline body → **business decline** (REJECTED) |
| `DEV-FAIL` | vendor **HTTP 422** → **FAILED / PERMANENT** |

---

## 5. Permutation quick-table (full-stack)

Set key = correlationId. Expected = ops `status`.

| # | correlationId | brand | deviceId | Expected status |
|---|---|---|---|---|
| 1 | `corr-df-approve-samsung` | SAMSUNG | DEV-1 | `COMPLETED_APPROVED` (validate+block) |
| 2 | `corr-df-approve-godrej` | GODREJ | DEV-2 | `COMPLETED_APPROVED` (block only) |
| 3 | `corr-df-approve-hisense` | HISENSE | DEV-9 | `COMPLETED_APPROVED` (needs HISENSE config rows, see §7) |
| 4 | `corr-df-decline-validate` | SAMSUNG | DEV-DECLINE | `COMPLETED_DECLINED` (declines at validate) |
| 5 | `corr-df-decline-block` | GODREJ | DEV-DECLINE | `COMPLETED_DECLINED` (declines at block) |
| 6 | `corr-df-block-only-decline` | BOSCH | DEV-3 | `COMPLETED_DECLINED` — **engine-only** (§6) |
| 7 | `corr-df-fail-permanent` | SAMSUNG | DEV-FAIL | `FAILED_SFDC_NOTIFIED` / PERMANENT |
| 8 | `corr-df-unknown-brand` | NOKIA | DEV-7 | `FAILED_SFDC_NOTIFIED` / PERMANENT (fail-closed at n_brand) |
| 9 | `corr-df-fail-transient` | GODREJ | DEV-5 | `FAILED_SFDC_NOTIFIED` / TRANSIENT — stop :9106 or stub 5xx |
| 10 | `corr-df-fail-ambiguous` | GODREJ | DEV-6 | `FAILED_SFDC_NOTIFIED` / AMBIGUOUS — stub `fixedDelay > 10000ms` |
| 12 | `corr-df-approve-samsung` (resend) | SAMSUNG | DEV-1 | dedup → still ONE run |
| 13 | `corr-df-badtype` | SAMSUNG | DEV-1 | **no run**; poison → `orig.demo.device.v1.dlq` (set `type:"DEVICE_FINANCE"`) |
| 14 | `corr-df-stuck` | SAMSUNG | DEV-1 | `RUNNING`→(900s)→`FAILED_SFDC_NOTIFIED` `__timeout__` — engine-only, publish no response |

(#11 BREAKER_OPEN is **not reachable** in this journey — no breaker configured.)

---

## 6. Engine-only: the response messages you hand-publish

Publish to `cap.device-financing.response.v1`, key = `ji-<correlationId>`, in node order.

**Approve, validation-required (SAMSUNG):** n_brand → n_validate → n_block, all approve:
```json
{ "journeyInstanceId": "ji-corr-df-approve-samsung", "correlationId": "corr-df-approve-samsung", "nodeId": "n_brand",    "capabilityKey": "device-financing", "status": "OK", "result": { "brand": "SAMSUNG", "validationRequired": true, "authType": "OAUTH" }, "errorClass": null }
{ "journeyInstanceId": "ji-corr-df-approve-samsung", "correlationId": "corr-df-approve-samsung", "nodeId": "n_validate", "capabilityKey": "device-financing", "status": "OK", "result": { "brand": "SAMSUNG", "approved": true, "authType": "OAUTH", "vendor": { "respCode": "0" } }, "errorClass": null }
{ "journeyInstanceId": "ji-corr-df-approve-samsung", "correlationId": "corr-df-approve-samsung", "nodeId": "n_block",    "capabilityKey": "device-financing", "status": "OK", "result": { "brand": "SAMSUNG", "approved": true, "authType": "OAUTH", "vendor": { "respCode": "0" } }, "errorClass": null }
```

**Approve, block-only (GODREJ):** n_brand (`validationRequired:false`) → n_block. Do NOT send n_validate.
```json
{ "journeyInstanceId": "ji-corr-df-approve-godrej", "correlationId": "corr-df-approve-godrej", "nodeId": "n_brand", "capabilityKey": "device-financing", "status": "OK", "result": { "brand": "GODREJ", "validationRequired": false, "authType": "NA" }, "errorClass": null }
{ "journeyInstanceId": "ji-corr-df-approve-godrej", "correlationId": "corr-df-approve-godrej", "nodeId": "n_block", "capabilityKey": "device-financing", "status": "OK", "result": { "brand": "GODREJ", "approved": true, "authType": "NA", "vendor": { "status": "OK" } }, "errorClass": null }
```

**Decline at validate:** send n_brand OK, then n_validate with `"approved": false`.
**Decline at block:** send n_brand OK, then n_block with `"approved": false`.

**#6 — pass validate, decline block (BOSCH, engine-only only):**
```json
{ "journeyInstanceId": "ji-corr-df-block-only-decline", "correlationId": "corr-df-block-only-decline", "nodeId": "n_brand",    "capabilityKey": "device-financing", "status": "OK", "result": { "brand": "BOSCH", "validationRequired": true, "authType": "BAUTH" }, "errorClass": null }
{ "journeyInstanceId": "ji-corr-df-block-only-decline", "correlationId": "corr-df-block-only-decline", "nodeId": "n_validate", "capabilityKey": "device-financing", "status": "OK", "result": { "brand": "BOSCH", "approved": true,  "authType": "BAUTH", "vendor": { "result": { "code": "S" } } }, "errorClass": null }
{ "journeyInstanceId": "ji-corr-df-block-only-decline", "correlationId": "corr-df-block-only-decline", "nodeId": "n_block",    "capabilityKey": "device-financing", "status": "OK", "result": { "brand": "BOSCH", "approved": false, "authType": "BAUTH", "vendor": { "result": { "code": "F" } } }, "errorClass": null }
```

**Failures (status ERROR, node fails immediately — no retry in this journey):** send n_brand OK, then the failing node with `status:"ERROR"`, `result:{}`, and the right `errorClass`:
- PERMANENT → `"errorClass": "PERMANENT"` (also brand fail-closed at n_brand for unknown brand)
- TRANSIENT → `"errorClass": "TRANSIENT"`
- AMBIGUOUS → `"errorClass": "AMBIGUOUS"`

Example (n_block transient on GODREJ):
```json
{ "journeyInstanceId": "ji-corr-df-fail-transient", "correlationId": "corr-df-fail-transient", "nodeId": "n_block", "capabilityKey": "device-financing", "status": "ERROR", "result": {}, "errorClass": "TRANSIENT" }
```

---

## 7. Runtime-added brand (HISENSE, the brand-as-config headline)

Start the capability app with 4 extra CLI rows — no rebuild:
```bash
./gradlew :capabilities:device-financing:bootRun --args='--spring.profiles.active=local \
  --device-financing.brands.HISENSE.auth-type=OAUTH \
  --device-financing.brands.HISENSE.validation-required=false \
  --device-financing.brands.HISENSE.pass-path=responseStatus \
  --device-financing.brands.HISENSE.pass-value=-4'
```
Then send permutation 3. Without these rows, HISENSE hits the fail-closed path (#8).

---

## 8. Verify (ops-query, engine :8082)

Headers: `X-Ops-Token: dev-ops-token`, `X-User-Id: ops.analyst@bank`

```
GET http://localhost:8082/ops/runs/search?key=<correlationId | notificationId | sfdcRecordId>
GET http://localhost:8082/ops/runs/{runId}
GET http://localhost:8082/ops/runs?journeyKey=device-financing
GET http://localhost:8082/ops/runs?status=COMPLETED_APPROVED     (also COMPLETED_DECLINED / FAILED_SFDC_NOTIFIED / RUNNING)
GET http://localhost:8082/ops/runs?stuckOnly=true                (#14 during its stuck window)
```
Detail fields to check: `status`, `terminalNodeId` (`n_approve`/`n_reject`/failing node/`__timeout__`),
`terminalOutcome` (APPROVED/REJECTED/ERROR), and for failures `nodeStats[].failureClass`
(PERMANENT vs TRANSIENT vs AMBIGUOUS — the only way to tell those three apart at the same status).

---

## 9. Fastest smoke test

1. Infra up + engine on `local` (classpath) + `device-financing` app — or just `./run-services.sh`.
2. Kafka UI → topic `orig.demo.device.v1` → Produce: key `corr-df-approve-samsung`, value = §2 template
   with `CORR=corr-df-approve-samsung`, `BRAND=SAMSUNG`, `DEVICE=DEV-1`.
3. `GET /ops/runs/search?key=corr-df-approve-samsung` → expect `COMPLETED_APPROVED`.
