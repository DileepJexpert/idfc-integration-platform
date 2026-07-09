# Journey-wise manual test playbook

Start each journey by hand and watch it run. Routing is by the envelope's **`type`** field (not the
topic), so any consumed origination topic works — the tables below use the natural one.

## 0. Bring it up (once)

```bash
docker compose -f docker-compose.infra.yml up -d     # Kafka(29092) · Kafka UI(8085) · Aerospike · all vendor mocks
./run-services.sh                                     # engine(8082) + capabilities + edges, local profile, classpath journeys
```

Ports & tokens you'll use:

| thing | where | auth |
|---|---|---|
| Kafka UI (produce/watch) | http://localhost:8085 | — |
| Kafka broker (host) | `localhost:29092` (container `idfc-kafka`, internal `9092`) | — |
| Ops view (verify a run) | http://localhost:8082/ops/... | `X-Ops-Token: dev-ops-token` · `X-User-Id: you@bank` |
| Digital edge (SYNC doors) | http://localhost:8081 | `Authorization: Bearer dev-sync-token` |
| SFDC ingress edge (SOAP) | http://localhost:8080 | `X-Auth-Token: dev-token` |

**Two ways to publish a Kafka message:**
- **Kafka UI** → Topics → *(topic)* → **Produce Message** → Key = the `correlationId`, Value = the JSON.
- **CLI:**
  ```bash
  docker exec -i idfc-kafka /opt/kafka/bin/kafka-console-producer.sh \
    --bootstrap-server localhost:9092 --topic orig.device-validation.v1 \
    --property parse.key=true --property key.separator='|'
  # then paste one line:   c-1|{"type":"DEVICE_VALIDATION", ... }
  ```

**Verify any run (ops view):**
```bash
H=(-H "X-Ops-Token: dev-ops-token" -H "X-User-Id: you@bank")
curl -s "${H[@]}" "http://localhost:8082/ops/runs/search?key=<correlationId>"   # -> journeyKey, status, runId
curl -s "${H[@]}" "http://localhost:8082/ops/runs/<runId>"                      # -> node transitions, terminalNodeId, failureClass
```
Statuses: `COMPLETED_APPROVED` (valid/approved) · `COMPLETED_DECLINED` (business "no") · `FAILED_SFDC_NOTIFIED` (technical) · `RUNNING`.
Also watch `orig.decision.v1` and `cap.*.response.v1` in Kafka UI.

**The envelope template** (only `payload` changes per journey; keep `correlationId` = your search key):
```json
{
  "type": "<TYPE>",
  "source": "SFDC",
  "orgId": "IDFC_RETAIL",
  "notificationId": "n-<corr>",
  "sfdcRecordId": "<id>",
  "applicationRef": "app-<corr>",
  "correlationId": "<corr>",
  "occurredAt": "2026-07-06T10:00:00Z",
  "payloadContentType": "application/json",
  "payload": { }
}
```

---

## A. Ready out-of-the-box (Kafka, via `./run-services.sh`)

### A1 · device-validation  ·  vendor mock 19106
- **Topic:** `orig.device-validation.v1`  ·  **type:** `DEVICE_VALIDATION`
- **payload:** `{ "brand": "SAMSUNG", "deviceId": "DEV-1", "status": "1" }`
- **Levers:** brand `SAMSUNG`(imei, validate+block+unblock) · `GODREJ`(serial, block-only) · `BOSCH`(serial, full) · `APPLE`(imei, block-only). `status` `1`=validate+block, `2`=unblock. deviceId `DEV-1`→**valid**, `DEV-DECLINE`→**invalid**, `DEV-FAIL`→**FAILED**. (serial brands may send `"serial"` instead of `deviceId`.)
- **Expect:** `1`+`DEV-1` → `COMPLETED_APPROVED`, terminal `n_valid`; `DEV-DECLINE` → `COMPLETED_DECLINED` `n_invalid`; `2` → only the unblock hop runs.

