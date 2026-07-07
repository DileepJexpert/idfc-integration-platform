# AS-TESTED LOG — manual journey tests (living document)

One row per journey we actually drive by hand. Each entry captures **exactly** what was sent, where it
entered, the topic/route, how we verified, the expectation, and the real result. Append a new section as we
test each journey. Everything here is pulled from the **current code** (not the older test docs), with
file:line where it matters.

## Status

| # | Journey | Entry | Status | Terminal seen |
|---|---|---|---|---|
| 1 | device-validation (Apple SOAP) | SOAP `:8080` | ✅ TESTED — passed on 2nd attempt | `n_valid` → `COMPLETED_APPROVED` |
| 2 | vehicle-rc-verification | Kafka `orig.sfdc.pl.v1` | ⏳ READY (needs `verification` cap started) | expect `n_proceed` → `COMPLETED_APPROVED` |

---

## Shared — bring-up, ports, verify

**Infra (mocks + Kafka + Aerospike) — start once, include `--remove-orphans` to avoid stale-container port clashes:**
```powershell
docker compose -f docker-compose.infra.yml -p idfc-integration-platform up -d --remove-orphans
docker ps --format "{{.Names}}  {{.Ports}}"     # eyeball every mock has a HOST port mapped (e.g. 0.0.0.0:9105->8080)
```

**Services:**
```powershell
.\run-services.ps1 -Clean      # engine :8082 + edges + capabilities, local profile, classpath journeys
```

**Ops verify (used for every Kafka/SOAP journey):**
```
# search by any id (runId | correlationId | notificationId | sfdcRecordId) -> returns run summary incl. runId + status
curl --location 'http://localhost:8082/ops/runs/search?key=<ID>' --header 'X-Ops-Token: dev-ops-token' --header 'X-User-Id: you@bank'
# full node-by-node timeline
curl --location 'http://localhost:8082/ops/runs/<runId>'          --header 'X-Ops-Token: dev-ops-token' --header 'X-User-Id: you@bank'
```
- runId is always `ji-<correlationId>`.
- Status vocabulary: `COMPLETED_APPROVED` (approved) · `COMPLETED_DECLINED` (business "no") · `FAILED_SFDC_NOTIFIED` (technical failure, SFDC told) · `RUNNING`.

**Ports (from code):** engine 8082 · SFDC edge 8080 · digital edge 8081 · Kafka broker host `29092` (container `idfc-kafka` internal `9092`) · Kafka UI 8085 · Aerospike 3000. Vendor mocks: device-validation **9106**, Karza **9105**, NSDL 9104, bureau 9101-03, Fusion 9107.

---

## 1. device-validation — Apple post-disbursal via SFDC SOAP  ✅ TESTED

**Entry:** real SFDC SOAP door (HTTP). **Capability:** `device-validation` (ops `decideActivities`, `block`). **Vendor mock:** 9106.

**Command (Postman-importable):**
```
curl --location 'http://localhost:8080/api/v1/sfdc/outbound-messages' \
--header 'X-Auth-Token: dev-token' \
--header 'Content-Type: text/xml' \
--data-raw '<soapenv:Envelope xmlns:soapenv="http://schemas.xmlsoap.org/soap/envelope/" xmlns="http://soap.sforce.com/2005/09/outbound" xmlns:sf1="urn:sobject.enterprise.soap.sforce.com"><soapenv:Body><notifications><OrganizationId>00D0w0000008ec7EAA</OrganizationId><Notification><Id>04l7200000Daq5RAb3</Id><sObject><sf1:Id>a2T721100001IS65V3</sf1:Id><sf1:Request__c><![CDATA[{"imei":"431254356142345678","apiVersion":"2","paymentInfo":{"swipeOrLoanAmount":"23800.00","scheme":"2","loanTenure":"12"}}]]></sf1:Request__c><sf1:SVCNAME__c>Post_Disbursal_Apple</sf1:SVCNAME__c><sf1:VERSION__c>1.0</sf1:VERSION__c></sObject></Notification></notifications></soapenv:Body></soapenv:Envelope>'
```
- Edge replies **HTTP 200 `<Ack>true</Ack>`** once the message is durably on Kafka.