### A2 · loan-origination  ·  mocks 19101/19102/19103/19104 + FinnOne 1521
- **Topic:** `orig.sfdc.pl.v1`  ·  **type:** `PERSONAL_LOAN`
- **payload:** `{ "customerId": "CUST-1001", "pan": "ABCDE1234F", "mobile": "9999900001", "amount": 500000 }`
- **Flow:** customer → KYC → bureau → score → decide → book (with reversal + a FinnOne concurrency cap).
- **Expect:** approve/decline is driven by the bureau/scoring mock; a good score → `COMPLETED_APPROVED` (`LoanBooked`), else `COMPLETED_DECLINED`. Exhaustive vetted payloads: see `docs/MANUAL_TEST_GUIDE.md`.

### A3 · vehicle-rc-verification  ·  Karza mock 19105
- **Topic:** `orig.sfdc.pl.v1` (any consumed topic)  ·  **type:** `VEHICLE_RC`
- **payload:** `{ "registrationNumber": "MH12AB1234", "consent": "Y" }`
- **Expect:** RC `ACTIVE` & `CLEAR` → `COMPLETED_APPROVED`; not-active/blacklisted → `COMPLETED_DECLINED`; Karza error → `FAILED_*`.

### A4 · employee-lwd-update  ·  Fusion mock 19107
- **Topic:** `orig.employee-lwd-update.v1`  ·  **type:** `EMPLOYEE_LWD_UPDATE`
- **payload:** `{ "employeeId": "EMP-001", "lastWorkingDay": "2026-07-31" }`
- **Or via file drop:** put a CSV (`employeeId,lastWorkingDay`) into the file-batch edge's inbox → one run per row.
- **Expect:** valid date → `COMPLETED_APPROVED`; a malformed date (`not-a-date`) → that row `FAILED` (HTTP 400 → PERMANENT), others still complete.

---

## B. Sync lane — HTTP, no Kafka (digital edge :8081)

### B1 · imps-disbursal  ·  mock-imps 19110
```bash
curl -sS -X POST http://localhost:8081/api/v1/impsFT \
  -H 'Authorization: Bearer dev-sync-token' -H 'Content-Type: application/json' \
  -H 'correlationId: c-imps-1' -H 'transactionId: t-imps-1' -H 'source: INDMONEY' \
  --data '{ "custBankAccNo":"2026040915306622", "idempotentId":"idem-imps-1",
            "ifscCode":"UTIB0000001", "reqId":"INDMONEY202601121110A101",
            "source":"INDMONEY", "loanNo":"110855952", "isDisbursalFlag":"Y" }'
```
- **Expect:** `200 { "status":"S", "transactionId":"003712585052", ... }`. Repeat the same `idempotentId` → same result, **no second transfer**.
- **Levers (`custBankAccNo`):** `BAD-ACCOUNT` → 200 `status:F` (business decline) · `SLOW-ACC` → `502 AMBIGUOUS` (timeout) · `SERVER-ERROR` → `502 TRANSIENT`. Missing/blank Bearer → `401`; missing `idempotentId` → `400`.

### B2 · lms-utilities  ·  mock-lms 19111
```bash
curl -sS -X POST http://localhost:8081/api/v1/callLmsUtilities \
  -H 'Authorization: Bearer dev-sync-token' -H 'Content-Type: application/json' \
  -H 'correlationId: c-lms-1' -H 'source: SAVEIN' \
  --data '{ "entityName":"PBLINE", "agreementId":"pbline_5eb16741c06cf6dd368e0cea7f41f838",
            "crnNo":"pbline_5eb16741c06cf6dd368e0cea7f41f838", "requestCode":"OFFER_CHECK" }'
```
- **Expect:** `200 { "status":"SUCCESS", "resourceData":[ { "LOAN_AMOUNT":"500000", "ROI":"14", ... } ] }`.
- **Levers:** `crnNo` = `NO-OFFER-CRN` → 200 SUCCESS with **empty** `resourceData` (a clean "no offer") · `requestCode` = anything not in the allow-list → `422 UNKNOWN_REQUEST_CODE` (fail closed). Missing Bearer → `401`.