**Internal route (edge → engine):**
- Edge stamps envelope `type = Post_Disbursal_Apple` (verbatim from `SVCNAME__c`) and publishes to topic **`orig.device-validation.v1`** (`edges/.../application.yml:91`).
- Engine `type-to-journey`: `Post_Disbursal_Apple → device-validation` (`orchestration/.../application-local.yml:40`).
- **Dedup key = `Notification/Id`** (`DedupeService.java:26`). Re-sending the same `<Id>` is skipped. **Bump `<Id>` and `<sf1:Id>` for every new run** (this file uses `…RAb3` / `…65V3` — bump to `…RAb4` next).

**Verify:** search by the notificationId you sent:
```
curl --location 'http://localhost:8082/ops/runs/search?key=04l7200000Daq5RAb3' --header 'X-Ops-Token: dev-ops-token' --header 'X-User-Id: you@bank'
```

**Expectation:** Apple config is **block-only** (`validate:false, block:true, unblock:false`), so the plan runs only `n_block`. Path: `n_decide → n_gate_validate (skip) → n_gate_block → n_block → n_valid` = **`COMPLETED_APPROVED`**, terminal **`n_valid`**.

**ACTUAL RESULT (2026-07-06):**
- 1st run `ji-corr-363b722b-…`: **`FAILED_SFDC_NOTIFIED`**, terminal `n_block → ERROR (TRANSIENT)`. Root cause: an orphaned pre-rename container `idfc-mock-devicefin` was holding port **9106**, so the new `idfc-mock-devicevalidation` couldn't bind → vendor call refused. (Correct behaviour: classified TRANSIENT, retried, then failed cleanly and told SFDC.)
- Fix: `docker rm -f idfc-mock-devicefin` + start `mock-devicevalidation` (9106).
- 2nd run (fresh Notification Id): **`COMPLETED_APPROVED`**, terminal `n_valid`. ✅

**Levers for more device-validation cases:** brand is implicit in `SVCNAME__c`. `SAMSUNG` = validate+block+unblock (all 3 fire); `GODREJ`/`BOSCH` = serial-based; deviceId/imei value picks the mock's pass vs decline stub.

---

## 2. vehicle-rc-verification — Karza VAHAN RC via Kafka  ⏳ READY