---

## C. Real SFDC SOAP door — HTTP (ingress edge :8080)

### C1 · device-validation via the Apple post-disbursal SOAP (the production entry)
```bash
curl -sS -X POST http://localhost:8080/api/v1/sfdc/outbound-messages \
  -H "X-Auth-Token: dev-token" -H "Content-Type: text/xml" \
  --data-binary @full-flow-it/src/test/resources/sfdc-outbound-apple-postdisbursal.xml
# -> 200 <Ack>true</Ack>
```
- The edge stamps its **own** correlationId, so verify by **notificationId**:
  `curl -s "${H[@]}" "http://localhost:8082/ops/runs/search?key=04l7200000Daq5RAbR"` → device-validation, `COMPLETED_APPROVED`.
- Sibling SOAP fixtures in `full-flow-it/src/test/resources/`: `sfdc-outbound-golden.xml` (Inbound_Wrapper → loan-origination), `sfdc-outbound-sendsms-golden.xml` (SENDSMS → communications, sends one SMS).

---

## D. Need a config row first (not routable by default)

These journeys exist and are seeded, but have **no `type-to-journey` row**, so the engine fails them closed
until you add one. Add the row to `orchestration/.../application-local.yml` under `idfc.engine.type-to-journey`,
restart the engine, then publish as in section A.

| journey | add row | then publish `type` | payload |
|---|---|---|---|
| domain-check-verification | `KARZA_DOMAIN_CHECK: domain-check-verification` | `KARZA_DOMAIN_CHECK` | `{ "organizationName":"Acme", "individualName":"A B", "email":"a@acme.com", "consent":"Y" }` |
| negative-area-verification | `ENT_KARZA_NEGATIVE_AREA_TAGGING: negative-area-verification` | `ENT_KARZA_NEGATIVE_AREA_TAGGING` | `{ "addressId":"ADDR-1", "consent":"Y" }` |
| emandate-autopay-setup | `EMANDATE_AUTOPAY: emandate-autopay-setup` | `EMANDATE_AUTOPAY` | `{ "invoiceNo":"INV-1", "customerId":"CUST-1" }` |
| emandate-cancel | `EMANDATE_CANCEL: emandate-cancel` | `EMANDATE_CANCEL` | `{ "invoiceNo":"INV-1" }` |

(These four also need their journeys loadable: run in **registry** mode — start `:platform:journey-registry`
which seeds all bundled journeys — or add the journey file to the engine's `journey-resources` list.
Karza-backed ones need the `verification` capability + the Karza mock (19105); e-mandate needs the `mandate`
capability. The three Karza verification journeys share the `verification` capability — only `vehicle-rc`
ships a route row.)

---

## Quick index

| # | journey | how | entry |
|---|---|---|---|
| A1 | device-validation | Kafka | `orig.device-validation.v1` · `DEVICE_VALIDATION` |
| A2 | loan-origination | Kafka | `orig.sfdc.pl.v1` · `PERSONAL_LOAN` |
| A3 | vehicle-rc-verification | Kafka | any orig topic · `VEHICLE_RC` |
| A4 | employee-lwd-update | Kafka / file | `orig.employee-lwd-update.v1` · `EMPLOYEE_LWD_UPDATE` |
| B1 | imps-disbursal | HTTP | `POST :8081/api/v1/impsFT` |
| B2 | lms-utilities | HTTP | `POST :8081/api/v1/callLmsUtilities` |
| C1 | device-validation (SOAP) | HTTP | `POST :8080/api/v1/sfdc/outbound-messages` |
| D | domain-check · negative-area · e-mandate | Kafka | add a `type-to-journey` row first |