**Entry:** Kafka (there is **no** SOAP door for VEHICLE_RC — routing is by the envelope's `type` field, so any consumed origination topic works; use `orig.sfdc.pl.v1`). **Capability:** `verification` (op `KARZA_VAHAN_RC`). **Vendor mock:** Karza 9105.

**⚠️ PREREQUISITE — the `verification` capability must be running.** It is now in `run-services.ps1`/`.sh`
(added alongside `communications` and `mandate`), so `git pull` + `.\run-services.ps1 -Clean` picks it up
automatically. Until you pull, start it by hand:
```powershell
java -jar capabilities\verification\build\libs\verification-0.1.0-SNAPSHOT.jar --spring.profiles.active=local --server.port=8102
```
Also confirm Karza mock is up: `docker ps --filter "name=karza"` → `idfc-mock-karza … 0.0.0.0:9105->8080/tcp`.
(Without the verification capability running, the run hangs at `n_vehicleRc` — nothing consumes `cap.verification.request.v1`.)

**Command — publish the canonical envelope to Kafka.**

Option A — **Kafka UI** (http://localhost:8085) → Topics → `orig.sfdc.pl.v1` → Produce Message →
Key = `rc-corr-0001`, Value =
```json
{
  "type": "VEHICLE_RC",
  "source": "SFDC",
  "orgId": "IDFC_RETAIL",
  "notificationId": "rc-0001",
  "sfdcRecordId": "rc-rec-0001",
  "correlationId": "rc-corr-0001",
  "occurredAt": "2026-07-07T10:00:00Z",
  "payloadContentType": "application/json",
  "payload": { "registrationNumber": "AB12CD1234", "consent": "Y" }
}
```

Option B — **CLI** (one line; `key|json`):
```powershell
docker exec -i idfc-kafka /opt/kafka/bin/kafka-console-producer.sh --bootstrap-server localhost:9092 --topic orig.sfdc.pl.v1 --property parse.key=true --property key.separator="|"
# then paste:
rc-corr-0001|{"type":"VEHICLE_RC","source":"SFDC","orgId":"IDFC_RETAIL","notificationId":"rc-0001","sfdcRecordId":"rc-rec-0001","correlationId":"rc-corr-0001","occurredAt":"2026-07-07T10:00:00Z","payloadContentType":"application/json","payload":{"registrationNumber":"AB12CD1234","consent":"Y"}}
```
**Bump `correlationId` + `notificationId` for each new run** (runId is `ji-<correlationId>`, so reuse would collide).

**Verify — WATCH THE KAFKA TOPICS (prod-style; no curl).** One publish, four topics, in order (Kafka UI → topic → Messages):

```
1. orig.sfdc.pl.v1              YOU publish (key=correlationId)  { type:VEHICLE_RC, payload:{registrationNumber:AB12CD1234, consent:Y} }
        │  engine routes VEHICLE_RC -> vehicle-rc-verification; n_vehicleRc input = {registrationNumber: context.registrationNumber, consent:'Y'}
        ▼
2. cap.verification.request.v1  ENGINE publishes (key=journeyInstanceId ji-rc-corr-0001)
        CapabilityRequest{ capabilityKey:verification, operation:KARZA_VAHAN_RC, input:{registrationNumber:AB12CD1234, consent:Y} }
        │  verification cap consumes; internally (HTTP to Karza mock :9105, not Kafka):
        │    KarzaVahanRcRequestMapper -> adds version:1.0 ; POST /karza/vahan-rc (OAuth) ;
        │    KarzaVahanRcResponseMapper -> {Status,result[]} ; VerificationEnvelope.ok -> {ISSUCCESS:True, DATA:{…}}
        ▼
3. cap.verification.response.v1 VERIFICATION CAP publishes (key=journeyInstanceId)
        { status:OK, result:{ ISSUCCESS:"True", DATA:{ Status:SUCCESS, result:[{ result:{ rcStatus:ACTIVE, blackListStatus:CLEAR } }] } } }
        │  engine stores at context.vehicleRc, evaluates n_rcDecision (ISSUCCESS && rcStatus==ACTIVE && blackListStatus==CLEAR) -> n_proceed
        ▼
4. orig.decision.v1             ENGINE publishes (key=applicationRef)  { decision:"VehicleRcApproved", status: COMPLETED_APPROVED }
```
Payload modifications per hop: (→2) engine wraps registrationNumber into a CapabilityRequest · (inside 2→3) verification cap adds `version:1.0`, calls Karza, reshapes `{metadata,resource_data}`→`{Status,result[]}`, wraps as `{ISSUCCESS,DATA}` · (→4) engine evaluates the branch and emits the decision.
Failure topics: Karza unreachable/permanent → `cap.verification.dlq.v1` · unroutable type → `orig.sfdc.pl.v1.dlq`.

(Local-only convenience, NOT for prod: the same run is also visible read-only in the ops UI / `GET /ops/runs/search?key=rc-0001`.)

**Expectation (from the journey JSON + Karza stubs):**
- Journey: `n_vehicleRc` (calls `verification.KARZA_VAHAN_RC`) → `n_rcDecision` branch:
  `ISSUCCESS=='True' && rcStatus=='ACTIVE' && blackListStatus=='CLEAR'` → `n_proceed`, else `n_decline`.
- `registrationNumber = AB12CD1234` → Karza mock `vahan-rc-pass` (rcStatus ACTIVE, blackListStatus CLEAR) → **`COMPLETED_APPROVED`**, terminal `n_proceed`, emit `VehicleRcApproved`.

**Levers:**
- `registrationNumber = XX00YY0000` → mock `vahan-rc-fail` (BLACKLIST) → **`COMPLETED_DECLINED`**, terminal `n_decline`, emit `VehicleRcDeclined`.
- Karza unreachable / mock down (9105) → technical failure → `n_rcError` / `FAILED_*`.

**ACTUAL RESULT:** _(fill in after you run it: runId, status, terminal node)_

---
<!-- APPEND NEXT JOURNEY BELOW THIS LINE -->
