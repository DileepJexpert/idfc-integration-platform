# IDFC Integration Platform — Manual Test Guide (Kafka UI + Postman)

## Overview

This document is the operator's manual for hand-driving the IDFC integration platform without writing code: you start journey runs by **producing JSON messages in Kafka UI** (or by calling the REST edges in Postman), you **advance and steer** each run by hand-crafting capability responses in Kafka UI, and you **observe** everything through the read-only Ops API and the decision/ops-event topics.

The platform is a set of thin **edges** (SFDC SOAP, Digital partner REST) that normalize inbound traffic into ONE shared `CanonicalEnvelope` and publish it to an origination topic. The **origination-journey engine** consumes that envelope, selects a journey from the envelope's `type` field, and orchestrates a DAG of **capabilities** over Kafka request/response topics. Terminal nodes emit a `JourneyDecision`. Two control-plane services sit alongside: the **journey-registry** (maker-checker authoring of journey definitions) and the **ops-query** API (a read window over run state, co-hosted in the engine process).

**The single most important mental model:** the engine routes on the envelope **`type`** field, NOT on the topic name. All four `orig.sfdc.*.v1` topics share one consumer group; publishing `type:"COMMERCIAL"` onto `orig.sfdc.pl.v1` still runs the COMMERCIAL mapping. A response is correlated back to a run by the JSON body's **`journeyInstanceId` + `nodeId`**, never by the Kafka message key.

> Per-journey test procedures (loan-origination, the three Karza verification journeys, the two e-mandate journeys, and the two demo journeys) are documented in their own sections. This preamble gives you the shared framing every one of them depends on.

---

## The three run modes

All modes require the dockerized infra first. Bring it up once; it is long-lived:

```bash
docker compose -f docker-compose.infra.yml up -d
```

This starts Kafka (host bootstrap `localhost:29092`), Aerospike (`3000`), **Kafka UI at http://localhost:8085** (cluster name `idfc`), CloudBeaver SQL UI (`8978`), and all vendor mocks (WireMock `19101`–`19107`, Oracle-XE FinnOne `1521`). Verify Kafka + Aerospike are healthy before starting services:

```bash
docker inspect --format '{{.State.Health.Status}}' idfc-kafka idfc-aerospike
```

### Mode A — Full-stack (edges + engine + capabilities on the host)

Use this to test the real REST edges end-to-end (SOAP/Digital → Kafka → engine → real mock capabilities → decision). Builds boot jars once, then launches every service as a `java -jar` host process on the `local` profile:

```bash
./gradlew bootJar                # build all boot jars once
./run-services.sh                # start registry+engine+edges+5 capabilities (classpath journeys)
./run-services.sh --registry     # OR: engine loads journeys from the registry seam (must publish one first)
./run-services.sh status         # ports / PIDs / up|booting|stopped
./run-services.sh logs origination-journey   # tail one service (grab instanceIds here)
./run-services.sh stop
```

`run-services.sh` starts: `journey-registry:8104`, `origination-journey:8082`, `sfdc-ingress-edge:8080`, `digital-partner-edge:8081`, `customer-party:8090`, `kyc:8091`, `bureau:8092`, `scoring:8093`, `lending-origination:8094`. (Container-image equivalent: `./demo.sh up`, which builds `idfc/*` images and runs `docker-compose.services.yml`; the host path above is the faster loop.)

In this mode the five loan capabilities run for real (mocked vendors), so their responses are produced automatically — you only publish the **starting envelope**. Note the mock capabilities can only ever emit `PERMANENT` on failure; to exercise `TRANSIENT`/`AMBIGUOUS` retry lanes you must hand-craft the response (Mode B).

### Mode B — Engine-only, manually driven (pure Kafka UI)

Use this to hand-drive a run node-by-node: you publish the starting envelope AND every `cap.<key>.response.v1`. Nothing but the engine runs, so each node stays pending until you publish its response by hand — which is exactly what lets you exercise every `status`/`errorClass` permutation, dedup paths, and the liveness sweeper.

```bash
./gradlew :orchestration:origination-journey:bootRun \
  --args='--spring.profiles.active=local --idfc.engine.journey-source=classpath --idfc.engine.state-store=in-memory'
```

`state-store=in-memory` means an engine restart clears all runs (clean slate between test cases). `journey-source=classpath` loads only `loan-origination.journey.json` by default — see the troubleshooting table for how to make the verification / e-mandate journeys reachable.

### Mode C — Demo features (device-validation + fusion-hcm file batch)

There is **no separate `demo` profile** — the two demo doors + journeys live in the engine's `local` profile (`application-local.yml`), loaded via classpath. Mode C is just Mode A/B **plus** the two demo capability apps that make real HTTP calls to `mock-devicevalidation` (19106) / `mock-fusion` (19107). The one-click `./run-services.sh` already starts all of this; the explicit commands are:

```bash
# engine with the demo rows (adds topics orig.device-validation.v1 / orig.employee-lwd-update.v1 and 2 demo journeys)
./gradlew :orchestration:origination-journey:bootRun \
  --args='--spring.profiles.active=local --idfc.engine.journey-source=classpath --idfc.engine.state-store=in-memory'

# the capabilities + the file-batch ingress edge
./gradlew :capabilities:device-validation:bootRun --args='--spring.profiles.active=local' &          # :8110
./gradlew :capabilities:fusion-hcm:bootRun --args='--spring.profiles.active=local' &                # :8111
./gradlew :edges:file-batch-edge:bootRun --args='--spring.profiles.active=local --file-batch.enabled=true' &  # :8112
```

Trigger the demos: `demo/run-demo1.sh` (fires SAMSUNG/GODREJ/BOSCH-decline/SAMSUNG-fail at `orig.device-validation.v1`) and `demo/run-demo2.sh` (drops the sample CSV into `demo/batch-inbox/`). Add a brand live with no rebuild by passing extra `--device-validation.brands.<BRAND>.*` CLI rows (see the device-validation section).

---

## Reference: ports, endpoints, topics

### Service ports & HTTP endpoints

| Service | Port | Endpoints (method + path) | Auth header |
|---|---|---|---|
| sfdc-ingress-edge | 8080 | `POST /api/v1/sfdc/outbound-messages` (SOAP XML, `consumes` text/xml·application/xml·application/soap+xml, `produces` text/xml) · `POST /api/v1/sfdc/decisions` (JSON) | `X-Auth-Token` |
| digital-partner-edge | 8081 | `POST /api/v1/digital/origination` (JSON) · `GET /api/v1/digital/applications/{applicationId}` | `X-Partner-Token` |
| origination-journey **engine** | 8082 | (no origination REST; consumes Kafka) — **co-hosts ops-query** | — |
| ops-query (in engine) | 8082 | `GET /ops/runs` · `GET /ops/runs/search?key=` · `GET /ops/runs/{runId}` | `X-Ops-Token` + `X-User-Id` |
| journey-registry | 8104 | `…/api/v1/journeys…` (maker-checker lifecycle, 13 endpoints) | `X-Registry-Token` + `X-User-Id` (writes) |
| customer-party / kyc / bureau / scoring / lending-origination | 8090 / 8091 / 8092 / 8093 / 8094 | health/metrics only; work over Kafka | — |
| device-validation / fusion-hcm / file-batch-edge | 8110 / 8111 / 8112 | capabilities + ingress edge; doors over Kafka | — |

> **SFDC ingress is SOAP-only.** The only origination door is `POST /api/v1/sfdc/outbound-messages` consuming a raw SOAP Outbound Message (XML). (The JSON `POST /api/v1/sfdc/notifications` call in `demo.sh` is stale and does not exist in the current controller.) For manual testing it is almost always simpler to skip the SOAP edge and publish the `CanonicalEnvelope` JSON straight to an origination topic in Kafka UI.

### Infra UIs & mocks

| Component | Host address | Note |
|---|---|---|
| Kafka bootstrap (host tools) | `localhost:29092` | in-cluster listener is `kafka:9092` |
| **Kafka UI** | http://localhost:8085 | cluster `idfc` — produce/consume all topics here |
| Aerospike | `localhost:3000` | run-dedup + idempotency store (when `state-store=aerospike`) |
| CloudBeaver (FinnOne/Oracle SQL) | http://localhost:8978 | host=`mock-finnone` port 1521 service `XEPDB1` user/pass `finnone` |
| mock-posidex / cibil / fico / nsdl / karza | 19101 / 19102 / 19103 / 19104 / 19105 | WireMock vendor stubs (loan + verification) |
| mock-devicevalidation / mock-fusion | 19106 / 19107 | demo vendor stubs |

### Topics

| Purpose | Topic(s) | Message key | Value |
|---|---|---|---|
| **Start a run** (origination) | `orig.sfdc.pl.v1`, `orig.sfdc.lap.v1`, `orig.sfdc.bl.v1`, `orig.sfdc.commercial.v1` | `notificationId` | `CanonicalEnvelope` JSON |
| Demo doors (Mode C) | `orig.device-validation.v1`, `orig.employee-lwd-update.v1` | `correlationId` | `CanonicalEnvelope` JSON |
| Capability **request** (engine→cap) | `cap.<key>.request.v1` | `journeyInstanceId` | `CapabilityRequest` JSON |
| Capability **response** (cap→engine) | `cap.<key>.response.v1` | `journeyInstanceId` | `CapabilityResponse` JSON |
| Journey **decision** (terminal) | `orig.decision.v1` | `applicationRef` | `JourneyDecision` JSON |
| Ops lifecycle events | `ops.journey.events.v1` | `journeyInstanceId` | `OpsEvent` JSON |
| Comms (SENDSMS route) | `comm.sms.send.v1` | `notificationId` | canonical envelope (no cap request/response) |
| Verification failure notify | `sfdc.response.notify.v1` | — | on `verification` capability ERROR |

Capability keys in use: `customer-party`, `kyc`, `bureau`, `scoring`, `lending-origination` (loan journey); `verification` (Karza journeys); `mandate` (e-mandate journeys); `payments` (stub — no consumer). Topic names are always `cap.<key>.request.v1` / `cap.<key>.response.v1`.

---

## Tokens & headers

Local/dev values below are the `application-local.yml` defaults. **Every service fails closed** (refuses to start) if its token env var is unset in any non-local profile.

| Surface | Header | Local/dev value | Env var (real deploys) |
|---|---|---|---|
| SFDC edge (SOAP + decisions) | `X-Auth-Token` | `dev-token` | `SFDC_EDGE_TOKEN` |
| Digital edge — CRED | `X-Partner-Token` | `cred-dev-token` | `CRED_TOKEN` |
| Digital edge — FLIPKART | `X-Partner-Token` | `flipkart-dev-token` | `FLIPKART_TOKEN` |
| Digital edge — GROWW | `X-Partner-Token` | `groww-dev-token` | `GROWW_TOKEN` |
| journey-registry | `X-Registry-Token` | `dev-registry-token` | `REGISTRY_AUTH_TOKEN` |
| ops-query | `X-Ops-Token` | `dev-ops-token` | `OPS_API_TOKEN` |
| Actor identity (registry writes; every ops call) | `X-User-Id` | any non-blank id, e.g. `maker@bank`, `checker@bank`, `ops.analyst@bank` | — (header IS the identity) |
| Trace (optional) | `X-Correlation-Id` | any value | — |

Notes: the ops token is deliberately a **different secret** from the registry token — one does not authorize the other. The digital edge derives the partner from the token (partner is never in the request body, so it cannot be spoofed). Registry writes (create/draft/submit/approve/reject) additionally require `X-User-Id`; reads and `/validate` do not.

---

## The CanonicalEnvelope (the message you publish to start a run)

Produce this JSON to an origination topic (e.g. `orig.sfdc.pl.v1`), **message key = `notificationId`**. Only `type` plus one of `correlationId`/`notificationId`/`applicationRef` are load-bearing to start a run; the rest are carried through. SFDC and Digital emit the identical shape — only `source` differs.

```json
{
  "transactionId": "tx-0001",
  "schemaVersion": "sfdc-ingress.v1",
  "source": "SFDC",
  "type": "PERSONAL_LOAN",
  "notificationId": "04l6D00000ABCdeQAF",
  "orgId": "00D6D00000020HoUAI",
  "sfdcRecordId": "a0X6D000001abcdEAA",
  "applicationRef": "APP-1001",
  "correlationId": "corr-abc-123",
  "originalCorrelationId": "corr-abc-123",
  "payloadRef": null,
  "payloadContentType": "application/json",
  "occurredAt": "2026-07-03T10:15:30Z",
  "payload": {
    "amount": 500000,
    "tenureMonths": 36,
    "pan": "ABCDE1234F"
  }
}
```

| Field | Type | Required? | What it does |
|---|---|---|---|
| `transactionId` | String | platform-added | Not read by the engine. DLQ envelopes literally set `"dlq"`. |
| `schemaVersion` | String | yes | `sfdc-ingress.v1` / `digital-partner.v1`; checked at load. |
| `source` | enum string | yes | `SFDC` or `DIGITAL` (demo scripts use `FILE_DEMO`). Applied onto the journey context and unknown enums fail closed. |
| `type` | String | **yes — routes the run** | Engine does `registry.resolveForType(type)`. Unmapped → `UnroutableTypeException` → poison → DLQ. |
| `notificationId` | String | yes in practice | Kafka key; part of dedup + instanceId fallback. |
| `orgId` | String | optional to engine | SFDC edge requires a KNOWN org (`ORG1`, `IDFC_RETAIL`, `IDFC_BUSINESS`, `00D6D00000020HoUAI`, `00DC40000014dS1MAI`). |
| `sfdcRecordId` | String | optional | Ops search key; `null` on digital. |
| `applicationRef` | String | optional | Decision key; engine falls back to `notificationId`. `null` on SFDC SOAP, set on digital. |
| `correlationId` | String | optional | Trace id + **highest-priority dedup/instanceId seed**. |
| `originalCorrelationId` | String | optional | Second-priority instanceId seed. |
| `payloadRef` | String | optional | S3 claim-check ref; `null` inline. Not read by engine. |
| `payloadContentType` | String | optional | e.g. `application/json`. |
| `occurredAt` | Instant | optional | **Not read by the engine.** |
| `payload` | Map | optional | Inline business body. Applied to the journey context FIRST, then identity fields (`applicationRef`,`type`,`orgId`,`correlationId`,`notificationId`,`sfdcRecordId`,`source`) are layered on top and are authoritative (body keys cannot shadow them). |

**instanceId derivation** (deterministic; same envelope → same run): `dedupKey = firstNonNull(correlationId, originalCorrelationId, notificationId, applicationRef, "unknown")`, then `instanceId = "ji-" + dedupKey`. `store.insertIfAbsent(instance)` is the exactly-once start gate: a redelivery with the same key is logged `journey.start.duplicate` and dropped. **Grab the exact instanceId from the engine's `journey.start instanceId=…` log line** — you need it for every capability response.

---

## The CapabilityResponse (the message you hand-craft to advance a run)

When a run reaches a task node the engine publishes a `CapabilityRequest` on `cap.<key>.request.v1` and waits. In Mode B (no real capabilities) you supply the answer by producing a `CapabilityResponse` on `cap.<key>.response.v1`:

```json
{
  "journeyInstanceId": "ji-corr-abc-123",
  "correlationId": "corr-abc-123",
  "nodeId": "n_score",
  "capabilityKey": "scoring",
  "status": "OK",
  "result": {
    "decision": "APPROVED",
    "score": 780,
    "reasons": ["bureauScore 780 >= threshold 700", "fico=750"]
  },
  "errorClass": null
}
```

For a failure, set `"status": "ERROR"`, `"result": {}`, and `"errorClass"` to one of `TRANSIENT` / `PERMANENT` / `AMBIGUOUS` (a null errorClass on ERROR is treated as `AMBIGUOUS`).

**How the engine correlates a response to a pending node:**
1. `store.find(journeyInstanceId)` — **`journeyInstanceId` selects the run.** Unknown id → logged and dropped.
2. `def.node(nodeId)` — **`nodeId` selects the DAG node.** So the matching pair is `journeyInstanceId` + `nodeId`, both read from the **JSON body**.
3. `status`: `ERROR` → retry/fail path (using `errorClass`); `OK` → `recordResult` binds `result` to the node's `output` context key and advances successors.

Critical: **the Kafka message key is NOT used for correlation** — set it to the `journeyInstanceId` (or leave it blank), only the body matters. `capabilityKey` and `correlationId` are echoed/logged but not part of the match; set them correctly for clean ops. Each `(journeyInstanceId, nodeId)` is effectively consumed once — a duplicate/late response, or one for a run already terminal, is dropped. `nodeId` must be an exact node id in the pinned journey definition, and `result` must carry the raw keys the real capability would emit (the per-journey sections list them).

---

## Locked status vocabulary

The ops-query `status` is **computed** server-side from raw state + terminal outcome + notify state — do not confuse a business decline with a failure. There are exactly five values:

| `status` | Meaning |
|---|---|
| `RUNNING` | live; still executing (surfaced as `stuck` via `stuckOnly`/`sweepDeadline` once past threshold) |
| `COMPLETED_APPROVED` | terminal `completed` status → decision `APPROVED` (a green success) |
| `COMPLETED_DECLINED` | terminal `rejected` status → decision `REJECTED` — a **normal business decline, not red** |
| `FAILED_SFDC_NOTIFIED` | run FAILED and the channel WAS told (`sfdcNotified=SENT`) |
| `FAILED_NOTIFY_PENDING` | run FAILED but the channel was NOT yet told (`sfdcNotified=PENDING`) |

Supporting enums: `sfdcNotified` = `NONE | PENDING | SENT`. `JourneyDecision.outcome` = `APPROVED | REJECTED | ERROR`. Terminal-status → outcome map: `completed→APPROVED`, `rejected→REJECTED`, `failed→ERROR`, unknown→fail-closed ERROR. A run force-failed by the liveness sweeper ends `FAILED_SFDC_NOTIFIED` with `terminalOutcome=ERROR`, `terminalNodeId=__timeout__`.

---

## How to produce / consume in Kafka UI

Open **http://localhost:8085** → cluster **`idfc`**.

**Produce (start a run / answer a capability):** Topics → pick the topic → **Produce Message**. Set **Key** (origination: `notificationId`; cap response: `journeyInstanceId` or blank), paste the JSON into **Value**, leave headers empty, Send. The topics are auto-created; if a topic is missing, produce once to create it (or start the consuming service first — `auto-offset-reset` is `earliest`, so a run started before the engine is up will still be picked up).

**Consume (watch results):** Topics → pick the topic → **Messages** tab. Set the offset to **From beginning** (`earliest`) to see historical messages. Useful watches: `orig.decision.v1` (final decisions, key=`applicationRef`), `ops.journey.events.v1` (per-node lifecycle), `cap.<key>.request.v1` (what the engine is asking for — copy the `journeyInstanceId`/`nodeId` from here into your response), and the `*.dlq` topics for poison/permanent failures.

**Typical Mode B loop:** produce envelope to `orig.sfdc.pl.v1` → read `cap.customer-party.request.v1` for the `journeyInstanceId`+`nodeId` → produce OK response to `cap.customer-party.response.v1` → repeat for `kyc`, `bureau`, `scoring` → the branch fires → (if approved) answer `lending-origination` → read the decision on `orig.decision.v1`.

---

## How to set up Postman

Create an **environment** with these variables, then reference them as `{{var}}`:

| Variable | Value (local/dev) |
|---|---|
| `sfdcBase` | `http://localhost:8080` |
| `digitalBase` | `http://localhost:8081` |
| `registryBase` | `http://localhost:8104` |
| `opsBase` | `http://localhost:8082` |
| `sfdcToken` | `dev-token` |
| `partnerToken` | `cred-dev-token` |
| `registryToken` | `dev-registry-token` |
| `opsToken` | `dev-ops-token` |
| `actor` | `maker@bank` (use a different `checker@bank` for approve/reject) |

Add tokens as **collection-level headers** so every request inherits them (`X-Auth-Token: {{sfdcToken}}`, `X-Partner-Token: {{partnerToken}}`, `X-Registry-Token: {{registryToken}}`, `X-Ops-Token: {{opsToken}}`, `X-User-Id: {{actor}}`). For JSON endpoints set `Content-Type: application/json`; for the SFDC SOAP endpoint set `Content-Type: text/xml` and put the raw SOAP Outbound Message in a **raw/XML** body. Example digital origination call:

```
POST {{digitalBase}}/api/v1/digital/origination
X-Partner-Token: {{partnerToken}}
Content-Type: application/json

{ "requestId":"req-1", "applicationRef":"APP-HIGH-1", "type":"PERSONAL_LOAN", "orgId":"ORG1", "payload":{"amount":300000,"pan":"ABCDE1234F","name":"Asha"} }
```

Expected: `200` `{ "applicationId":"DIG-CRED-APP-HIGH-1", "status":"ACK_PROCESSED", "detail":... }`. Poll status with `GET {{digitalBase}}/api/v1/digital/applications/DIG-CRED-APP-HIGH-1` (same `X-Partner-Token`; another partner's id reads 404). See the per-edge sections for the full status-code matrix (401 UNAUTHENTICATED, 400 INVALID, 422 UNROUTABLE, 503 RETRY).

## Table of contents

1. [SFDC Ingress Edge (REST + Kafka)](#sec-sfdc-edge)
2. [Digital Partner Edge (REST)](#sec-digital-edge)
3. [Journey: loan-origination (PL / LAP / BL / Commercial)](#sec-loan-origination)
4. [Journey: vehicle-rc-verification](#sec-vehicle-rc)
5. [Journey: negative-area-verification](#sec-negative-area)
6. [Journey: domain-check-verification](#sec-domain-check)
7. [Journey: payment-execution (IMPS / UPI_MANDATE / BILL_PAY / unsupported)](#sec-payment-execution)
8. [Journey: emandate-autopay-setup](#sec-emandate-autopay)
9. [Journey: emandate-cancel (found / not-found)](#sec-emandate-cancel)
10. [Journey: device-validation (SFDC real entry + brand-as-config demo door)](#sec-device-validation)
11. [Demo: employee-lwd-update file-batch](#sec-employee-lwd)
12. [Control plane: journey-registry maker-checker (Postman)](#sec-registry)
13. [Control plane: ops read window /ops (Postman)](#sec-ops)


---

<a id="sec-sfdc-edge"></a>

---

## SFDC Ingress Edge (REST + Kafka)

This section exercises the **assisted (SFDC) origination door** end-to-end: the SOAP Outbound-Message ingress, the decision-callback, the dedupe/ACK state machine, fail-closed routing, and the downstream `loan-origination` journey the edge feeds. All SFDC origination types (`PERSONAL_LOAN`, `LAP`, `BUSINESS_LOAN`, `COMMERCIAL`, `Inbound_Wrapper`) resolve to the **same** engine journey `loan-origination@1`; only the topic differs.

### Conventions used in every case below

**Services / tokens (local profile):**

| Thing | Value |
|---|---|
| SFDC edge base | `http://localhost:8080` |
| SOAP ingress | `POST /api/v1/sfdc/outbound-messages` |
| Decision callback | `POST /api/v1/sfdc/decisions` |
| Edge auth header | `X-Auth-Token: dev-token` (fail-closed; `SFDC_EDGE_TOKEN`) |
| Ops API base | `http://localhost:8082/ops` |
| Ops headers | `X-Ops-Token: dev-ops-token` + `X-User-Id: ops.analyst@bank` |

**Topics:** `PERSONAL_LOAN`/`Inbound_Wrapper` → `orig.sfdc.pl.v1`; `LAP` → `orig.sfdc.lap.v1`; `BUSINESS_LOAN` → `orig.sfdc.bl.v1`; `COMMERCIAL` → `orig.sfdc.commercial.v1`; `SENDSMS` → `comm.sms.send.v1`. Edge DLQ: `orig.sfdc.dlq.v1`. Edge publish **key = `notificationId`**.

**Two test modes** (referenced as "Full-stack" and "Engine-only" below):
- **Full-stack** = capabilities + WireMock running; you steer downstream outcomes with payload levers.
- **Engine-only** = no capabilities running; you steer by hand-publishing `cap.<key>.response.v1` messages. These require the **live `journeyInstanceId`**. The SOAP path stamps a *generated* `correlationId` on the envelope, so `instanceId = "ji-" + <generated corr>` is **not predictable** from the request — read it from the engine `journey.start instanceId=...` log line, or from `GET /ops/runs/search?key=<notificationId|sfdcRecordId>`. To get a **deterministic** instanceId for engine-only steering, use the "Kafka direct-publish" entry style (publish the `CanonicalEnvelope` yourself to the origination topic with a fixed `correlationId` → `instanceId = "ji-" + correlationId`). Both entry styles are shown.

**`loan-origination@1` node chain:** `n_customer` (`customer-party.resolve`) → `n_kyc` (`kyc.verify`) → `n_bureau` (`bureau.pull`) → `n_score` (`scoring.decide`) → `n_decide` (branch on `context.scoring.decision == 'APPROVED'`) → `n_book` (`lending-origination.book`) → `n_done` (terminal, `completed`/APPROVED, emit `LoanBooked`); default arm → `n_reject` (terminal, `rejected`/REJECTED, emit `LoanRejected`).

**Ops status vocabulary:** `RUNNING`, `COMPLETED_APPROVED`, `COMPLETED_DECLINED`, `FAILED_SFDC_NOTIFIED`, `FAILED_NOTIFY_PENDING`.

**Base SOAP envelope** (reused below; change only `<Id>`, `<OrganizationId>`, `<SVCNAME__c>`, and the `Request__c` CDATA):

```xml
<soapenv:Envelope
    xmlns:soapenv="http://schemas.xmlsoap.org/soap/envelope/"
    xmlns="http://soap.sforce.com/2005/09/outbound"
    xmlns:sf1="urn:sobject.enterprise.soap.sforce.com">
  <soapenv:Header/>
  <soapenv:Body>
    <notifications>
      <OrganizationId>00D6D00000020HoUAI</OrganizationId>
      <ActionId>OUTID1240000000000</ActionId>
      <SessionId>SID</SessionId>
      <EnterpriseUrl>EnterpriseUrl</EnterpriseUrl>
      <PartnerUrl>PartnerUrl</PartnerUrl>
      <Notification>
        <Id>04l6D00000ABCdeQAF</Id>
        <sObject>
          <sf1:Id>a0X6D000001abcdEAA</sf1:Id>
          <sf1:CLIENTID__c>SFDC</sf1:CLIENTID__c>
          <sf1:EXECMODE__c>ASYNC</sf1:EXECMODE__c>
          <sf1:Request__c><![CDATA[{"pan":"ABCDE1234F","name":"ASHA RAO","amount":500000,"tenureMonths":36,"negativeFlags":[]}]]></sf1:Request__c>
          <sf1:SVCNAME__c>PERSONAL_LOAN</sf1:SVCNAME__c>
          <sf1:VERSION__c>1.0</sf1:VERSION__c>
        </sObject>
      </Notification>
    </notifications>
  </soapenv:Body>
</soapenv:Envelope>
```

Durable-accept response (HTTP 200):
```xml
<soapenv:Envelope xmlns:soapenv="http://schemas.xmlsoap.org/soap/envelope/">
  <soapenv:Body>
    <notificationsResponse xmlns="http://soap.sforce.com/2005/09/outbound">
      <Ack>true</Ack>
    </notificationsResponse>
  </soapenv:Body>
</soapenv:Envelope>
```

---

## A. Routing: one case per SVCNAME (edge publish target)

Each case below is the **same** base envelope with a different `<SVCNAME__c>` (and matching `<OrganizationId>`). "Drive to outcome" here is purely the edge's publish decision; the downstream journey outcome is covered in section B.

### A1. SVCNAME `PERSONAL_LOAN` → `orig.sfdc.pl.v1`

- **Entry (REST):** `POST http://localhost:8080/api/v1/sfdc/outbound-messages`

| Header | Value |
|---|---|
| `X-Auth-Token` | `dev-token` |
| `Content-Type` | `text/xml` |

Body = base envelope (SVCNAME `PERSONAL_LOAN`, org `00D6D00000020HoUAI`).
- **Drive to outcome:** no lever needed — `NEW` dedup key → publish.
- **Expected result:** HTTP 200 `<Ack>true</Ack>`, disposition `ACK_PROCESSED`. Consume `orig.sfdc.pl.v1`: one `CanonicalEnvelope`, key `04l6D00000ABCdeQAF`, `source:"SFDC"`, `type:"PERSONAL_LOAN"`, `schemaVersion:"sfdc-ingress.v1"`, `applicationRef:null`, inline `payload` = the CDATA JSON. Engine starts run `loan-origination`. **Verify:** `GET /ops/runs/search?key=04l6D00000ABCdeQAF` → one `RunSummaryDto`, `journeyKey:"loan-origination"`.

### A2. SVCNAME `LAP` → `orig.sfdc.lap.v1`
- **Entry (REST):** base envelope, `<sf1:SVCNAME__c>LAP</sf1:SVCNAME__c>`, `<Id>04l6D00000ABCdeQA1</Id>`.
- **Drive to outcome:** none (NEW).
- **Expected result:** 200 `<Ack>true</Ack>`; envelope on `orig.sfdc.lap.v1` with `type:"LAP"`; run `loan-origination`. Verify by `key=04l6D00000ABCdeQA1`.

### A3. SVCNAME `BUSINESS_LOAN` → `orig.sfdc.bl.v1`
- **Entry (REST):** base envelope, `SVCNAME__c=BUSINESS_LOAN`, `<Id>04l6D00000ABCdeQA2</Id>`.
- **Expected result:** 200 `<Ack>true</Ack>`; envelope on `orig.sfdc.bl.v1`, `type:"BUSINESS_LOAN"`; run `loan-origination`.

### A4. SVCNAME `COMMERCIAL` → `orig.sfdc.commercial.v1`
- **Entry (REST):** base envelope, `SVCNAME__c=COMMERCIAL`, `<Id>04l6D00000ABCdeQA3</Id>`.
- **Expected result:** 200 `<Ack>true</Ack>`; envelope on `orig.sfdc.commercial.v1`, `type:"COMMERCIAL"`; run `loan-origination`.

### A5. SVCNAME `Inbound_Wrapper` → `orig.sfdc.pl.v1` (shares the PL topic)
- **Entry (REST):** base envelope, `SVCNAME__c=Inbound_Wrapper`, `<Id>04l6D00000ABCdeQA4</Id>`. (Use the account-creation CDATA from the golden fixture if you want a realistic body; the edge carries it opaque.)
- **Expected result:** 200 `<Ack>true</Ack>`; envelope on **`orig.sfdc.pl.v1`** (same topic as PERSONAL_LOAN) with `type:"Inbound_Wrapper"`. Engine `type-to-journey` maps `Inbound_Wrapper` → `loan-origination` (explicit; not a default).

### A6. SVCNAME `SENDSMS` → `comm.sms.send.v1` (comms, NOT origination)
- **Entry (REST):** use the SENDSMS golden fixture — `<OrganizationId>00DC40000014dS1MAI</OrganizationId>`, `SVCNAME__c=SENDSMS`, `<Id>04lC4000000AbCdEAO</Id>`, `Request__c` CDATA = the Task JSON (`Mobile__c`/`Description`/`Type=OTP`).
- **Drive to outcome:** none (NEW).
- **Expected result:** 200 `<Ack>true</Ack>`, `ACK_PROCESSED`. Envelope published to **`comm.sms.send.v1`** (a comms topic — **not** consumed by the origination engine, so **no `loan-origination` run starts**). `GET /ops/runs/search?key=04lC4000000AbCdEAO` returns `[]` (empty). Verify instead by consuming `comm.sms.send.v1` in Kafka UI.

---

## B. Downstream branch arms + failure classes (loan-origination via the PL door)

For B-cases the **Entry** is shown in **both** styles. Use the SOAP entry to exercise the real edge; use the Kafka direct-publish entry when you want a deterministic `journeyInstanceId` for engine-only steering.

**Kafka direct-publish entry (shared shape)** — topic `orig.sfdc.pl.v1`, key = `notificationId`, value:
```json
{
  "transactionId": "tx-0001",
  "schemaVersion": "sfdc-ingress.v1",
  "source": "SFDC",
  "type": "PERSONAL_LOAN",
  "notificationId": "04l6D00000ABCdeQAF",
  "orgId": "00D6D00000020HoUAI",
  "sfdcRecordId": "a0X6D000001abcdEAA",
  "applicationRef": "APP-1001",
  "correlationId": "corr-sfdc-approved-1",
  "originalCorrelationId": "corr-sfdc-approved-1",
  "payloadRef": null,
  "payloadContentType": "application/json",
  "occurredAt": "2026-07-03T10:15:30Z",
  "payload": { "pan": "ABCDE1234F", "name": "ASHA RAO", "amount": 500000, "tenureMonths": 36, "negativeFlags": [] }
}
```
With `correlationId:"corr-sfdc-approved-1"` the run id is **`ji-corr-sfdc-approved-1`**.

### B1. APPROVED arm → `n_book` → `n_done` (COMPLETED_APPROVED)

- **Entry (REST):** base SOAP envelope; CDATA `{"pan":"ABCDE1234F","name":"ASHA RAO","amount":500000,"negativeFlags":[]}` (PAN without `"LOW"`, empty `negativeFlags`).
- **Entry (Kafka):** direct-publish shape above (`corr-sfdc-approved-1`).
- **Drive to outcome — Full-stack:** PAN/applicationRef contain no `"LOW"` (→ bureauScore 780 ≥ 700) **and** `payload.negativeFlags` empty → scoring `decision="APPROVED"`. No WireMock lever needed (the five mocks always succeed).
- **Drive to outcome — Engine-only:** publish in order to each `cap.<key>.response.v1` (key = `ji-corr-sfdc-approved-1`):

```json
{ "journeyInstanceId":"ji-corr-sfdc-approved-1","correlationId":"corr-sfdc-approved-1","nodeId":"n_customer","capabilityKey":"customer-party","status":"OK","result":{"crn":"CRN-ABCDE1234F","customerId":"CUST-ABCDE1234F","customerName":"ASHA RAO","customerStatus":"ACTIVE"},"errorClass":null }
```
```json
{ "journeyInstanceId":"ji-corr-sfdc-approved-1","correlationId":"corr-sfdc-approved-1","nodeId":"n_kyc","capabilityKey":"kyc","status":"OK","result":{"kycStatus":"VERIFIED","kycRefId":"KYC-ABCDE1234F"},"errorClass":null }
```
```json
{ "journeyInstanceId":"ji-corr-sfdc-approved-1","correlationId":"corr-sfdc-approved-1","nodeId":"n_bureau","capabilityKey":"bureau","status":"OK","result":{"bureauResults":[{"type":"CIBIL","score":780,"grade":"A","reportId":"RPT-1","source":"CIBIL","fetchedAt":"2026-07-03T10:15:31Z"}],"bureauScore":780,"bureauGrade":"A","reportId":"RPT-1"},"errorClass":null }
```
```json
{ "journeyInstanceId":"ji-corr-sfdc-approved-1","correlationId":"corr-sfdc-approved-1","nodeId":"n_score","capabilityKey":"scoring","status":"OK","result":{"decision":"APPROVED","score":780,"reasons":["bureauScore 780 >= threshold 700","fico=750"]},"errorClass":null }
```
```json
{ "journeyInstanceId":"ji-corr-sfdc-approved-1","correlationId":"corr-sfdc-approved-1","nodeId":"n_book","capabilityKey":"lending-origination","status":"OK","result":{"loanId":"LN-APP-1001","status":"BOOKED"},"errorClass":null }
```
- **Expected result:** transitions `n_customer→n_kyc→n_bureau→n_score→n_decide(APPROVED arm)→n_book→n_done`. Terminal node `n_done`, `terminalOutcome:"APPROVED"`, emit `LoanBooked`. Decision on `orig.decision.v1` (key `applicationRef`) `outcome:"APPROVED"`, `loanId:"LN-APP-1001"`, `terminalNodeId:"n_done"`. Ops status **`COMPLETED_APPROVED`**. **Verify:** `GET /ops/runs/search?key=corr-sfdc-approved-1` then `GET /ops/runs/{runId}`.

### B2. DECLINED arm → `n_reject` (COMPLETED_DECLINED)

- **Entry (REST):** base envelope; CDATA `{"pan":"ABCDELOW1F","name":"ASHA RAO","amount":500000,"negativeFlags":[]}` (PAN contains `"LOW"`). `<Id>04l6D00000ABCdeDEC</Id>`.
- **Entry (Kafka):** direct-publish shape with `correlationId:"corr-sfdc-declined-1"`, `payload.pan:"ABCDELOW1F"` (or `negativeFlags:["FRAUD"]`). → `ji-corr-sfdc-declined-1`.
- **Drive to outcome — Full-stack:** PAN (or applicationRef) containing `"LOW"` → bureauScore 540 < 700 → scoring `decision="REJECTED"`; **or** non-empty `negativeFlags` → REJECTED regardless of score.
- **Drive to outcome — Engine-only:** same `n_customer`/`n_kyc` OK as B1, then `n_bureau` OK with `bureauScore:540,bureauGrade:"C"`, then:
```json
{ "journeyInstanceId":"ji-corr-sfdc-declined-1","correlationId":"corr-sfdc-declined-1","nodeId":"n_score","capabilityKey":"scoring","status":"OK","result":{"decision":"REJECTED","score":540,"reasons":["bureauScore 540 < threshold 700","fico=750"]},"errorClass":null }
```
(No `n_book` response — the default arm skips booking.)
- **Expected result:** `n_decide` takes **default** → `n_reject` (`rejected`), `terminalOutcome:"REJECTED"`, emit `LoanRejected`. Decision `outcome:"REJECTED"`, `loanId:null`, `terminalNodeId:"n_reject"`. Ops status **`COMPLETED_DECLINED`** (a normal completion, not a failure). No DLQ.

### B3. Capability failure — PERMANENT (FAILED)

- **Entry (Kafka direct-publish):** shape above with `correlationId:"corr-sfdc-perm-1"` → `ji-corr-sfdc-perm-1`.
- **Drive to outcome — Full-stack:** **no lever** — the five loan-origination mocks (`customer-party`, `kyc`, `bureau`, `scoring`, `lending-origination`) always return OK; there is no full-stack input that forces a capability ERROR in this journey. Use engine-only mode.
- **Drive to outcome — Engine-only:** drive OK responses up to any node, then publish an ERROR at that node. Example at `n_bureau`:
```json
{ "journeyInstanceId":"ji-corr-sfdc-perm-1","correlationId":"corr-sfdc-perm-1","nodeId":"n_bureau","capabilityKey":"bureau","status":"ERROR","result":{},"errorClass":"PERMANENT" }
```
- **Expected result:** `loan-origination` nodes declare **no retrySpec**, so the first ERROR fails the node for good → run FAILED, ERROR `JourneyDecision` emitted → SFDC notified. Ops status **`FAILED_SFDC_NOTIFIED`**, `terminalOutcome:"ERROR"`, `RunDetailDto.nodeStats[n_bureau].failureClass:"PERMANENT"`, `dlqTopicRef:"orig.sfdc.dlq.v1"`. (If the notify publish can't be confirmed: **`FAILED_NOTIFY_PENDING`**.)

### B4. Capability failure — TRANSIENT (FAILED, no retry in this journey)

- **Entry (Kafka direct-publish):** `correlationId:"corr-sfdc-tran-1"` → `ji-corr-sfdc-tran-1`.
- **Drive — Full-stack:** the delegating mocks only ever emit PERMANENT on error and have no error lever here; TRANSIENT must be hand-crafted (engine-only).
- **Drive — Engine-only:** OK up to `n_score`, then ERROR at `n_book`:
```json
{ "journeyInstanceId":"ji-corr-sfdc-tran-1","correlationId":"corr-sfdc-tran-1","nodeId":"n_book","capabilityKey":"lending-origination","status":"ERROR","result":{},"errorClass":"TRANSIENT" }
```
- **Expected result:** because `n_book` has no `retryOn` set, TRANSIENT does **not** retry — node fails immediately → run **FAILED**, `terminalOutcome:"ERROR"`, ops **`FAILED_SFDC_NOTIFIED`**, `nodeStats[n_book].failureClass:"TRANSIENT"`, `dlqTopicRef:"orig.sfdc.dlq.v1"`. (This documents that TRANSIENT is classified but not retried in `loan-origination`.)

### B5. Capability failure — AMBIGUOUS (FAILED)

- **Entry (Kafka direct-publish):** `correlationId:"corr-sfdc-amb-1"` → `ji-corr-sfdc-amb-1`.
- **Drive — Engine-only:** OK up to `n_kyc`, then ERROR at `n_bureau` with **`errorClass` omitted/null** (engine treats missing errorClass as AMBIGUOUS):
```json
{ "journeyInstanceId":"ji-corr-sfdc-amb-1","correlationId":"corr-sfdc-amb-1","nodeId":"n_bureau","capabilityKey":"bureau","status":"ERROR","result":{},"errorClass":null }
```
- **Expected result:** null errorClass → AMBIGUOUS; no retrySpec → node fails → run **FAILED**, ops **`FAILED_SFDC_NOTIFIED`**, `nodeStats[n_bureau].failureClass:"AMBIGUOUS"`.

### B6. Capability failure — BREAKER_OPEN (not reachable in this journey)

- **Status:** **No reachable trigger via the SFDC door in `loan-origination`.** BREAKER_OPEN is produced only by an engine-side circuit breaker on a node, and no node in `loan-origination@1` declares a breaker/retry policy (`n_book` carries only a `meter` on `finnone_pool`). A `CapabilityResponse.errorClass` cannot be `BREAKER_OPEN` (the `ErrorClass` enum is only `TRANSIENT`/`PERMANENT`/`AMBIGUOUS`), so it cannot be injected in engine-only mode either. Record as **N/A for this entry-point**; exercise it only in a journey that pins a circuit-breaker policy.

---

## C. Idempotency / duplicate resend (same `notificationId`) — all six `DedupePath` arms

All C-cases resend the **same** base SOAP envelope (`<Id>04l6D00000ABCdeQAF</Id>`). Publish-lease default = **60 s** (`idfc.edge.publish-lease-seconds`). Every arm returns HTTP 200 `<Ack>true</Ack>` (all these dispositions `acknowledge()`), but they differ in whether a second envelope is published. Verify "published exactly once" by counting messages with key `04l6D00000ABCdeQAF` on `orig.sfdc.pl.v1`, and inspect edge logs for the disposition.

### C1. `NEW` → publishes (ACK_PROCESSED)
- **Entry:** first-ever POST of the base envelope.
- **Expected:** disposition `ACK_PROCESSED`; exactly **one** envelope on `orig.sfdc.pl.v1`. Record status → `PUBLISHED`.

### C2. `IN_FLIGHT` → re-attach, no publish (ACK_DUPLICATE_INFLIGHT)
- **Entry:** re-POST the identical envelope **within 60 s** while the original is still `RECEIVED`/`IN_FLIGHT` (lease not expired).
- **Expected:** disposition `ACK_DUPLICATE_INFLIGHT`, HTTP 200 `<Ack>true</Ack>`, **no second publish** (still exactly one message on the topic).

### C3. `STALLED` → re-drives publish (ACK_PROCESSED)
- **Entry:** re-POST after the original crashed pre-confirm — record left in `RECEIVED`, **or** `IN_FLIGHT` **past** the 60 s lease, never confirmed on Kafka. (Simulate: kill the edge mid-publish, restart, resend; or wait out the lease on a wedged attempt.)
- **Expected:** `handleStalledRedelivery` → re-drives `processForPublish` → publishes; HTTP 200 `<Ack>true</Ack>`. This is the "crashed attempt, not a duplicate" path.

### C4. `PUBLISHED` → idempotent no-op (ACK_DUPLICATE_PUBLISHED)
- **Entry:** resend after the record is confirmed `PUBLISHED` (i.e. after C1 landed).
- **Expected:** disposition `ACK_DUPLICATE_PUBLISHED`, HTTP 200 `<Ack>true</Ack>`, **no re-publish**.

### C5. `DECIDED` → no push, no publish (ACK_DUPLICATE_DECIDED)
- **Entry:** first drive the record to `DECIDED` via the decision callback (see E1) for `notificationId=04l6D00000ABCdeQAF`, then resend the SOAP envelope.
- **Expected:** disposition `ACK_DUPLICATE_DECIDED`, HTTP 200 `<Ack>true</Ack>`, **no publish and no SFDC push** (the sink is already updated). `RecordStatus` `DECIDED` is terminal.

### C6. `FAILED` → re-attempts publish (ACK_PROCESSED, governed by C3/C5)
- **Entry:** resend after a prior attempt left the record `FAILED` (e.g. after a transient publish failure that did not DLQ).
- **Expected:** `handleFailedRedelivery` → `processForPublish` re-attempts the publish; `FAILED → IN_FLIGHT` is the single legal C3 transient re-enqueue transition. HTTP 200 `<Ack>true</Ack>` on success.

---

## D. Fail-closed & ACK dispositions (edge-level)

### D1. Bad `Request__c` JSON — one notification DLQ'd, batch still accepted (ACK_DLQ_PERMANENT)
- **Entry (REST):** base envelope but malformed CDATA: `<sf1:Request__c><![CDATA[{ not json ]]></sf1:Request__c>`, `<Id>04l6D00000ABCdeBAD</Id>`.
- **Drive:** none — parse failure is deterministic (`NotificationMappingException`, permanent).
- **Expected:** that one notification → `ACK_DLQ_PERMANENT`: routed to **`orig.sfdc.dlq.v1`** (envelope `transactionId:"dlq"`) + alert, and it **counts as accepted** — HTTP 200 `<Ack>true</Ack>` for the batch (does not sink sibling notifications). No `loan-origination` run. Verify: message present on `orig.sfdc.dlq.v1`; `GET /ops/runs/search?key=04l6D00000ABCdeBAD` → `[]`.

### D2. Unknown SVCNAME — fail closed (ACK_DLQ_PERMANENT)
- **Entry (REST):** base envelope, `<sf1:SVCNAME__c>MORTGAGE_XYZ</sf1:SVCNAME__c>`, `<Id>04l6D00000ABCdeUNK</Id>`.
- **Expected:** edge does `orgConfig.refresh()` + re-check **once** → still no routing row → `ConfigNotFoundException` (permanent) → `ACK_DLQ_PERMANENT` to `orig.sfdc.dlq.v1` + alert. HTTP 200 `<Ack>true</Ack>`. No origination publish.

### D3. Unknown OrganizationId — fail closed (ACK_DLQ_PERMANENT)
- **Entry (REST):** base envelope, `<OrganizationId>00DZZZUNKNOWN000</OrganizationId>`, `<Id>04l6D00000ABCdeORG</Id>` (SVCNAME valid). Known orgs: `ORG1`, `IDFC_RETAIL`, `IDFC_BUSINESS`, `00D6D00000020HoUAI`, `00DC40000014dS1MAI`.
- **Expected:** one config `refresh()` + recheck → still unknown → permanent → DLQ `orig.sfdc.dlq.v1` + alert, HTTP 200 `<Ack>true</Ack>`.

### D4. Missing required per-notification field (ACK_DLQ_PERMANENT)
- **Entry (REST):** base envelope but drop `<Id>` (or `<sf1:SVCNAME__c>`, or `<sf1:Request__c>`). `OutboundNotificationMapper.toEvent` requires `Notification/Id`, `OrganizationId`, `SVCNAME__c`, `Request__c`.
- **Expected:** `NotificationMappingException` (permanent) → that notification DLQ'd to `orig.sfdc.dlq.v1`, counts as accepted → HTTP 200 `<Ack>true</Ack>`.

### D5. Unparseable ENVELOPE — whole POST NAK'd (HTTP 500, no ACK)
- **Entry (REST):** send a body that is not well-formed SOAP XML at the envelope level, e.g. `<soapenv:Envelope><broken>`.
- **Expected:** `SoapParseException` → **HTTP 500** + SOAP `<Fault>` `faultstring="unparseable envelope: ..."`. **No `<Ack>`** — SFDC resends the entire message. Nothing published, nothing DLQ'd.

### D6. Missing / wrong token — HTTP 401
- **Entry (REST):** base envelope with `X-Auth-Token` omitted, or `X-Auth-Token: wrong`.
- **Expected:** **HTTP 401** + SOAP `<Fault>` `faultstring="invalid or missing token"`. Request never reaches the batch service.

### D7. Transient publish failure — do NOT ACK (RETRY_TRANSIENT)
- **Entry (REST):** base valid envelope while Kafka/claim-check is unavailable (stop the broker, or block `orig.sfdc.pl.v1`).
- **Drive:** `TransientEdgeException` (claim-check/kafka failure) or any unclassified `RuntimeException` → treated as transient.
- **Expected:** disposition `RETRY_TRANSIENT` — **does not acknowledge**: HTTP 200 with `<Ack>false</Ack>` (retry signal) — SFDC redelivers the entire batch. Record left `FAILED`/re-drivable (see C6). No DLQ yet.

### D8. C5 poison breaker — repeated transient redelivery flips to DLQ (ACK_DLQ_POISON)
- **Entry (REST):** resend the same failing envelope repeatedly (keep the transient fault of D7 in place) until redeliveries reach the poison threshold **5** (`poison-redelivery-threshold`).
- **Expected:** on the threshold crossing the disposition flips from `RETRY_TRANSIENT` to **`ACK_DLQ_POISON`**: HTTP 200 `<Ack>true</Ack>`, envelope dead-lettered to `orig.sfdc.dlq.v1` **as poison** + alert. Stops the resend storm.

### D9. C3 transient journey re-enqueue (REENQUEUED)
- **Context:** the C3 re-enqueue ceiling is `max-journey-retry=1`. A transient journey-side re-enqueue within the ceiling yields disposition `REENQUEUED` (acknowledges: HTTP 200 `<Ack>true</Ack>`) rather than a fresh publish. Reachable only when a transient condition triggers the single allowed `FAILED → IN_FLIGHT` re-enqueue; beyond one retry it escalates to the poison path (D8).

---

## E. Decision callback (`POST /api/v1/sfdc/decisions`) — C1 exactly-once push

### E1. New decision → CAS into DECIDED, push fires once (pushed=true)
- **Entry (REST):** `POST http://localhost:8080/api/v1/sfdc/decisions`

| Header | Value |
|---|---|
| `X-Auth-Token` | `dev-token` |
| `X-Correlation-Id` | `corr-decide-1` (optional, trace only) |
| `Content-Type` | `application/json` |

```json
{ "notificationId": "04l6D00000ABCdeQAF", "outcome": "APPROVED", "applicationId": "APP-1001", "terms": "36m@10.5%" }
```
(Precondition: a record exists for `04l6D00000ABCdeQAF` in a non-DECIDED state, e.g. after C1/A1.)
- **Expected:** CAS `→ DECIDED` succeeds → `sfdcResponse.pushDecision(...)` runs **exactly once**. HTTP 200 `{ "notificationId":"04l6D00000ABCdeQAF", "pushed":true, "detail":"transitioned to DECIDED and pushed to SFDC" }`.

### E2. Already-decided resend → idempotent, no push (pushed=false)
- **Entry (REST):** repeat the exact E1 body after E1 succeeded.
- **Expected:** record already `DECIDED` → returns false, **no second push**. HTTP 200 `{ ..., "pushed":false, "detail":"already decided; no push (C1)" }`.

### E3. Unknown record → no push (pushed=false)
- **Entry (REST):** decision body with a `notificationId` that was never ingested, e.g. `"notificationId":"04l6D00000NEVERSEEN"`.
- **Expected:** unknown record → returns false, no push. HTTP 200 `{ "notificationId":"04l6D00000NEVERSEEN", "pushed":false, "detail":"already decided; no push (C1)" }`.

### E4. Lost CAS race → no push (pushed=false)
- **Entry:** two concurrent E1 POSTs for the same `notificationId`. Only the winner transitions into `DECIDED`.
- **Expected:** exactly one response `pushed:true`; the loser `pushed:false` (`"already decided; no push (C1)"`). Push fired exactly once total.

### E5. Missing / wrong token → HTTP 401
- **Entry (REST):** E1 body with `X-Auth-Token` omitted or wrong.
- **Expected:** **HTTP 401** `{ "notificationId":"04l6D00000ABCdeQAF", "pushed":false, "detail":"invalid or missing token" }`. No CAS, no push.

---

## F. Stuck / liveness sweeper (run started via the SFDC door hangs)

### F1. Run hangs at a capability node → flagged stuck → force-failed
- **Entry (Kafka direct-publish):** publish the B-shape envelope with `correlationId:"corr-sfdc-stuck-1"` → `ji-corr-sfdc-stuck-1`, and then **do not** publish any `cap.*.response.v1` (engine-only, no capabilities running). The run parks at `n_customer`.
- **Drive to outcome:** none — let it sit. Budget `run-budget-seconds=900`; sweep `sweep-interval-ms=60000`; stuck threshold = 900−60 = **840 s**.
- **Expected result:**
  - **At ≥ 840 s (before sweep acts):** store state still `RUNNING` → ops status `RUNNING`, but flagged. `GET /ops/runs?stuckOnly=true` includes it; `RunSummaryDto.stuck=true`; `sweepDeadline = startedAt + 900s`.
  - **At ≥ 900 s (sweeper fires):** force-failed — ERROR `JourneyDecision` published FIRST (`outcome:"ERROR"`, `terminalNodeId:"__timeout__"`, `loanId:null`), `markSfdcNotified()`, then `fail("__timeout__", ERROR)` → state `FAILED`, ops event `run.sweptTimeout`. Ops status **`FAILED_SFDC_NOTIFIED`**, `RunDetailDto.terminalNodeId:"__timeout__"`, `terminalOutcome:"ERROR"`.
  - **Verify:** `GET /ops/runs/search?key=corr-sfdc-stuck-1` → runId; before 900 s `GET /ops/runs/{runId}` shows `stuck:true`,`sweepDeadline:<...>`; after 900 s shows `status:"FAILED_SFDC_NOTIFIED"`, `stuck:false`, `sweepDeadline:null`, `sfdcNotified:"SENT"`.

---

## G. Ops verification surface (for the runs the SFDC door created)

Use these against any run started above. All calls require `X-Ops-Token: dev-ops-token` + `X-User-Id: ops.analyst@bank`.

### G1. Exact-id search — by each of the four id families
- **Entry:** `GET http://localhost:8082/ops/runs/search?key=<id>` for each: `notificationId` (`04l6D00000ABCdeQAF`), `correlationId` (`corr-sfdc-approved-1`), `sfdcRecordId` (`a0X6D000001abcdEAA`), and `runId`.
- **Expected:** `List<RunSummaryDto>` newest-first (a re-sent business record can have several runs). Blank `key` → **HTTP 400** `{"error":"BAD_REQUEST","message":"query parameter 'key' must be a non-blank exact id (runId | correlationId | notificationId | sfdcRecordId)"}`.

### G2. Run detail
- **Entry:** `GET http://localhost:8082/ops/runs/{runId}`.
- **Expected:** `RunDetailDto` — `status`, `sfdcNotified` (`NONE|PENDING|SENT`), `terminalNodeId`, `terminalOutcome`, `transitions[]` (ordered by `seq`, `late` flag), `nodeStats[]` (`attempts`, `failureClass`), `dlqTopicRef` (`orig.sfdc.dlq.v1` only for FAILED_* statuses), `stuck`, `sweepDeadline`, `compensationOf`/`compensationPending`. Unknown runId → **404** empty body.

### G3. List + each filter
- **Entry / Expected:**
  - `GET /ops/runs` → `PageDto` (sort `startedAt` DESC), defaults `page=0,size=50`.
  - `?status=COMPLETED_APPROVED` (or `COMPLETED_DECLINED`, `RUNNING`, `FAILED_SFDC_NOTIFIED`, `FAILED_NOTIFY_PENDING`) → filtered; unknown status → **400** `unknown status 'BOGUS'`.
  - `?journeyKey=loan-origination` → exact match.
  - `?since=2026-07-03T00:00:00Z&until=2026-07-03T23:59:59Z` → `startedAt` window; bad instant → **400**.
  - `?stuckOnly=true` → only RUNNING runs past the 840 s threshold (F1).
  - `?page=0&size=25` → `size` clamped to `1..200`.

### G4. Auth negatives
- `GET /ops/runs` without `X-Ops-Token` → **401** `{"error":"UNAUTHENTICATED","message":"invalid or missing X-Ops-Token"}`.
- `GET /ops/runs` without `X-User-Id` → **401** `{"error":"UNAUTHENTICATED","message":"X-User-Id header is required for the ops API"}`. (Note: the ops token is a **different** secret from the registry token — the registry token does not authorize ops.)

---

**Coverage note (this entry-point):** Maker-checker (403 self-approve / 409 lifecycle / 422 validation) belongs to the journey-registry control plane, not the SFDC ingress door, and is out of scope for this section. `BREAKER_OPEN` (B6) has no reachable trigger through this door because `loan-origination@1` pins no circuit-breaker/retry policy.


---

<a id="sec-digital-edge"></a>

## Digital Partner Edge (REST)

The Digital Partner Edge is the synchronous partner door (`digital-partner-edge`, **port 8081**). It authenticates a partner by token, validates, dedupes, normalizes to the shared `CanonicalEnvelope` (`source=DIGITAL`), and publishes to the **same** `orig.sfdc.*.v1` topics the SFDC edge uses. Downstream the engine runs the **`loan-origination`** journey (all four digital types map to it), so every business outcome below is a `loan-origination` outcome; the only difference from SFDC is `source` and the entry door.

**Local/dev constants used throughout**

| Thing | Value |
|---|---|
| Edge base URL | `http://localhost:8081` |
| Partner tokens (local profile) | `CRED`→`cred-dev-token`, `FLIPKART`→`flipkart-dev-token`, `GROWW`→`groww-dev-token` |
| Ops base URL | `http://localhost:8082/ops` |
| Ops headers | `X-Ops-Token: dev-ops-token`, `X-User-Id: ops.analyst@bank` |
| Origination topics | `PERSONAL_LOAN`→`orig.sfdc.pl.v1`, `LAP`→`orig.sfdc.lap.v1`, `BUSINESS_LOAN`→`orig.sfdc.bl.v1`, `COMMERCIAL`→`orig.sfdc.commercial.v1` |
| Decision topic | `orig.decision.v1` (key = `applicationRef`) |
| Response DTO | `DigitalAck{ applicationId, status, detail }` |
| `applicationId` | deterministic: `"DIG-" + <partnerCode> + "-" + <applicationRef>` |

**Two load-bearing derivations a tester must control**
- `notificationId` = `requestId` (Kafka message key on the origination topic; primary dedup key).
- `correlationId` = the `X-Correlation-Id` header if present, else the edge generates `corr-<UUID>`. The engine derives `instanceId = "ji-" + correlationId`. **Always send `X-Correlation-Id` with a known value** so you know the `journeyInstanceId` for engine-only steering and for ops search.

**Not reachable from this edge (do not test here):** the mandate `found/not-found` arms, the payment `rail` arms, `Inbound_Wrapper`/`SENDSMS`. The digital routing table has only the four loan types; anything else is a `422 UNROUTABLE` at the edge (below).

---

### Origination — happy path APPROVED (ACK_PROCESSED → COMPLETED_APPROVED)

**Entry** (REST)

`POST http://localhost:8081/api/v1/digital/origination`

| Header | Value |
|---|---|
| `X-Partner-Token` | `cred-dev-token` |
| `X-Correlation-Id` | `corr-dig-appr-001` |
| `Content-Type` | `application/json` |

```json
{
  "requestId": "REQ-APPR-001",
  "applicationRef": "APP-APPR-001",
  "type": "PERSONAL_LOAN",
  "orgId": "IDFC_RETAIL",
  "payload": {
    "pan": "ABCDE1234F",
    "name": "Asha Rao",
    "amount": 500000,
    "tenureMonths": 36
  }
}
```

Immediate edge response: **200** `{"applicationId":"DIG-CRED-APP-APPR-001","status":"ACK_PROCESSED","detail":"normalized and published to the engine"}`. The edge publishes to `orig.sfdc.pl.v1`, key `REQ-APPR-001`, Kafka headers `correlationId=corr-dig-appr-001`, `notificationId=REQ-APPR-001`, `source=DIGITAL`. Engine start id = `ji-corr-dig-appr-001`.

**Drive to outcome**

- **Full-stack mode** (capability services running with their mock adapters — note: `loan-origination` uses in-process mock adapters, *not* WireMock): APPROVED requires `bureauScore ≥ 700` and empty `negativeFlags`. Use a `payload.pan` **and** `applicationRef` that do **not** contain the substring `"LOW"` (mock CIBIL returns 780/grade A), and omit `negativeFlags` (or send `[]`). Scoring returns `decision="APPROVED"` → branch `n_decide` takes the arm → `n_book` books `LN-APP-APPR-001` → `n_done`.

- **Engine-only manual mode** (no capabilities running): after the edge publishes, the engine dispatches `cap.customer-party.request.v1`. PUBLISH these five OK responses in order (topic = `cap.<key>.response.v1`, message key = `ji-corr-dig-appr-001` or blank). Each must echo `journeyInstanceId` + `nodeId`.

  1. → `cap.customer-party.response.v1`
  ```json
  {"journeyInstanceId":"ji-corr-dig-appr-001","correlationId":"corr-dig-appr-001","nodeId":"n_customer","capabilityKey":"customer-party","status":"OK","result":{"crn":"CRN-ABCDE1234F","customerId":"CUST-ABCDE1234F","customerName":"Asha Rao","customerStatus":"ACTIVE"},"errorClass":null}
  ```
  2. → `cap.kyc.response.v1`
  ```json
  {"journeyInstanceId":"ji-corr-dig-appr-001","correlationId":"corr-dig-appr-001","nodeId":"n_kyc","capabilityKey":"kyc","status":"OK","result":{"kycStatus":"VERIFIED","kycRefId":"KYC-ABCDE1234F"},"errorClass":null}
  ```
  3. → `cap.bureau.response.v1`
  ```json
  {"journeyInstanceId":"ji-corr-dig-appr-001","correlationId":"corr-dig-appr-001","nodeId":"n_bureau","capabilityKey":"bureau","status":"OK","result":{"bureauResults":[{"type":"CIBIL","score":780,"grade":"A","reportId":"RPT-1","source":"CIBIL","fetchedAt":"2026-07-03T10:15:30Z"}],"bureauScore":780,"bureauGrade":"A","reportId":"RPT-1"},"errorClass":null}
  ```
  4. → `cap.scoring.response.v1`
  ```json
  {"journeyInstanceId":"ji-corr-dig-appr-001","correlationId":"corr-dig-appr-001","nodeId":"n_score","capabilityKey":"scoring","status":"OK","result":{"decision":"APPROVED","score":780,"reasons":["bureauScore 780 >= threshold 700","fico=750"]},"errorClass":null}
  ```
  The engine auto-evaluates `n_decide` (`context.scoring.decision == 'APPROVED'`) and dispatches `cap.lending-origination.request.v1`. Then:
  5. → `cap.lending-origination.response.v1`
  ```json
  {"journeyInstanceId":"ji-corr-dig-appr-001","correlationId":"corr-dig-appr-001","nodeId":"n_book","capabilityKey":"lending-origination","status":"OK","result":{"loanId":"LN-APP-APPR-001","status":"BOOKED"},"errorClass":null}
  ```

**Expected result**
- Ops status vocabulary: **`COMPLETED_APPROVED`**. Terminal node **`n_done`**, terminal outcome **APPROVED**, emits `LoanBooked`.
- Transitions: `n_customer`→`n_kyc`→`n_bureau`→`n_score`→(`n_decide` arm)→`n_book`→`n_done`, all `COMPLETED`.
- Decision on `orig.decision.v1` (key `APP-APPR-001`): `outcome:"APPROVED"`, `loanId:"LN-APP-APPR-001"`, `terminalNodeId:"n_done"`, `source:"DIGITAL"`. The edge `DecisionConsumer` updates the status store and pushes the partner callback.
- No DLQ.

**Verify**
```
GET http://localhost:8082/ops/runs/search?key=corr-dig-appr-001
  -> [{ runId:"ji-corr-dig-appr-001", status:"COMPLETED_APPROVED", ... }]   (also searchable by key=REQ-APPR-001)
GET http://localhost:8082/ops/runs/ji-corr-dig-appr-001
  -> terminalNodeId:"n_done", terminalOutcome:"APPROVED", dlqTopicRef:null, transitions[...]
GET http://localhost:8081/api/v1/digital/applications/DIG-CRED-APP-APPR-001   (X-Partner-Token: cred-dev-token)
  -> { applicationId:"DIG-CRED-APP-APPR-001", applicationRef:"APP-APPR-001", partner:"CRED", outcome:"APPROVED", loanId:"LN-APP-APPR-001" }
```

---

### Origination — business DECLINE (ACK_PROCESSED → COMPLETED_DECLINED)

**Entry** (REST) — identical shape, force the reject arm.

`POST http://localhost:8081/api/v1/digital/origination`

| Header | Value |
|---|---|
| `X-Partner-Token` | `cred-dev-token` |
| `X-Correlation-Id` | `corr-dig-decl-001` |
| `Content-Type` | `application/json` |

```json
{
  "requestId": "REQ-DECL-001",
  "applicationRef": "APP-LOW-001",
  "type": "PERSONAL_LOAN",
  "orgId": "IDFC_RETAIL",
  "payload": {
    "pan": "LOWXX9999L",
    "name": "Ravi Kumar",
    "negativeFlags": ["DEFAULT_HISTORY"]
  }
}
```

**Drive to outcome**
- **Full-stack**: two independent levers, either suffices — (a) `payload.pan` or `applicationRef` **contains `"LOW"`** (case-insensitive) → mock CIBIL returns `bureauScore=540`/grade C (`540 < 700`); or (b) a **non-empty `payload.negativeFlags`**. Scoring returns `decision="REJECTED"` → `n_decide` falls through to `default` → `n_reject`.
- **Engine-only**: publish responses 1–3 exactly as APPROVED (customer-party, kyc, then bureau with `bureauScore:540, bureauGrade:"C"`), then a REJECTED scoring response — **no `n_book` response is needed** (that node is never reached):
  → `cap.scoring.response.v1`
  ```json
  {"journeyInstanceId":"ji-corr-dig-decl-001","correlationId":"corr-dig-decl-001","nodeId":"n_score","capabilityKey":"scoring","status":"OK","result":{"decision":"REJECTED","score":540,"reasons":["bureauScore 540 < threshold 700","fico=750"]},"errorClass":null}
  ```
  (Use `journeyInstanceId":"ji-corr-dig-decl-001"` and matching `nodeId` on responses 1–3.)

**Expected result**
- Ops status: **`COMPLETED_DECLINED`** (a normal completion, **not** a failure — teal, not red). Terminal node **`n_reject`**, outcome **REJECTED**, emits `LoanRejected`.
- Decision `orig.decision.v1`: `outcome:"REJECTED"`, `loanId:null`, `terminalNodeId:"n_reject"`. `dlqTopicRef:null`.

**Verify**
```
GET http://localhost:8082/ops/runs/search?key=corr-dig-decl-001  -> status:"COMPLETED_DECLINED"
GET http://localhost:8081/api/v1/digital/applications/DIG-CRED-APP-LOW-001 (X-Partner-Token: cred-dev-token)
  -> outcome:"REJECTED", loanId:null
```

---

### Origination — each routing arm (LAP / BUSINESS_LOAN / COMMERCIAL)

The four digital types differ only in the `type` string and destination topic; all run the same `loan-origination` journey with the same node ids, so the APPROVED / DECLINE / failure permutations above apply unchanged. Verify the edge routes correctly by watching which topic receives the envelope.

**Entry** (REST) — one call per arm; change `type`, `X-Correlation-Id`, `requestId`, `applicationRef`.

| Header | Value |
|---|---|
| `X-Partner-Token` | `flipkart-dev-token` |
| `X-Correlation-Id` | `corr-dig-lap-001` |
| `Content-Type` | `application/json` |

```json
{
  "requestId": "REQ-LAP-001",
  "applicationRef": "APP-LAP-001",
  "type": "LAP",
  "orgId": "IDFC_RETAIL",
  "payload": { "pan": "ABCDE1234F", "name": "Asha Rao" }
}
```

**Expected result / Verify (per arm)**

| `type` sent | Envelope published to | Returned `applicationId` | Engine journey |
|---|---|---|---|
| `PERSONAL_LOAN` | `orig.sfdc.pl.v1` | `DIG-FLIPKART-APP-…` | `loan-origination` |
| `LAP` | `orig.sfdc.lap.v1` | `DIG-FLIPKART-APP-LAP-001` | `loan-origination` |
| `BUSINESS_LOAN` | `orig.sfdc.bl.v1` | `DIG-FLIPKART-…` | `loan-origination` |
| `COMMERCIAL` | `orig.sfdc.commercial.v1` | `DIG-FLIPKART-…` | `loan-origination` |

Drive each to APPROVED/DECLINE using the two permutations above (engine-only nodeIds are identical: `n_customer`/`n_kyc`/`n_bureau`/`n_score`/`n_book`). Verify with `GET /ops/runs/search?key=corr-dig-lap-001`. (In Kafka UI, confirm the message landed on the expected topic — the engine consumes all four via one group, so journey selection is by the envelope `type`, not the topic.)

---

### Origination — 401 UNAUTHENTICATED (missing / unknown partner token)

**Entry** (REST)

`POST http://localhost:8081/api/v1/digital/origination`

| Header | Value |
|---|---|
| `X-Partner-Token` | *(omit, or send `bogus-token`)* |
| `Content-Type` | `application/json` |

```json
{
  "requestId": "REQ-401-001",
  "applicationRef": "APP-401-001",
  "type": "PERSONAL_LOAN",
  "orgId": "IDFC_RETAIL",
  "payload": { "pan": "ABCDE1234F" }
}
```

**Drive to outcome:** edge-terminal; no journey, no publish, no claim. Same in both modes.

**Expected result:** HTTP **401**, body `{"applicationId":null,"status":"UNAUTHENTICATED","detail":"unknown or missing partner token"}`. Nothing on any Kafka topic; no ops run.

**Verify:** `GET /ops/runs/search?key=corr-…` returns `[]` (there is no correlation id — the request was rejected before normalization).

---

### Origination — 400 INVALID (structurally invalid)

**Entry** (REST) — any of `requestId`, `applicationRef`, `type`, `orgId` blank/missing.

`POST http://localhost:8081/api/v1/digital/origination`

| Header | Value |
|---|---|
| `X-Partner-Token` | `cred-dev-token` |
| `Content-Type` | `application/json` |

```json
{
  "requestId": "REQ-400-001",
  "applicationRef": "",
  "type": "PERSONAL_LOAN",
  "orgId": "IDFC_RETAIL",
  "payload": { "pan": "ABCDE1234F" }
}
```

**Drive to outcome:** edge-terminal; validation happens after auth, before routing/claim. Same in both modes.

**Expected result:** HTTP **400**, body `{"applicationId":null,"status":"INVALID","detail":"requestId, applicationRef, type and orgId are required"}`. No publish, no claim burned, no run.

---

### Origination — 422 UNROUTABLE (unknown / unroutable type — fail-closed)

**Entry** (REST) — a `type` with no digital routing row (e.g. `SENDSMS`, `Inbound_Wrapper`, or a typo).

`POST http://localhost:8081/api/v1/digital/origination`

| Header | Value |
|---|---|
| `X-Partner-Token` | `cred-dev-token` |
| `X-Correlation-Id` | `corr-dig-422-001` |
| `Content-Type` | `application/json` |

```json
{
  "requestId": "REQ-422-001",
  "applicationRef": "APP-422-001",
  "type": "SENDSMS",
  "orgId": "IDFC_RETAIL",
  "payload": { "pan": "ABCDE1234F" }
}
```

**Drive to outcome:** edge-terminal, fail-closed on unknown enum/type. Routing is checked **before** any claim, so the `requestId` is **not** burned — the partner may fix `type` and resend the **same** `requestId` and it will proceed to `ACK_PROCESSED`.

**Expected result:** HTTP **422** (`UNPROCESSABLE_ENTITY`), body `{"applicationId":"DIG-CRED-APP-422-001","status":"UNROUTABLE","detail":"no origination route for type SENDSMS"}` (note: `applicationId` is still computed and returned). No publish, no run, no claim.

**Verify:** `GET /ops/runs/search?key=corr-dig-422-001` returns `[]`. Then resend with `type:"PERSONAL_LOAN"` and the **same** `requestId` → `200 ACK_PROCESSED` (proves the id was not burned).

---

### Origination — 200 ACK_DUPLICATE_REQUEST (idempotent exact resend, gate 1)

**Entry** (REST) — send the APPROVED request once (→ `ACK_PROCESSED`), then POST the **exact same `requestId`** again.

`POST http://localhost:8081/api/v1/digital/origination`

| Header | Value |
|---|---|
| `X-Partner-Token` | `cred-dev-token` |
| `X-Correlation-Id` | `corr-dig-dup-001` |
| `Content-Type` | `application/json` |

```json
{
  "requestId": "REQ-DUP-001",
  "applicationRef": "APP-DUP-001",
  "type": "PERSONAL_LOAN",
  "orgId": "IDFC_RETAIL",
  "payload": { "pan": "ABCDE1234F", "name": "Asha Rao" }
}
```

**Drive to outcome:** send the identical body twice. Gate 1 (`claimNotification(requestId)`) rejects the second. Same in both modes (pure edge dedup — the second call does **not** publish, so no second run and no second capability sequence).

**Expected result:** first call **200** `ACK_PROCESSED`; second call **200** `{"applicationId":"DIG-CRED-APP-DUP-001","status":"ACK_DUPLICATE_REQUEST","detail":"resend of request REQ-DUP-001 (…)"}`. Only **one** envelope on `orig.sfdc.pl.v1`; only **one** ops run.

**Verify:** `GET /ops/runs/search?key=corr-dig-dup-001` returns a **single** run (no double-book).

---

### Origination — 200 ACK_DUPLICATE_APPLICATION (new requestId, same application, gate 2)

**Entry** (REST) — first the ACK_DUPLICATE_REQUEST base above (`requestId REQ-DUP-001`, `applicationRef APP-DUP-001`), then POST a **different `requestId`** with the **same partner + `applicationRef`**.

`POST http://localhost:8081/api/v1/digital/origination`

| Header | Value |
|---|---|
| `X-Partner-Token` | `cred-dev-token` |
| `Content-Type` | `application/json` |

```json
{
  "requestId": "REQ-DUP-002",
  "applicationRef": "APP-DUP-001",
  "type": "PERSONAL_LOAN",
  "orgId": "IDFC_RETAIL",
  "payload": { "pan": "ABCDE1234F", "name": "Asha Rao" }
}
```

**Drive to outcome:** gate 1 passes (new `requestId`) but gate 2 (`claimApplication(applicationKey = "CRED::APP-DUP-001")`) rejects — the application is already owned. Same in both modes.

**Expected result:** HTTP **200** `{"applicationId":"DIG-CRED-APP-DUP-001","status":"ACK_DUPLICATE_APPLICATION","detail":"resend of an already-owned application CRED::APP-DUP-001 (…)"}`. No second publish, no second run. (Note the returned `applicationId` is the same deterministic id as the first request — no double-book across a new id.)

**Verify:** still a single ops run for `APP-DUP-001`.

---

### Origination — 503 RETRY (transient publish failure, no ACK)

**Entry** (REST) — a well-formed, routable, first-win request while the **Kafka broker is unreachable** (stop the broker, or point `KAFKA_BOOTSTRAP_SERVERS` at a dead port before the call).

`POST http://localhost:8081/api/v1/digital/origination`

| Header | Value |
|---|---|
| `X-Partner-Token` | `cred-dev-token` |
| `X-Correlation-Id` | `corr-dig-503-001` |
| `Content-Type` | `application/json` |

```json
{
  "requestId": "REQ-503-001",
  "applicationRef": "APP-503-001",
  "type": "PERSONAL_LOAN",
  "orgId": "IDFC_RETAIL",
  "payload": { "pan": "ABCDE1234F" }
}
```

**Drive to outcome:** the publish throws → the service calls `releaseClaims(requestId, applicationKey)` and rethrows → the controller maps the `RuntimeException` to 503. This is infra-level; there is no payload lever. (Not reachable via cap responses — the failure is in the edge’s producer, before the engine.)

**Expected result:** HTTP **503**, body `{"applicationId":null,"status":"RETRY","detail":"transient failure; please retry"}`. Claims are **released**, so a retry with the **same `requestId`** (once the broker is back) proceeds cleanly to `ACK_PROCESSED` — it must **not** collect a false `ACK_DUPLICATE_*` against a message that was never sent.

**Verify:** after broker recovery, resend the same body → `200 ACK_PROCESSED`; `GET /ops/runs/search?key=corr-dig-503-001` then shows exactly one run.

---

### Downstream failure class at a capability node (TRANSIENT / PERMANENT / AMBIGUOUS)

A capability `ERROR` fails the node. **No `loan-origination` node declares a `retrySpec`, `onFailure` route, or breaker**, so *any* error class fails the run identically to ERROR on the first response — the class only changes the recorded `nodeStats.failureClass`. `BREAKER_OPEN` is **not reachable** in this journey (no breaker policy on any node).

**Entry** (REST) — start a normal run.

`POST http://localhost:8081/api/v1/digital/origination`

| Header | Value |
|---|---|
| `X-Partner-Token` | `cred-dev-token` |
| `X-Correlation-Id` | `corr-dig-fail-001` |
| `Content-Type` | `application/json` |

```json
{
  "requestId": "REQ-FAIL-001",
  "applicationRef": "APP-FAIL-001",
  "type": "PERSONAL_LOAN",
  "orgId": "IDFC_RETAIL",
  "payload": { "pan": "ABCDE1234F", "name": "Asha Rao" }
}
```

**Drive to outcome**
- **Full-stack:** **not producible** — the `loan-origination` mock adapters (Posidex/NSDL/CIBIL/scoring/FinnOne) never return ERROR (the `"LOW"` lever changes the *score*, not an error; there is no error lever). Use engine-only mode to exercise any failure class. (You can, however, produce an infra-timeout via the stuck/sweeper permutation below by stopping a capability.)
- **Engine-only:** publish the upstream OK responses up to the node you want to fail, then a single ERROR at that node. Example: fail at `n_bureau`. Publish responses 1–2 (customer-party OK, kyc OK) with `journeyInstanceId":"ji-corr-dig-fail-001"`, then:
  → `cap.bureau.response.v1`
  ```json
  {"journeyInstanceId":"ji-corr-dig-fail-001","correlationId":"corr-dig-fail-001","nodeId":"n_bureau","capabilityKey":"bureau","status":"ERROR","result":{},"errorClass":"TRANSIENT"}
  ```
  Swap `"errorClass"` to **`"PERMANENT"`** or **`"AMBIGUOUS"`** for those permutations. A `null`/missing `errorClass` is treated as `AMBIGUOUS`. (The same holds if you fail any other node — `n_customer`/`n_kyc`/`n_score`/`n_book`; just set the matching `nodeId`+`capabilityKey`.)

**Expected result** (all three classes, given no retry policy):
- Ops status: **`FAILED_SFDC_NOTIFIED`** (the engine emits the ERROR `JourneyDecision` and marks notified). Terminal node = the failed node (e.g. `n_bureau`), terminal outcome **ERROR**.
- `RunDetailDto.nodeStats` shows the failed node with `failureClass` = the class you sent (`TRANSIENT`/`PERMANENT`/`AMBIGUOUS`).
- `RunDetailDto.dlqTopicRef` = `orig.sfdc.dlq.v1` (pointer, set for FAILED runs).
- Decision `orig.decision.v1`: `outcome:"ERROR"`, `loanId:null`. Edge status store → `outcome:"ERROR"`.
- (If the notify publish cannot be confirmed the status is `FAILED_NOTIFY_PENDING` instead — same run, channel not yet told.)

**Verify**
```
GET http://localhost:8082/ops/runs/search?key=corr-dig-fail-001   -> status:"FAILED_SFDC_NOTIFIED"
GET http://localhost:8082/ops/runs/ji-corr-dig-fail-001
  -> terminalNodeId:"n_bureau", terminalOutcome:"ERROR", dlqTopicRef:"orig.sfdc.dlq.v1",
     nodeStats:[{ nodeId:"n_bureau", attempts:1, failureClass:"TRANSIENT" }]
```

---

### Downstream booking failure at n_book (ERROR — compensation caveat)

**Entry** (REST) — same as APPROVED but drive to `n_book`, then fail booking.

`POST http://localhost:8081/api/v1/digital/origination`

| Header | Value |
|---|---|
| `X-Partner-Token` | `cred-dev-token` |
| `X-Correlation-Id` | `corr-dig-book-001` |
| `Content-Type` | `application/json` |

```json
{
  "requestId": "REQ-BOOK-001",
  "applicationRef": "APP-BOOK-001",
  "type": "PERSONAL_LOAN",
  "orgId": "IDFC_RETAIL",
  "payload": { "pan": "ABCDE1234F", "name": "Asha Rao" }
}
```

**Drive to outcome**
- **Full-stack:** not producible (mock FinnOne always returns `BOOKED`).
- **Engine-only:** publish the APPROVED responses 1–4 (through scoring `decision:"APPROVED"`) with `journeyInstanceId":"ji-corr-dig-book-001"`, then fail `n_book`:
  → `cap.lending-origination.response.v1`
  ```json
  {"journeyInstanceId":"ji-corr-dig-book-001","correlationId":"corr-dig-book-001","nodeId":"n_book","capabilityKey":"lending-origination","status":"ERROR","result":{},"errorClass":"PERMANENT"}
  ```

**Expected result:** Ops status **`FAILED_SFDC_NOTIFIED`**, terminal node `n_book`, outcome **ERROR**, `dlqTopicRef:"orig.sfdc.dlq.v1"`. `n_book` has `onFailure:"compensate"`, but the saga only undoes **already-completed** compensable nodes and `n_book` never completed — so the compensation queue is empty (`reverseBooking` is never dispatched). Expect `compensationOf:null` / `compensationPending:[]` in the detail. Decision `outcome:"ERROR"`, `loanId:null`.

**Verify:** `GET /ops/runs/ji-corr-dig-book-001` → `terminalNodeId:"n_book"`, `terminalOutcome:"ERROR"`, `compensationPending:[]`.

---

### Engine-level duplicate start (redelivery of the same envelope)

The edge’s gates stop partner-side duplicates. This permutation exercises the **engine’s** `insertIfAbsent` exactly-once start, seen when the same envelope is delivered to the origination topic twice (e.g. you re-publish it manually in Kafka UI, or a rebalance redelivers).

**Entry** (Kafka) — PUBLISH the same envelope twice.

Topic `orig.sfdc.pl.v1`, key `REQ-EDUP-001`:
```json
{
  "transactionId": "tx-edup-1",
  "schemaVersion": "digital-partner.v1",
  "source": "DIGITAL",
  "type": "PERSONAL_LOAN",
  "notificationId": "REQ-EDUP-001",
  "orgId": "IDFC_RETAIL",
  "sfdcRecordId": null,
  "applicationRef": "APP-EDUP-001",
  "correlationId": "corr-dig-edup-001",
  "originalCorrelationId": "corr-dig-edup-001",
  "payloadRef": null,
  "payloadContentType": "application/json",
  "occurredAt": "2026-07-03T10:15:30Z",
  "payload": { "pan": "ABCDE1234F", "name": "Asha Rao" }
}
```

**Drive to outcome:** publish the identical value a second time. Both derive `instanceId="ji-corr-dig-edup-001"`; the second `insertIfAbsent` loses.

**Expected result:** exactly **one** run `ji-corr-dig-edup-001`. The second delivery is logged `journey.start.duplicate` and dropped (it does **not** resume or re-run, even if the first is still mid-flight). Steer the single run to any terminal with the cap-response sequences above.

**Verify:** `GET /ops/runs/search?key=corr-dig-edup-001` returns a **single** run.

---

### Engine fail-closed on unknown type (manual publish of an unmapped type)

The edge can only emit the four mapped types, so an engine-level unroutable is not reachable through the REST door. To exercise the engine’s A2 fail-closed guard, publish an envelope with an **unmapped `type`** directly onto an origination topic.

**Entry** (Kafka)

Topic `orig.sfdc.pl.v1`, key `REQ-UNMAP-001`:
```json
{
  "transactionId": "tx-unmap-1",
  "schemaVersion": "digital-partner.v1",
  "source": "DIGITAL",
  "type": "MORTGAGE_XYZ",
  "notificationId": "REQ-UNMAP-001",
  "orgId": "IDFC_RETAIL",
  "sfdcRecordId": null,
  "applicationRef": "APP-UNMAP-001",
  "correlationId": "corr-dig-unmap-001",
  "originalCorrelationId": "corr-dig-unmap-001",
  "payloadRef": null,
  "payloadContentType": "application/json",
  "occurredAt": "2026-07-03T10:15:30Z",
  "payload": { "pan": "ABCDE1234F" }
}
```

**Drive to outcome:** `type` has no `type-to-journey` row → `UnroutableTypeException` → treated as a poison message.

**Expected result:** **no run starts** (no ops row for `corr-dig-unmap-001`). The message is dead-lettered to **`orig.sfdc.pl.v1.dlq`** (`<sourceTopic>.dlq`). Fail-closed — never a default journey.

**Verify:** `GET /ops/runs/search?key=corr-dig-unmap-001` → `[]`; consume `orig.sfdc.pl.v1.dlq` in Kafka UI to see the poisoned message.

---

### Stuck run and liveness sweeper (RUNNING → FAILED_SFDC_NOTIFIED)

**Entry** (REST) — start a normal run, then **never complete it** (leave a node’s capability response outstanding).

`POST http://localhost:8081/api/v1/digital/origination`

| Header | Value |
|---|---|
| `X-Partner-Token` | `cred-dev-token` |
| `X-Correlation-Id` | `corr-dig-stuck-001` |
| `Content-Type` | `application/json` |

```json
{
  "requestId": "REQ-STUCK-001",
  "applicationRef": "APP-STUCK-001",
  "type": "PERSONAL_LOAN",
  "orgId": "IDFC_RETAIL",
  "payload": { "pan": "ABCDE1234F", "name": "Asha Rao" }
}
```

**Drive to outcome**
- **Full-stack:** stop one capability service (e.g. kill `bureau`) so its request is never answered — the run parks in `RUNNING`.
- **Engine-only:** simply publish **nothing** after the run starts (or publish only responses 1–2 and stop). The node stays pending.

Then let wall-clock advance against the defaults: `run-budget-seconds=900`, `sweep-interval-ms=60000`.

**Expected result**
- At **~840s** (`startedAt ≤ now − (900−60)`) the run is flagged **stuck** while still `RUNNING` — surfaced only via `stuckOnly` / `stuck:true` / `sweepDeadline` (store state unchanged).
- At **900s** the sweeper force-fails it: publishes ERROR `JourneyDecision` (`terminalNodeId="__timeout__"`, `outcome=ERROR`, `loanId=null`), marks notified, sets state `FAILED`, emits ops event `run.sweptTimeout`. Ops status → **`FAILED_SFDC_NOTIFIED`**. `dlqTopicRef:"orig.sfdc.dlq.v1"`.
- Edge status store (via `DecisionConsumer`, `source=DIGITAL`): `outcome:"ERROR"`.

**Verify**
```
# during the stuck window (~840–900s):
GET http://localhost:8082/ops/runs?stuckOnly=true
  -> includes { runId:"ji-corr-dig-stuck-001", status:"RUNNING", stuck:true, sweepDeadline:"<startedAt+900s>" }
# after 900s:
GET http://localhost:8082/ops/runs/search?key=corr-dig-stuck-001   -> status:"FAILED_SFDC_NOTIFIED"
GET http://localhost:8082/ops/runs/ji-corr-dig-stuck-001
  -> terminalNodeId:"__timeout__", terminalOutcome:"ERROR", dlqTopicRef:"orig.sfdc.dlq.v1", sweepDeadline:null
```

---

### Status poll — 200 (owning partner)

**Entry** (REST)

`GET http://localhost:8081/api/v1/digital/applications/DIG-CRED-APP-APPR-001`

| Header | Value |
|---|---|
| `X-Partner-Token` | `cred-dev-token` |

**Drive to outcome:** the application must have been published by this partner (status store `register` at publish time; `outcome` starts `PENDING`, becomes `APPROVED`/`REJECTED`/`ERROR` when the decision returns). Same in both modes — reads the edge status store.

**Expected result:** HTTP **200**, body `ApplicationStatusStore.Status` = `{"applicationId":"DIG-CRED-APP-APPR-001","applicationRef":"APP-APPR-001","partner":"CRED","outcome":"APPROVED","loanId":"LN-APP-APPR-001"}` (before the decision returns: `outcome:"PENDING"`, `loanId:null`).

---

### Status poll — 401 (missing / unknown token)

**Entry** (REST)

`GET http://localhost:8081/api/v1/digital/applications/DIG-CRED-APP-APPR-001`

| Header | Value |
|---|---|
| `X-Partner-Token` | *(omit, or `bogus-token`)* |

**Expected result:** HTTP **401**, empty body (token resolves to no partner before any store lookup).

---

### Status poll — 404 (tenant-scoping / unknown id)

**Entry** (REST) — a **valid** token for a **different** partner than the one that owns the application, or an id that was never published.

`GET http://localhost:8081/api/v1/digital/applications/DIG-CRED-APP-APPR-001`

| Header | Value |
|---|---|
| `X-Partner-Token` | `flipkart-dev-token` |

**Drive to outcome:** the store lookup succeeds but the `partner` filter (`status.partner() == "CRED"`) fails for `FLIPKART`, so it maps to not-found — indistinguishable from a truly unknown id (ids can’t be probed across tenants). An entirely unknown `applicationId` (e.g. `DIG-CRED-NOPE`) with the owning token yields the same 404.

**Expected result:** HTTP **404**, empty body — identical for "another partner’s app" and "no such app".


---

<a id="sec-loan-origination"></a>

## Journey: loan-origination (PL / LAP / BL / Commercial)

This journey (`journeyKey=loan-origination`, `version=1`, `schemaVersion=2`, `startNodeId=n_customer`) is the single journey selected for envelope `type` ∈ {`PERSONAL_LOAN`, `LAP`, `BUSINESS_LOAN`, `COMMERCIAL`, `Inbound_Wrapper`} (engine `idfc.engine.type-to-journey`). All four "products" run the **identical DAG** — only the envelope `type` (and, to mirror production, the topic) differs.

**DAG (linear → one branch → book/reject):**

| # | node | type | capability.operation | binds response `result` → | next |
|---|---|---|---|---|---|
| 1 | `n_customer` | task | `customer-party.resolve` | `context.customer` | `n_kyc` |
| 2 | `n_kyc` | task | `kyc.verify` | `context.kyc` | `n_bureau` |
| 3 | `n_bureau` | task | `bureau.pull` | `context.bureau` | `n_score` |
| 4 | `n_score` | task | `scoring.decide` | `context.scoring` | `n_decide` |
| 5 | `n_decide` | branch | — | — | arm→`n_book`, default→`n_reject` |
| 6a | `n_book` | task | `lending-origination.book` (`onFailure: compensate`, pool `finnone_pool`) | `context.loan` | `n_done` |
| 7a | `n_done` | terminal | `status=completed` → **APPROVED**, emit `LoanBooked` | — | — |
| 6b | `n_reject` | terminal | `status=rejected` → **REJECTED**, emit `LoanRejected` | — | — |

**The one branch (`n_decide`):** arm `context.scoring.decision == 'APPROVED'` → `n_book`; everything else (including `"REJECTED"`, null, missing) → default `n_reject`. String compare.

**No node declares `retry`/circuit-breaker** (`n_book` has only `meter`). Therefore **any** capability `status=ERROR` fails the node on the first response regardless of `errorClass`; retry/breaker lanes are unreachable in this journey.

**instanceId derivation** (from the inbound envelope): `dedupKey = correlationId → originalCorrelationId → notificationId → applicationRef → "unknown"`; `instanceId = "ji-" + dedupKey`. `store.insertIfAbsent` is the exactly-once start gate. Terminal `status` → decision (`JourneyEngine.terminalOutcome`): `completed→APPROVED`, `rejected→REJECTED`, `failed→ERROR`, unknown→ERROR (fail-closed).

**Ops status vocabulary used below:** `RUNNING`, `COMPLETED_APPROVED`, `COMPLETED_DECLINED`, `FAILED_SFDC_NOTIFIED`, `FAILED_NOTIFY_PENDING`.

---

### Steering library (engine-only manual mode)

In engine-only mode **no capabilities are running**, so the only responses the engine sees are the ones you publish. All responses go to topic **`cap.<key>.response.v1`** (message key = the `journeyInstanceId`, or leave blank — the engine correlates on the JSON body's `journeyInstanceId` + `nodeId`, never the Kafka key). The run below uses `correlationId=corr-abc-123` ⇒ **`journeyInstanceId=ji-corr-abc-123`** and `applicationRef=APP-1001`. **Change these per run** to avoid the duplicate-start drop.

**P1** → `cap.customer-party.response.v1`
```json
{
  "journeyInstanceId": "ji-corr-abc-123",
  "correlationId": "corr-abc-123",
  "nodeId": "n_customer",
  "capabilityKey": "customer-party",
  "status": "OK",
  "result": { "crn": "CRN-ABCDE1234F", "customerId": "CUST-ABCDE1234F", "customerName": "RAKESH KUMAR", "customerStatus": "ACTIVE" },
  "errorClass": null
}
```

**P2** → `cap.kyc.response.v1`
```json
{
  "journeyInstanceId": "ji-corr-abc-123",
  "correlationId": "corr-abc-123",
  "nodeId": "n_kyc",
  "capabilityKey": "kyc",
  "status": "OK",
  "result": { "kycStatus": "VERIFIED", "kycRefId": "KYC-ABCDE1234F" },
  "errorClass": null
}
```

**P3-high** (bureauScore 780 ⇒ APPROVED path) → `cap.bureau.response.v1`
```json
{
  "journeyInstanceId": "ji-corr-abc-123",
  "correlationId": "corr-abc-123",
  "nodeId": "n_bureau",
  "capabilityKey": "bureau",
  "status": "OK",
  "result": {
    "bureauResults": [ { "type": "CIBIL", "score": 780, "grade": "A", "reportId": "RPT-1", "source": "CIBIL", "fetchedAt": "2026-07-03T10:15:31Z" } ],
    "bureauScore": 780,
    "bureauGrade": "A",
    "reportId": "RPT-1"
  },
  "errorClass": null
}
```

**P3-low** (bureauScore 540 ⇒ REJECT path) → `cap.bureau.response.v1` — same as P3-high but `"score":540`,`"grade":"C"`,`"bureauScore":540`,`"bureauGrade":"C"`.

**P4-approved** → `cap.scoring.response.v1`
```json
{
  "journeyInstanceId": "ji-corr-abc-123",
  "correlationId": "corr-abc-123",
  "nodeId": "n_score",
  "capabilityKey": "scoring",
  "status": "OK",
  "result": { "decision": "APPROVED", "score": 780, "reasons": ["bureauScore 780 >= threshold 700", "fico=750"] },
  "errorClass": null
}
```

**P4-rejected** → `cap.scoring.response.v1` — same but `"decision":"REJECTED"`,`"score":540`,`"reasons":["bureauScore 540 < threshold 700","fico=750"]`.

**P5-book** → `cap.lending-origination.response.v1`
```json
{
  "journeyInstanceId": "ji-corr-abc-123",
  "correlationId": "corr-abc-123",
  "nodeId": "n_book",
  "capabilityKey": "lending-origination",
  "status": "OK",
  "result": { "loanId": "LN-APP-1001", "status": "BOOKED" },
  "errorClass": null
}
```

**ERR-template** (fail any node) → `cap.<key>.response.v1` — set `nodeId`/`capabilityKey` to the target node, `status:"ERROR"`, `result:{}`, `errorClass` ∈ `"TRANSIENT"|"PERMANENT"|"AMBIGUOUS"|null`.
```json
{
  "journeyInstanceId": "ji-corr-abc-123",
  "correlationId": "corr-abc-123",
  "nodeId": "n_bureau",
  "capabilityKey": "bureau",
  "status": "ERROR",
  "result": {},
  "errorClass": "PERMANENT"
}
```

---

## Entry points (start a run)

### E1 — Kafka door (primary manual path)

**Entry.** Topic per product; message **KEY = `notificationId`**; value = `CanonicalEnvelope`.

| product | topic | envelope `type` |
|---|---|---|
| PL | `orig.sfdc.pl.v1` | `PERSONAL_LOAN` |
| LAP | `orig.sfdc.lap.v1` | `LAP` |
| BL | `orig.sfdc.bl.v1` | `BUSINESS_LOAN` |
| Commercial | `orig.sfdc.commercial.v1` | `COMMERCIAL` |
| Inbound_Wrapper | `orig.sfdc.pl.v1` | `Inbound_Wrapper` |

(The engine picks the journey from `type`, not the topic — all four `orig.sfdc.*.v1` share one consumer group `origination-journey-engine`. Publishing `type:"COMMERCIAL"` onto `orig.sfdc.pl.v1` still runs COMMERCIAL→`loan-origination`.)

Canonical PL body (key `04l6D00000ABCdeQAF`):
```json
{
  "transactionId": "tx-0001",
  "schemaVersion": "sfdc-ingress.v1",
  "source": "SFDC",
  "type": "PERSONAL_LOAN",
  "notificationId": "04l6D00000ABCdeQAF",
  "orgId": "00D6D00000020HoUAI",
  "sfdcRecordId": "a0X6D000001abcdEAA",
  "applicationRef": "APP-1001",
  "correlationId": "corr-abc-123",
  "originalCorrelationId": "corr-abc-123",
  "payloadRef": null,
  "payloadContentType": "application/json",
  "occurredAt": "2026-07-03T10:15:30Z",
  "payload": { "pan": "ABCDE1234F", "name": "RAKESH KUMAR", "amount": 500000, "tenureMonths": 36 }
}
```
For LAP/BL/Commercial change **`type`** (and topic) only. For Inbound_Wrapper set `type:"Inbound_Wrapper"` on `orig.sfdc.pl.v1`.

**Expected.** Engine logs `journey.start instanceId=ji-corr-abc-123`; ops event `run.started`; status `RUNNING`; run dispatches `n_customer` first. Continue with an outcome permutation (O1–O10).

### E2 — REST door A: SFDC SOAP ingress (full-stack edge)

**Entry.** `POST /api/v1/sfdc/outbound-messages`

| Header | Value |
|---|---|
| `Content-Type` | `text/xml` |
| `X-Auth-Token` | `<edge token>` |

Body (raw SOAP; `SVCNAME__c` is the routing key = `PERSONAL_LOAN`/`LAP`/`BUSINESS_LOAN`/`COMMERCIAL`/`Inbound_Wrapper`; `Request__c` CDATA carries the applicant payload):
```xml
<?xml version="1.0" encoding="UTF-8"?>
<soapenv:Envelope xmlns:soapenv="http://schemas.xmlsoap.org/soap/envelope/" xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance">
  <soapenv:Body>
    <notifications xmlns="http://soap.sforce.com/2005/09/outbound" xmlns:sf="urn:sobject.enterprise.soap.sforce.com">
      <OrganizationId>00D6D00000020HoUAI</OrganizationId>
      <ActionId>04kABCDEFGHIJKL</ActionId>
      <SessionId>SES-1</SessionId>
      <EnterpriseUrl>https://example.my.salesforce.com/services/Soap/c/58.0</EnterpriseUrl>
      <PartnerUrl>https://example.my.salesforce.com/services/Soap/u/58.0</PartnerUrl>
      <Notification>
        <Id>04l6D00000ABCdeQAF</Id>
        <sObject xsi:type="sf:Loan_Application__c">
          <sf:Id>a0X6D000001abcdEAA</sf:Id>
          <sf:CLIENTID__c>CLI-1</sf:CLIENTID__c>
          <sf:SVCNAME__c>PERSONAL_LOAN</sf:SVCNAME__c>
          <sf:VERSION__c>1.0</sf:VERSION__c>
          <sf:EXECMODE__c>SYNC</sf:EXECMODE__c>
          <sf:Request__c><![CDATA[{"pan":"ABCDE1234F","name":"RAKESH KUMAR","amount":500000,"tenureMonths":36}]]></sf:Request__c>
        </sObject>
      </Notification>
    </notifications>
  </soapenv:Body>
</soapenv:Envelope>
```

**Drive to outcome.** The edge dedupes (`Notification/Id` = `04l6D00000ABCdeQAF`), normalizes, and publishes the envelope to `orig.sfdc.pl.v1` (key = `notificationId`). Note: on the SOAP path `applicationRef` is **null** and `correlationId` is generated per request, so `dedupKey` = the generated `correlationId` ⇒ `instanceId = "ji-<generated-corr>"`. Read the engine `journey.start instanceId=…` log line to get the exact id for ops search / engine-only steering. Then drive to any outcome as in E1.

**Expected.** Whole batch durable → **HTTP 200** `<notificationsResponse><Ack>true</Ack>`. Bad/missing token → **HTTP 401** SOAP `<Fault>`. Unparseable envelope → **HTTP 500** `<Fault>` (batch NAK'd). Unknown `OrganizationId` (not in `ORG1`/`IDFC_RETAIL`/`IDFC_BUSINESS`/`00D6D00000020HoUAI`/`00DC40000014dS1MAI`) or unknown `SVCNAME__c` → after one config refresh → DLQ `orig.sfdc.dlq.v1` + `<Ack>true</Ack>` (C2 permanent).

### E3 — REST door B: Digital partner edge (full-stack edge)

**Entry.** `POST /api/v1/digital/origination`

| Header | Value |
|---|---|
| `Content-Type` | `application/json` |
| `X-Partner-Token` | `<CRED/FLIPKART/GROWW token>` |
| `X-Correlation-Id` | `corr-dig-001` (optional; else edge generates `corr-<UUID>`) |

Body (`partner` is derived from the token, never in the body; `type` routes the topic):
```json
{
  "requestId": "REQ-9001",
  "applicationRef": "APP-2001",
  "type": "PERSONAL_LOAN",
  "orgId": "IDFC_RETAIL",
  "payload": { "pan": "ABCDE1234F", "name": "RAKESH KUMAR", "amount": 500000, "tenureMonths": 36 }
}
```

**Drive to outcome.** Edge maps to `CanonicalEnvelope` (`source=DIGITAL`, `notificationId=requestId=REQ-9001`, `correlationId=corr-dig-001`), publishes to `orig.sfdc.pl.v1` (LAP/BL/Commercial via `type`). `instanceId = "ji-corr-dig-001"`. Then drive as in E1.

**Expected.** Ack dispositions → **HTTP 200** `{applicationId:"DIG-<partnerCode>-APP-2001", status:"ACK_PROCESSED"|…, detail}`. Unknown/missing token → **401** `status:"UNAUTHENTICATED"`. Structurally invalid (blank `requestId`/`applicationRef`/`type`/`orgId`) → **400** `status:"INVALID"`. Unroutable `type` → **422** `status:"UNROUTABLE"`. Transient publish failure → **503** `status:"RETRY"`.

---

## Outcome permutations

### O1 — APPROVED (n_done → COMPLETED_APPROVED)

**Entry.** Any of E1/E2/E3 (payload PAN **without** the substring `LOW`, empty/absent `negativeFlags`).

**Drive — full-stack (capabilities + WireMock running).** Nothing extra: normal PAN ⇒ bureauScore `780 ≥ 700` and no negative flags ⇒ `scoring.decision="APPROVED"` ⇒ arm → `n_book` ⇒ `n_done`.

**Drive — engine-only.** Publish in order: **P1 → P2 → P3-high → P4-approved → P5-book**.

**Expected.**
- Terminal node `n_done`, terminal outcome `APPROVED`, ops status **`COMPLETED_APPROVED`**.
- Transitions: `n_customer`→`n_kyc`→`n_bureau`→`n_score`→(branch `n_decide` arm)→`n_book`→`n_done`, all `COMPLETED`.
- Ops events: `run.started`, `node.dispatched`/`node.completed` per node, `run.completed`.
- Decision on `orig.decision.v1` (key = `applicationRef`): `outcome:"APPROVED"`, `loanId:"LN-APP-1001"`, `terminalNodeId:"n_done"`, `emitted:["LoanBooked"]`. No DLQ.
- Verify: `GET /ops/runs/search?key=corr-abc-123` → one `RunSummaryDto` `status:"COMPLETED_APPROVED"`; then `GET /ops/runs/{runId}` → `terminalNodeId:"n_done"`, `terminalOutcome:"APPROVED"`, `dlqTopicRef:null`.

### O2 — DECLINED (n_reject → COMPLETED_DECLINED)

**Entry.** Any of E1/E2/E3.

**Drive — full-stack.** Two independent levers, either one declines:
1. PAN **or** applicationRef containing `LOW` (uppercased) ⇒ CIBIL `540 < 700` ⇒ `decision="REJECTED"`. E.g. `payload.pan:"LOWDE1234F"` or `applicationRef:"APP-LOW-1001"`.
2. `payload.negativeFlags` non-empty (e.g. `["FRAUD"]`) ⇒ `decision="REJECTED"` regardless of score.

**Drive — engine-only.** Publish: **P1 → P2 → P3-low → P4-rejected**. (`n_book`/`n_done` are never reached; the branch default → `n_reject` fires immediately after P4-rejected.)

**Expected.**
- Terminal node `n_reject`, terminal outcome `REJECTED`, ops status **`COMPLETED_DECLINED`** (a clean completion, **not** a failure).
- Transitions: …→`n_score`→(`n_decide` default)→`n_reject`, all `COMPLETED`.
- Decision on `orig.decision.v1`: `outcome:"REJECTED"`, `loanId:null`, `terminalNodeId:"n_reject"`, `emitted:["LoanRejected"]`. No DLQ.
- Verify: `GET /ops/runs/search?key=corr-abc-123` → `status:"COMPLETED_DECLINED"`; detail `dlqTopicRef:null`.

### O3 — Node failure: PERMANENT (any of n_customer / n_kyc / n_bureau / n_score / n_book)

**Entry.** Any of E1/E2/E3.

**Drive — full-stack.** **Not producible.** The five capabilities' mocks (customer-party, kyc, bureau, scoring, lending-origination `book`) always return OK — there is no WireMock/payload lever to force an ERROR on this journey. Use engine-only.

**Drive — engine-only.** Publish the OK prefix up to the node under test, then an **ERR-template** with `errorClass:"PERMANENT"` for that node:
- fail `n_customer`: publish ERR-template (`nodeId:"n_customer"`,`capabilityKey:"customer-party"`) with no prefix.
- fail `n_kyc`: P1, then ERR (`n_kyc`/`kyc`).
- fail `n_bureau`: P1, P2, then ERR (`n_bureau`/`bureau`).
- fail `n_score`: P1, P2, P3-high, then ERR (`n_score`/`scoring`).
- fail `n_book`: P1, P2, P3-high, P4-approved, then ERR (`n_book`/`lending-origination`) → see O7 (compensation).

**Expected.** Node fails on first ERROR (no retrySpec). Run fails: ERROR `JourneyDecision` published to `orig.decision.v1` (`outcome:"ERROR"`, `terminalNodeId:"<failed node>"`, `loanId:null`, `emitted:[]`) ⇒ channel notified ⇒ ops status **`FAILED_SFDC_NOTIFIED`**. Ops event `node.failed` then `run.failed`.
- Verify: `GET /ops/runs/search?key=corr-abc-123` → `status:"FAILED_SFDC_NOTIFIED"`; `GET /ops/runs/{runId}` → `terminalNodeId:"<failed node>"`, `terminalOutcome:"ERROR"`, `sfdcNotified:"SENT"`, `dlqTopicRef:"orig.sfdc.dlq.v1"`, `nodeStats:[{nodeId:"<failed node>", attempts:1, failureClass:"PERMANENT"}]`.
- (If the ERROR decision publish cannot be confirmed → `sfdcNotified:"PENDING"` → status **`FAILED_NOTIFY_PENDING`**.)

### O4 — Node failure: TRANSIENT

**Drive — engine-only.** Identical to O3 but ERR-template `errorClass:"TRANSIENT"`.

**Expected.** Because no node has a `retrySpec` containing `TRANSIENT`, the node fails immediately exactly like O3 — ops status **`FAILED_SFDC_NOTIFIED`**. The only observable difference is `nodeStats[].failureClass:"TRANSIENT"` in `GET /ops/runs/{runId}`. (There is **no retry ladder** on this journey.)

### O5 — Node failure: AMBIGUOUS (null / omitted errorClass)

**Drive — engine-only.** ERR-template with `"errorClass": null` (or omit the field). Engine maps missing errorClass → `AMBIGUOUS`.

**Expected.** No retrySpec ⇒ fails immediately ⇒ ops status **`FAILED_SFDC_NOTIFIED`**; `nodeStats[].failureClass:"AMBIGUOUS"`. Same terminal/decision shape as O3.

### O6 — BREAKER_OPEN (not reachable — fail-closed note)

**Not reachable in loan-origination.** `BREAKER_OPEN` is an engine circuit-breaker state, not a `CapabilityResponse.errorClass` (the wire enum has only `TRANSIENT`/`PERMANENT`/`AMBIGUOUS`). No node in this journey declares a breaker policy, so no dispatch can trip one; a crafted response cannot carry it. `failureClass:"BREAKER_OPEN"` can therefore never appear in this journey's `nodeStats`. (It is reachable only on journeys whose nodes declare a breaker policy.)

### O7 — Booking failure + compensation saga (n_book, onFailure: compensate)

**Entry.** Any of E1/E2/E3 (approved path so `n_book` is reached).

**Drive — engine-only.** Publish **P1 → P2 → P3-high → P4-approved**, then ERR-template for `n_book` (`capabilityKey:"lending-origination"`, any `errorClass`).

**Expected.** `n_book.onFailure=="compensate"` ⇒ `startCompensation`: engine emits the ERROR `JourneyDecision` **immediately** (`terminalNodeId:"n_book"`, `outcome:"ERROR"`), then walks completed compensable TASK nodes in reverse. **Three load-bearing facts:** (1) `n_book` itself never COMPLETED (it failed), and it is the only node with a `compensation`, so the undo queue is **empty** — `reverseBooking` is **never dispatched**; (2) even if it were, its mapping reads `context.loan.id` which is null (`book` result key is `loanId`, not `id`); (3) `reverseBooking` is not a registered `lending-origination` operation. Net: the run just ends **`FAILED_SFDC_NOTIFIED`** (`terminalOutcome:"ERROR"`, `terminalNodeId:"n_book"`).
- Verify: `GET /ops/runs/{runId}` → `compensationOf:null`, `compensationPending:[]` (empty saga), `dlqTopicRef:"orig.sfdc.dlq.v1"`.

### O8 — Idempotency / duplicate resend (same id)

**Entry.** Publish the **same** starting envelope from E1 **twice** (identical `correlationId=corr-abc-123` ⇒ identical `instanceId=ji-corr-abc-123`), e.g. a Kafka redelivery or a manual re-produce.

**Drive.** No steering needed — the second copy's `store.insertIfAbsent` loses.

**Expected.** First copy starts the run. Second copy logged `journey.start.duplicate` and **dropped** — it does **not** start a second run and does **not** resume the first even if it is still mid-flight (the liveness sweeper, O10, is the net for a truly stuck winner).
- Edge-level resend note (E2/E3): a resend of the same `Notification/Id` / `requestId` is absorbed at the edge (`ACK_DUPLICATE_*` / `ACK_DUPLICATE_REQUEST`) and returns the same deterministic `applicationId` — it never publishes a duplicate envelope.
- Verify: `GET /ops/runs/search?key=corr-abc-123` returns **exactly one** run (search is exact-match, newest-first; a genuinely re-sent business key with a *different* correlation would appear as multiple).

### O9 — Unknown type / unknown enum — fail-closed poison → DLQ

**Entry.** Publish to `orig.sfdc.pl.v1` (key `04l6D00000ZZZZZ`) an envelope whose `type` has no `type-to-journey` row, e.g. `"type":"AUTO_LOAN"` (or any typo). Full body otherwise as E1.

**Drive.** None — routing is fail-closed.

**Expected.** `OriginationConsumer` → `JourneyOrchestrator` → `registry.resolveForType("AUTO_LOAN")` → `UnroutableTypeException` → message treated as **poison** → dead-lettered to **`orig.sfdc.pl.v1.dlq`** (source topic + `.dlq`). **No run is started**, no `run.started` event.
- (Same fail-closed poison → `orig.sfdc.pl.v1.dlq` for an undeserializable envelope body → `PoisonMessageException`.)
- Verify: nothing in `GET /ops/runs/search?key=<notificationId>` (no run created); inspect the `orig.sfdc.pl.v1.dlq` topic in Kafka UI for the poison record.
- Edge fail-closed counterpart: unknown `SVCNAME__c` (SFDC) → `ConfigNotFoundException` after one refresh → `orig.sfdc.dlq.v1` + ACK; unknown `type` on digital edge → **422 `UNROUTABLE`**.

### O10 — Stuck run + liveness sweeper (RUNNING → FAILED_SFDC_NOTIFIED via __timeout__)

**Entry.** Start a run via E1 (engine-only mode, **no capabilities running**), then publish **nothing** (or stop after P1/P2 so a node stays pending).

**Drive.** Leave the run un-answered so it stays `RUNNING`.

**Expected timeline** (defaults: run-budget `900s`, sweep-interval `60s`):
- Immediately → ops status **`RUNNING`**; `sweepDeadline = startedAt + 900s`.
- At `startedAt + 840s` (`900 − 60`) → ops read-model flags `stuck:true` (surfaced only via `stuckOnly`/`stuckCount`). Store state is still `RUNNING`.
- At `startedAt + 900s` → `JourneyLivenessSweeper` force-fails: publishes ERROR `JourneyDecision` (`outcome:"ERROR"`, `terminalNodeId:"__timeout__"`, `loanId:null`) **first**, `markSfdcNotified()`, then `fail("__timeout__", ERROR)` → state `FAILED`, emits ops event **`run.sweptTimeout`**. Ops status → **`FAILED_SFDC_NOTIFIED`**.
- Verify while stuck: `GET /ops/runs?stuckOnly=true` lists it (`stuck:true`, `status:"RUNNING"`). After sweep: `GET /ops/runs/search?key=corr-abc-123` → `status:"FAILED_SFDC_NOTIFIED"`; `GET /ops/runs/{runId}` → `terminalNodeId:"__timeout__"`, `terminalOutcome:"ERROR"`, `sfdcNotified:"SENT"`, `sweepDeadline:null`.

---

## Decision callback (SFDC push-back, C1 exactly-once)

### D1 — POST decision, exactly-once transition into DECIDED

**Entry.** `POST /api/v1/sfdc/decisions`

| Header | Value |
|---|---|
| `Content-Type` | `application/json` |
| `X-Auth-Token` | `<edge token>` |
| `X-Correlation-Id` | `corr-abc-123` (optional trace) |

```json
{ "notificationId": "04l6D00000ABCdeQAF", "outcome": "APPROVED", "applicationId": "APP-1001", "terms": "36m@10.5%" }
```

**Drive.** The push-back to SFDC fires **exclusively** on the successful CAS transition into `DECIDED`.

**Expected.**
- First call, record exists and not yet decided → CAS applies → `sfdcResponse.pushDecision` runs **exactly once** → **HTTP 200** `{ "notificationId":"04l6D00000ABCdeQAF", "pushed":true, "detail":"transitioned to DECIDED and pushed to SFDC" }`.
- Resend (already `DECIDED`) → `pushed:false`, `detail:"already decided; no push (C1)"` (idempotent, no second push).
- Unknown record / lost CAS race → `pushed:false`, no push.
- Bad/missing token → **HTTP 401** `{ notificationId, pushed:false, detail:"invalid or missing token" }`.

---

## Ops verification (`/ops`, port 8082)

All calls require both headers: `X-Ops-Token: dev-ops-token` and `X-User-Id: ops.analyst@bank`. Base `http://localhost:8082/ops`. GET-only.

### V1 — List (defaults)
`GET /ops/runs` → `PageDto` (`page=0`, `size=50`, sorted `startedAt` DESC). Confirms your loan-origination runs appear with computed `status`.

### V2 — Filter by status
`GET /ops/runs?status=COMPLETED_APPROVED` (also `COMPLETED_DECLINED`, `RUNNING`, `FAILED_SFDC_NOTIFIED`, `FAILED_NOTIFY_PENDING`; case-insensitive). Unknown value → **400** `{"error":"BAD_REQUEST","message":"unknown status 'BOGUS' (allowed: [...])"}`.

### V3 — Filter by journeyKey
`GET /ops/runs?journeyKey=loan-origination` (exact match — all PL/LAP/BL/Commercial runs share this key).

### V4 — Filter by time window
`GET /ops/runs?since=2026-07-03T00:00:00Z&until=2026-07-03T23:59:59Z` (ISO-8601 instant; filters `startedAt`). Bad format → **400**.

### V5 — stuckOnly
`GET /ops/runs?status=RUNNING&stuckOnly=true` → only RUNNING runs past the `840s` stuck threshold (see O10).

### V6 — Exact search + detail
- `GET /ops/runs/search?key=corr-abc-123` → `List<RunSummaryDto>` newest-first, exact match across `runId | correlationId | notificationId | sfdcRecordId` (so `key=04l6D00000ABCdeQAF` or `key=a0X6D000001abcdEAA` also resolve this run). Blank `key` → **400**.
- `GET /ops/runs/{runId}` → `RunDetailDto` (transitions, `terminalNodeId`, `terminalOutcome`, `sfdcNotified`, `dlqTopicRef`, `nodeStats`, `compensationOf`/`compensationPending`, `stuck`, `sweepDeadline`). Unknown id → **404**, empty body.
- Auth failures: missing/bad `X-Ops-Token` → **401** `"invalid or missing X-Ops-Token"`; missing `X-User-Id` → **401** `"X-User-Id header is required for the ops API"`.

---

## Maker-checker (publishing the `loan-origination` journey definition)

Registry base `http://localhost:8104/api/v1`; header `X-Registry-Token: dev-registry-token` on every call; `X-User-Id` on writes only. Approve == publish (there is no `/publish`).

### M1 — Happy lifecycle
1. `POST /journeys` (`X-User-Id: maker@bank`) body `{"key":"loan-origination","name":"Loan Origination","businessLine":"RETAIL","product":"PL","partner":null}` → **201**.
2. `POST /journeys/loan-origination/versions` (maker) `{"config":{...valid loan-origination graph...},"note":"first cut"}` → **201** `{status:"draft",version:1}`.
3. `PUT /journeys/loan-origination/versions/1` (maker) → **200** (save draft).
4. `POST /journeys/loan-origination/versions/1/validate` (no actor) → **200** `ValidationResultDto`.
5. `POST /journeys/loan-origination/versions/1/submit` (maker) → **200** `{status:"pendingApproval"}`.
6. `POST /journeys/loan-origination/versions/1/approve` (`X-User-Id: checker@bank`) → **200** `{status:"published", approverId:"checker@bank"}` (moves the published pointer; engine can now fetch via `GET /published-journeys/loan-origination/versions/1`).

### M2 — 403 self-approve
`POST /journeys/loan-origination/versions/1/approve` with `X-User-Id: maker@bank` (the author) → **403** `{"error":"FORBIDDEN","message":"maker-checker: author 'maker@bank' may not approve/reject their own version","issues":[]}`. Same for `/reject` by the author.

### M3 — 409 conflicts (second-draft / lifecycle / immutability)
- Second editable version: `POST /journeys/loan-origination/versions` while v1 is DRAFT or PENDING_APPROVAL → **409** `"journey 'loan-origination' already has an editable version (v1)"`.
- Duplicate journey: `POST /journeys` with existing key → **409** `"journey 'loan-origination' already exists"`.
- `PUT` save on a non-DRAFT version → **409** `"version N of 'loan-origination' is <STATUS> — published/rejected/pending versions are immutable"`.
- `submit` on non-DRAFT → **409** `"only a DRAFT can be submitted ..."`. `approve`/`reject` on non-PENDING → **409** `"only a PENDING_APPROVAL version can be approved/rejected ..."`. Lost checker race → **409** `"... was already finalized by another checker"`.

### M4 — 422 validation
- Bad key on create (not lowercase kebab): `POST /journeys` `{"key":"Loan_Origination",...}` → **422** `{"error":"VALIDATION_FAILED","message":"journey key must be lowercase kebab-case ([a-z0-9-]), e.g. 'loan-origination'","issues":[]}`.
- Submit failing the graph gate: `submit` with any `severity:"error"` issue (e.g. empty DAG) → **422** `message:"journey 'loan-origination' vN fails validation"` with populated `issues[]` (e.g. `{"code":"emptyDag","severity":"error",...}`).
- Config not a JSON object / unparseable on draft create/save/submit → **422** `issues:[{"code":"emptyDag","severity":"error",...}]`.


---

<a id="sec-vehicle-rc"></a>

---

## Journey: vehicle-rc-verification

Verifies a vehicle Registration Certificate through Karza VAHAN‑RC and routes the run on the RC's status + blacklist flag. Graph (`journeys/vehicle-rc-verification.journey.json`, `journeyKey=vehicle-rc-verification`, `version=1`, `schemaVersion=2`, `startNodeId=n_vehicleRc`):

```
n_vehicleRc (task: verification / KARZA_VAHAN_RC, timeout 20s, output=context.vehicleRc)
    │  next
    ▼
n_rcDecision (branch)
    ├─ arm  → n_proceed  (terminal, status=completed, emit VehicleRcApproved)   → APPROVED
    └─ default → n_decline (terminal, status=rejected,  emit VehicleRcDeclined)  → REJECTED
n_vehicleRc.onFailure → n_rcError (terminal, status=failed, emit VehicleRcError) → ERROR
```

Branch arm (verbatim) → `n_proceed`:
```
context.vehicleRc.ISSUCCESS == 'True' && context.vehicleRc.DATA.result[0].result.rcStatus == 'ACTIVE' && context.vehicleRc.DATA.result[0].result.blackListStatus == 'CLEAR'
```
Everything else (any clause false / value missing) falls through `default` → `n_decline`. Terminal status → decision (`JourneyEngine.terminalOutcome`): `completed`→APPROVED, `rejected`→REJECTED, `failed`→ERROR.

Topics used by this journey:
| Purpose | Topic | Key |
|---|---|---|
| Start (origination envelope) | an engine origination topic — default set: `orig.sfdc.pl.v1`,`orig.sfdc.lap.v1`,`orig.sfdc.bl.v1`,`orig.sfdc.commercial.v1` | `notificationId` |
| Capability request (engine→cap) | `cap.verification.request.v1` | `journeyInstanceId` |
| Capability response (cap→engine, **you craft in engine‑only mode**) | `cap.verification.response.v1` | `journeyInstanceId` (or blank) |
| Run decision (engine→channel) | `orig.decision.v1` | `applicationRef` |
| Ops lifecycle events | `ops.journey.events.v1` | `journeyInstanceId` |
| Capability terminal‑failure DLQ (full‑stack only) | `cap.verification.dlq.v1` | — |
| Capability terminal‑failure notify (full‑stack only) | `sfdc.response.notify.v1` | — |
| Engine poison DLQ (unroutable/undeserializable) | `<start-topic>.dlq` (e.g. `orig.sfdc.pl.v1.dlq`) | — |

Instance id (`JourneyOrchestrator.onOrigination`): `instanceId = "ji-" + firstNonNull(correlationId, originalCorrelationId, notificationId, applicationRef)`. All examples below set `correlationId`, so `instanceId = "ji-" + correlationId`. `store.insertIfAbsent` is the exactly‑once start gate.

### Prerequisite (one‑time) — make this journey loadable and routable

This journey ships in `resources/journeys/` but is **NOT** in the engine's default `journey-resources` list and has **NO** `type-to-journey` row (base map is only `PERSONAL_LOAN/LAP/BUSINESS_LOAN/COMMERCIAL/Inbound_Wrapper → loan-origination`). Until you wire it, any `type` you send is unroutable → poison DLQ (that is permutation *Unknown‑type fail‑closed* below). Pick ONE enablement path:

- **Classpath mode (simplest):** start the engine with these overrides so it loads the file and maps a type to it. The type token `VEHICLE_RC` is the one the repo's own end‑to‑end test uses (`Map.of("VEHICLE_RC", def.key())`).
  ```
  IDFC_ENGINE_JOURNEY_SOURCE=classpath
  --idfc.engine.journey-resources[0]=journeys/loan-origination.journey.json
  --idfc.engine.journey-resources[1]=journeys/vehicle-rc-verification.journey.json
  --idfc.engine.type-to-journey.VEHICLE_RC=vehicle-rc-verification
  ```
- **Registry mode:** publish the journey through the maker‑checker lifecycle (see the *Maker‑checker* subsection at the end — it uses this exact journey key), set `IDFC_ENGINE_JOURNEY_SOURCE=registry` + `JOURNEY_REGISTRY_URL`/`REGISTRY_AUTH_TOKEN`, and still add the `type-to-journey.VEHICLE_RC=vehicle-rc-verification` row (routing is separate from cataloguing).

All origination examples below publish to `orig.sfdc.pl.v1` with `type:"VEHICLE_RC"`. The engine selects the journey from the **`type` field, not the topic** — the topic only decides which listener consumes it, so any of the four default origination topics works identically.

> Engine‑only vs full‑stack, one crucial difference for the ERROR arm: the DLQ (`cap.verification.dlq.v1`) and SFDC notify (`sfdc.response.notify.v1`) are produced **inside the verification capability** (`VerificationDispatcher.terminalFailure`), before it returns the ERROR `CapabilityResponse`. In engine‑only manual mode there is no capability running, so when you hand‑publish an ERROR response those two side‑effects **do not occur** — only the engine‑side outcome (`n_rcError`/ERROR decision/ops event) happens. Node `n_vehicleRc` has **no `retrySpec`** (only a `timeout` policy), so the engine fails it on the **first** ERROR regardless of `errorClass`.

---

### Permutation 1 — RC ACTIVE + CLEAR → n_proceed (APPROVED)

**Entry** — (Kafka) topic `orig.sfdc.pl.v1`, key `NRC-PASS-01`:
```json
{
  "transactionId": "tx-rc-pass-01",
  "schemaVersion": "sfdc-ingress.v1",
  "source": "SFDC",
  "type": "VEHICLE_RC",
  "notificationId": "NRC-PASS-01",
  "orgId": "00D6D00000020HoUAI",
  "sfdcRecordId": "a0X6D000001rcPASS",
  "applicationRef": "APP-RC-PASS-01",
  "correlationId": "corr-rc-pass-01",
  "originalCorrelationId": "corr-rc-pass-01",
  "payloadRef": null,
  "payloadContentType": "application/json",
  "occurredAt": "2026-07-03T10:15:30Z",
  "payload": {
    "registrationNumber": "AB12CD1234",
    "consent": "Y"
  }
}
```

**Drive to outcome**
- **Full‑stack (capabilities + WireMock):** set payload `registrationNumber` to any value **≠ `XX00YY0000`** (e.g. `AB12CD1234`). WireMock `vahan-rc-pass.json` returns HTTP 200 with inner `rcStatus:"ACTIVE"`, `blackListStatus:"CLEAR"`. No lever beyond that field.
- **Engine‑only (no capabilities):** the engine will emit a request on `cap.verification.request.v1` (key = `ji-corr-rc-pass-01`, `nodeId=n_vehicleRc`, `operation=KARZA_VAHAN_RC`). PUBLISH one message to `cap.verification.response.v1` (key `ji-corr-rc-pass-01`):
  ```json
  {
    "journeyInstanceId": "ji-corr-rc-pass-01",
    "correlationId": "corr-rc-pass-01",
    "nodeId": "n_vehicleRc",
    "capabilityKey": "verification",
    "status": "OK",
    "result": {
      "ISSUCCESS": "True",
      "DATA": {
        "Status": "SUCCESS",
        "errorMessage": "",
        "result": [
          {
            "requestId": "req-1",
            "statusCode": "200",
            "timeStamp": "2026-07-03T10:15:31Z",
            "result": {
              "registrationNumber": "AB12CD1234",
              "ownerName": "JOHN DOE",
              "rcStatus": "ACTIVE",
              "blackListStatus": "CLEAR"
            }
          }
        ]
      }
    },
    "errorClass": null
  }
  ```

**Expected result** — ops status `COMPLETED_APPROVED`; terminal node `n_proceed`, outcome **APPROVED**, emit `VehicleRcApproved`. Transitions: `n_vehicleRc COMPLETED` → `n_rcDecision` (arm true) → `n_proceed`. Decision on `orig.decision.v1` (key `APP-RC-PASS-01`): `{"journeyInstanceId":"ji-corr-rc-pass-01","outcome":"APPROVED","terminalNodeId":"n_proceed",...}`. Ops events: `run.started`,`node.dispatched`/`node.completed` (n_vehicleRc), `run.completed`. No DLQ. **Verify:** `GET /ops/runs/search?key=corr-rc-pass-01` → newest run `status:"COMPLETED_APPROVED"`; `GET /ops/runs/{runId}` → `terminalNodeId:"n_proceed"`, `terminalOutcome:"APPROVED"`, `dlqTopicRef:null`.

---

### Permutation 2 — RC blacklisted (blackListStatus ≠ CLEAR) → n_decline (REJECTED)

Business decline via the branch — **not** a technical failure (no DLQ, no notify).

**Entry** — topic `orig.sfdc.pl.v1`, key `NRC-DEC-01`:
```json
{
  "transactionId": "tx-rc-decline-01",
  "schemaVersion": "sfdc-ingress.v1",
  "source": "SFDC",
  "type": "VEHICLE_RC",
  "notificationId": "NRC-DEC-01",
  "orgId": "00D6D00000020HoUAI",
  "sfdcRecordId": "a0X6D000001rcDEC1",
  "applicationRef": "APP-RC-DEC-01",
  "correlationId": "corr-rc-decline-01",
  "originalCorrelationId": "corr-rc-decline-01",
  "payloadRef": null,
  "payloadContentType": "application/json",
  "occurredAt": "2026-07-03T10:15:30Z",
  "payload": {
    "registrationNumber": "XX00YY0000",
    "consent": "Y"
  }
}
```

**Drive to outcome**
- **Full‑stack:** set `registrationNumber` to exactly `XX00YY0000`. WireMock `vahan-rc-fail.json` returns HTTP 200 with `rcStatus:"ACTIVE"`, `blackListStatus:"BLACKLIST"` → arm's third clause false → `default`.
- **Engine‑only:** PUBLISH to `cap.verification.response.v1` (key `ji-corr-rc-decline-01`) an OK envelope whose inner `blackListStatus` ≠ `CLEAR`:
  ```json
  {
    "journeyInstanceId": "ji-corr-rc-decline-01",
    "correlationId": "corr-rc-decline-01",
    "nodeId": "n_vehicleRc",
    "capabilityKey": "verification",
    "status": "OK",
    "result": {
      "ISSUCCESS": "True",
      "DATA": {
        "Status": "SUCCESS",
        "errorMessage": "",
        "result": [
          {
            "requestId": "req-1",
            "statusCode": "200",
            "timeStamp": "2026-07-03T10:15:31Z",
            "result": {
              "registrationNumber": "XX00YY0000",
              "rcStatus": "ACTIVE",
              "blackListStatus": "BLACKLIST"
            }
          }
        ]
      }
    },
    "errorClass": null
  }
  ```

**Expected result** — ops status `COMPLETED_DECLINED` (a normal completion, not red); terminal `n_decline`, outcome **REJECTED**, emit `VehicleRcDeclined`. Decision on `orig.decision.v1`: `outcome:"REJECTED"`, `terminalNodeId:"n_decline"`. No DLQ, no `sfdc.response.notify.v1`. **Verify:** `GET /ops/runs/search?key=corr-rc-decline-01` → `status:"COMPLETED_DECLINED"`; detail `terminalNodeId:"n_decline"`, `terminalOutcome:"REJECTED"`, `dlqTopicRef:null`.

---

### Permutation 2b — RC status not ACTIVE → n_decline (REJECTED) — engine‑only

Same decline terminal via the **first status clause** instead of the blacklist clause. WireMock cannot produce this (the only fail stub flips `blackListStatus`), so it is an engine‑only permutation.

**Entry** — identical envelope shape as Permutation 2 but use `notificationId:"NRC-DEC-02"`, `correlationId:"corr-rc-decline-02"`, `applicationRef:"APP-RC-DEC-02"`, payload `registrationNumber:"AB12CD1234"`.

**Drive to outcome (engine‑only)** — PUBLISH to `cap.verification.response.v1` (key `ji-corr-rc-decline-02`) an OK envelope with inner `rcStatus` ≠ `ACTIVE`:
```json
{
  "journeyInstanceId": "ji-corr-rc-decline-02",
  "correlationId": "corr-rc-decline-02",
  "nodeId": "n_vehicleRc",
  "capabilityKey": "verification",
  "status": "OK",
  "result": {
    "ISSUCCESS": "True",
    "DATA": {
      "Status": "SUCCESS",
      "errorMessage": "",
      "result": [
        {
          "requestId": "req-1",
          "statusCode": "200",
          "timeStamp": "2026-07-03T10:15:31Z",
          "result": {
            "registrationNumber": "AB12CD1234",
            "rcStatus": "SUSPENDED",
            "blackListStatus": "CLEAR"
          }
        }
      ]
    }
  },
  "errorClass": null
}
```
(Equivalently, an envelope with `"ISSUCCESS":"False"` also fails the arm's first clause → `n_decline`; note `VerificationEnvelope.failure()` is never emitted by the real capability, so `ISSUCCESS` is always `"True"` on the OK path — this variant only occurs when hand‑crafted.)

**Expected result** — same as Permutation 2: `COMPLETED_DECLINED`, `n_decline`, REJECTED, `VehicleRcDeclined`, no DLQ. **Verify:** search `key=corr-rc-decline-02`.

---

### Permutation 3 — Technical failure TRANSIENT → n_rcError (ERROR)

**Entry** — topic `orig.sfdc.pl.v1`, key `NRC-ERR-TR-01`:
```json
{
  "transactionId": "tx-rc-err-tr-01",
  "schemaVersion": "sfdc-ingress.v1",
  "source": "SFDC",
  "type": "VEHICLE_RC",
  "notificationId": "NRC-ERR-TR-01",
  "orgId": "00D6D00000020HoUAI",
  "sfdcRecordId": "a0X6D000001rcETR1",
  "applicationRef": "APP-RC-ERR-TR-01",
  "correlationId": "corr-rc-err-tr-01",
  "originalCorrelationId": "corr-rc-err-tr-01",
  "payloadRef": null,
  "payloadContentType": "application/json",
  "occurredAt": "2026-07-03T10:15:30Z",
  "payload": {
    "registrationNumber": "AB12CD1234",
    "consent": "Y"
  }
}
```

**Drive to outcome**
- **Full‑stack:** cause a transport failure that `KarzaClient` classifies TRANSIENT — a **5xx** from the vahan‑rc endpoint (add/replace a WireMock stub for `/karza/vahan-rc` returning HTTP **503**), or **connection refused** (stop the `mock-karza` container). The capability retries (`idfc.verification.retry.max-attempts=3`, TRANSIENT is retryable) and, once exhausted, DLQs + notifies + returns `status:ERROR, errorClass:TRANSIENT`.
- **Engine‑only:** PUBLISH one message to `cap.verification.response.v1` (key `ji-corr-rc-err-tr-01`):
  ```json
  {
    "journeyInstanceId": "ji-corr-rc-err-tr-01",
    "correlationId": "corr-rc-err-tr-01",
    "nodeId": "n_vehicleRc",
    "capabilityKey": "verification",
    "status": "ERROR",
    "result": {},
    "errorClass": "TRANSIENT"
  }
  ```
  (One message suffices — `n_vehicleRc` has no `retrySpec`, so the engine does not re‑request; it fails the node immediately.)

**Expected result** — terminal `n_rcError`, outcome **ERROR**, emit `VehicleRcError`. Decision on `orig.decision.v1`: `outcome:"ERROR"`, `terminalNodeId:"n_rcError"`. Ops status: **`FAILED_SFDC_NOTIFIED` in BOTH full-stack and engine-only.** `OpsRun.status()` derives it as `sfdcNotified==SENT ? FAILED_SFDC_NOTIFIED : FAILED_NOTIFY_PENDING`; `sfdcNotified` is flipped to `SENT` by the **engine's own decision publish** — on reaching `n_rcError` the engine emits the ERROR decision to `orig.decision.v1` and, on the confirmed-send ack, calls `clearPendingPublishes()`→`markSfdcNotified()` (the persist-before-publish hop in `JourneyOrchestrator.dispatch`). This is identical whether the failing response came from the real capability (full-stack) or was hand-published to `cap.verification.response.v1` (engine-only) — it is NOT driven by the capability's `sfdc.response.notify.v1` (whose consumer never touches run notify-state). `FAILED_NOTIFY_PENDING` (`sfdcNotified` PENDING/NONE) arises only if the `orig.decision.v1` publish itself cannot be confirmed (e.g. broker down). The DLQ `cap.verification.dlq.v1` and `sfdc.response.notify.v1` are full-stack only (produced inside the capability); `failureClass` never affects this status split. **Verify:** `GET /ops/runs/search?key=corr-rc-err-tr-01`; `GET /ops/runs/{runId}` → `terminalNodeId:"n_rcError"`, `terminalOutcome:"ERROR"`, `nodeStats[].failureClass:"TRANSIENT"`, `dlqTopicRef` set for the FAILED status, `sfdcNotified:"SENT"` in both modes once the decision publish confirms.

---

### Permutation 4 — Technical failure PERMANENT → n_rcError (ERROR)

**Entry** — as Permutation 3 with `notificationId:"NRC-ERR-PM-01"`, `correlationId:"corr-rc-err-pm-01"`, `applicationRef:"APP-RC-ERR-PM-01"`, `sfdcRecordId:"a0X6D000001rcEPM1"`, payload `registrationNumber:"AB12CD1234"`.

**Drive to outcome**
- **Full‑stack:** cause a **4xx** on the vahan‑rc call → `KarzaClient` maps `is4xxClientError → PERMANENT` (`HTTP_4xx`). Easiest levers: point the route at a path with **no matching stub** (WireMock returns 404 → `HTTP_404`/PERMANENT), or add a stub returning **400/401/403/422**. An **empty 200 body** also yields PERMANENT (`EMPTY_RESPONSE`). PERMANENT is not retried — straight to DLQ+notify.
- **Engine‑only:** PUBLISH to `cap.verification.response.v1` (key `ji-corr-rc-err-pm-01`):
  ```json
  {
    "journeyInstanceId": "ji-corr-rc-err-pm-01",
    "correlationId": "corr-rc-err-pm-01",
    "nodeId": "n_vehicleRc",
    "capabilityKey": "verification",
    "status": "ERROR",
    "result": {},
    "errorClass": "PERMANENT"
  }
  ```

**Expected result** — identical engine-side outcome to Permutation 3 (`n_rcError`, ERROR, `VehicleRcError`); only `nodeStats[].failureClass:"PERMANENT"` differs, and the full-stack DLQ reason contains `PERMANENT`. Ops status `FAILED_SFDC_NOTIFIED` in both modes (see Permutation 3 — the SENT/PENDING split is driven by the `orig.decision.v1` publish confirming, not by full-stack vs engine-only). **Verify:** search `key=corr-rc-err-pm-01`; detail `failureClass:"PERMANENT"`.

---

### Permutation 5 — Technical failure AMBIGUOUS (read timeout) → n_rcError (ERROR)

**Entry** — as Permutation 3 with `notificationId:"NRC-ERR-AM-01"`, `correlationId:"corr-rc-err-am-01"`, `applicationRef:"APP-RC-ERR-AM-01"`, `sfdcRecordId:"a0X6D000001rcEAM1"`, payload `registrationNumber:"AB12CD1234"`.

**Drive to outcome**
- **Full‑stack:** make the vahan‑rc call exceed the read timeout — add a WireMock stub for `/karza/vahan-rc` with a fixed delay **> the read timeout** (`idfc.verification.http.read-timeout-ms`, default 20000 ms; the node `timeout` is also `20s`). `SimpleClientHttpRequestFactory` surfaces this as `SocketTimeoutException` → `KarzaClient` classifies `AMBIGUOUS` (`READ_TIMEOUT`). AMBIGUOUS is retried for idempotent reads (this op is an idempotent read), then DLQ+notify.
- **Engine‑only:** PUBLISH to `cap.verification.response.v1` (key `ji-corr-rc-err-am-01`):
  ```json
  {
    "journeyInstanceId": "ji-corr-rc-err-am-01",
    "correlationId": "corr-rc-err-am-01",
    "nodeId": "n_vehicleRc",
    "capabilityKey": "verification",
    "status": "ERROR",
    "result": {},
    "errorClass": "AMBIGUOUS"
  }
  ```

**Expected result** — `n_rcError`, ERROR, `VehicleRcError`; `nodeStats[].failureClass:"AMBIGUOUS"`. Ops status `FAILED_SFDC_NOTIFIED` in both modes (see Permutation 3 — the SENT/PENDING split is driven by the `orig.decision.v1` publish confirming, not by full-stack vs engine-only). **Verify:** search `key=corr-rc-err-am-01`; detail `failureClass:"AMBIGUOUS"`.

---

### Permutation 6 — BREAKER_OPEN — not reachable in this journey (documented negative)

There is **no reachable BREAKER_OPEN path** here. `n_vehicleRc` declares no circuit‑breaker policy (only `timeout`), and the verification capability (`KarzaClient`/`VerificationDispatcher`) only ever produces `TRANSIENT`/`PERMANENT`/`AMBIGUOUS`. `BREAKER_OPEN` exists in the ops `nodeStats.failureClass` enum but nothing in this journey can emit it. If you hand‑publish `"errorClass":"BREAKER_OPEN"` on `cap.verification.response.v1`, the engine still has no `retrySpec` on the node, so it behaves exactly like the other error classes → immediate `n_rcError`/ERROR, with `nodeStats[].failureClass:"BREAKER_OPEN"` recorded. It is not a distinct business behavior — no separate expected result.

---

### Permutation 7 — Idempotency / duplicate resend (same id)

**Entry** — re‑PUBLISH the **exact Permutation 1 envelope** (same `notificationId:"NRC-PASS-01"` / `correlationId:"corr-rc-pass-01"`) to `orig.sfdc.pl.v1` a second time, while or after the first run.

**Drive to outcome** — no levers; this exercises the engine start gate. Same envelope → same `dedupKey` → same `instanceId "ji-corr-rc-pass-01"`. The second `store.insertIfAbsent` loses → logged `journey.start.duplicate` and **dropped** (it does not resume or re‑run, even if the winner is still mid‑flight). No capability request is re‑emitted for the duplicate; do **not** publish a second `cap.verification.response.v1`.

**Expected result** — still exactly **one** run for `ji-corr-rc-pass-01`, one decision on `orig.decision.v1`, one `run.started`/`run.completed` pair. **Verify:** `GET /ops/runs/search?key=corr-rc-pass-01` returns a single run (not two); its `runId` is unchanged across the resend.

---

### Permutation 8 — Unknown type — fail‑closed to poison DLQ

**Entry** — topic `orig.sfdc.pl.v1`, key `NRC-BADTYPE-01`, with a `type` that has **no `type-to-journey` row** (e.g. omit the enablement row, or use a bogus token):
```json
{
  "transactionId": "tx-rc-badtype-01",
  "schemaVersion": "sfdc-ingress.v1",
  "source": "SFDC",
  "type": "VEHICLE_RC_TYPO",
  "notificationId": "NRC-BADTYPE-01",
  "orgId": "00D6D00000020HoUAI",
  "sfdcRecordId": "a0X6D000001rcBAD1",
  "applicationRef": "APP-RC-BADTYPE-01",
  "correlationId": "corr-rc-badtype-01",
  "originalCorrelationId": "corr-rc-badtype-01",
  "payloadRef": null,
  "payloadContentType": "application/json",
  "occurredAt": "2026-07-03T10:15:30Z",
  "payload": {
    "registrationNumber": "AB12CD1234",
    "consent": "Y"
  }
}
```

**Drive to outcome** — no levers. `JourneyOrchestrator` → `registry.resolveForType("VEHICLE_RC_TYPO")` finds no mapping → `UnroutableTypeException` → `OriginationConsumer` wraps it as `PoisonMessageException` → routed to the engine DLQ. (Same fail‑closed path fires for a message body that cannot be deserialized into a Map.)

**Expected result** — **no run starts** (no ops row, no decision). The message lands on `orig.sfdc.pl.v1.dlq` (source topic + `.dlq` suffix). Ops status vocabulary is not involved — there is nothing to search by id. **Verify:** consume `orig.sfdc.pl.v1.dlq` in Kafka UI and confirm the poison record; `GET /ops/runs/search?key=corr-rc-badtype-01` returns an empty list.

---

### Permutation 9 — Stuck run → sweeper force‑fail (timeout)

**Entry** — topic `orig.sfdc.pl.v1`, key `NRC-STUCK-01`:
```json
{
  "transactionId": "tx-rc-stuck-01",
  "schemaVersion": "sfdc-ingress.v1",
  "source": "SFDC",
  "type": "VEHICLE_RC",
  "notificationId": "NRC-STUCK-01",
  "orgId": "00D6D00000020HoUAI",
  "sfdcRecordId": "a0X6D000001rcSTK1",
  "applicationRef": "APP-RC-STUCK-01",
  "correlationId": "corr-rc-stuck-01",
  "originalCorrelationId": "corr-rc-stuck-01",
  "payloadRef": null,
  "payloadContentType": "application/json",
  "occurredAt": "2026-07-03T10:15:30Z",
  "payload": {
    "registrationNumber": "AB12CD1234",
    "consent": "Y"
  }
}
```

**Drive to outcome** — start the run but **never deliver a `cap.verification.response.v1`** for `n_vehicleRc`: in engine‑only mode simply don't publish the response; in full‑stack, stop the verification capability so the request on `cap.verification.request.v1` is never consumed. The run sits `RUNNING` with `n_vehicleRc` dispatched. Timings (defaults): flagged **stuck** at `startedAt + (900 − 60) = +840s`; **force‑failed** by `JourneyLivenessSweeper` at `startedAt + 900s` (sweep runs every 60s).

**Expected result** — before ~840s: `status RUNNING`. In the stuck window (~840–900s): still store‑state `RUNNING`, surfaced by the `stuckOnly` filter with `stuck:true`. At ~900s the sweeper: publishes an ERROR `JourneyDecision` (`outcome:ERROR`, `terminalNodeId:"__timeout__"`, `loanId:null`) on `orig.decision.v1`, marks SFDC notified, sets state FAILED, emits ops event `run.sweptTimeout`. Final ops status **`FAILED_SFDC_NOTIFIED`**. **Verify:** during the window `GET /ops/runs?status=RUNNING&stuckOnly=true` lists this run (`stuck:true`, `sweepDeadline = startedAt + 900s`); after sweep `GET /ops/runs/search?key=corr-rc-stuck-01` → `status:"FAILED_SFDC_NOTIFIED"`, detail `terminalNodeId:"__timeout__"`, `terminalOutcome:"ERROR"`, `sweepDeadline:null`.

---

### Maker‑checker — publishing this journey via the registry (control plane)

Base `http://localhost:8104/api/v1`. Every call needs `X-Registry-Token: dev-registry-token`; write/lifecycle ops also need `X-User-Id`. This is the registry‑mode enablement path for the journey (catalogue only — you still add the `type-to-journey.VEHICLE_RC` engine row separately).

**Happy lifecycle**
| Step | Request | Expect |
|---|---|---|
| 1. Create journey (maker) | `POST /journeys` H:`X-User-Id: maker@bank` body `{"key":"vehicle-rc-verification","name":"Vehicle RC Verification","businessLine":"RETAIL","product":"AUTO","partner":null}` | 201 `JourneyDto`, `activeVersion:null` |
| 2. Create draft (maker) | `POST /journeys/vehicle-rc-verification/versions` H:`X-User-Id: maker@bank` body `{"config":{ <the journey JSON: nodes/branch/terminals> },"note":"rc v1"}` | 201 `VersionDto` `{version:1,status:"draft"}` |
| 3. Save draft (maker) | `PUT /journeys/vehicle-rc-verification/versions/1` H:`X-User-Id: maker@bank` body `{"config":{...},"note":"wip"}` | 200 `VersionDto` |
| 4. Validate | `POST /journeys/vehicle-rc-verification/versions/1/validate` (no actor) | 200 `ValidationResultDto` (`issues:[]` when clean) |
| 5. Submit (maker) | `POST /journeys/vehicle-rc-verification/versions/1/submit` H:`X-User-Id: maker@bank` | 200 `{status:"pendingApproval"}` |
| 6. Approve = publish (checker) | `POST /journeys/vehicle-rc-verification/versions/1/approve` H:`X-User-Id: checker@bank` | 200 `{status:"published",approverId:"checker@bank"}`; `activeVersion` moves to 1 |
| 7. Confirm published | `GET /published-journeys/vehicle-rc-verification/versions/1` (no actor) | 200 `PublishedConfigDto` |

There is no `/publish` endpoint — **approve is publish**.

**403 self‑approve** — `POST /journeys/vehicle-rc-verification/versions/1/approve` with `X-User-Id: maker@bank` (the author) → `403 {"error":"FORBIDDEN","message":"maker-checker: author 'maker@bank' may not approve/reject their own version","issues":[]}`. Same for `/reject` by the author.

**409 conflict**
- Second editable draft: `POST /journeys/vehicle-rc-verification/versions` while v1 is still DRAFT/PENDING → `409 {"error":"CONFLICT","message":"journey 'vehicle-rc-verification' already has an editable version (v1)","issues":[]}`.
- Duplicate journey: repeat step 1 → `409 "journey 'vehicle-rc-verification' already exists"`.
- Lifecycle immutability: `PUT .../versions/1` after it is published → `409 "version 1 of 'vehicle-rc-verification' is PUBLISHED — published/rejected/pending versions are immutable"`; `submit` a non‑DRAFT → `409 "only a DRAFT can be submitted ..."`; `approve`/`reject` a non‑PENDING → `409 "only a PENDING_APPROVAL version can be approved/rejected ..."`; concurrent approve+reject loser → `409 "... was already finalized by another checker"`.

**422 validation**
- Bad key on create: `POST /journeys` with `"key":"Vehicle_RC"` (not lowercase kebab) → `422 {"error":"VALIDATION_FAILED","message":"journey key must be lowercase kebab-case ([a-z0-9-]), e.g. 'loan-origination'","issues":[]}`.
- Submit a graph that fails the gate (e.g. draft config `{"nodes":[],"edges":[]}`): `POST .../versions/1/submit` → `422 {"error":"VALIDATION_FAILED","message":"journey 'vehicle-rc-verification' v1 fails validation","issues":[{"code":"emptyDag","severity":"error",...}]}` (`issues[]` populated).

---

### Ops — observing this journey's runs

Base `http://localhost:8082/ops`, headers `X-Ops-Token: dev-ops-token` and `X-User-Id: ops.analyst@bank` on **every** call (both required; ops token is a different secret from the registry token). GET‑only.

| View | Request | Expect |
|---|---|---|
| List (defaults) | `GET /ops/runs` | `PageDto`, `page:0,size:50`, runs newest‑first |
| Filter by status | `GET /ops/runs?status=COMPLETED_APPROVED` | only Permutation‑1‑style runs. Valid values: `RUNNING`,`COMPLETED_APPROVED`,`COMPLETED_DECLINED`,`FAILED_SFDC_NOTIFIED`,`FAILED_NOTIFY_PENDING`. Unknown → `400 BAD_REQUEST` |
| Filter by journeyKey | `GET /ops/runs?journeyKey=vehicle-rc-verification` | only this journey's runs |
| Time window | `GET /ops/runs?since=2026-07-03T00:00:00Z&until=2026-07-04T00:00:00Z` | `startedAt` within range; bad instant → `400` |
| Stuck only | `GET /ops/runs?status=RUNNING&stuckOnly=true` | only RUNNING runs past the ~840s threshold (Permutation 9 during its window); each `stuck:true` with a `sweepDeadline` |
| Pagination | `GET /ops/runs?page=0&size=25` | `size` clamped to 1..200 |
| Exact search | `GET /ops/runs/search?key=corr-rc-pass-01` | `List<RunSummaryDto>` newest‑first; matches across `runId`/`correlationId`/`notificationId`/`sfdcRecordId`. Blank `key` → `400`. Also works with `key=NRC-PASS-01` or `key=a0X6D000001rcPASS` |
| Detail | `GET /ops/runs/{runId}` | `RunDetailDto` with `transitions[]`, `terminalNodeId`/`terminalOutcome`, `sfdcNotified` (NONE/PENDING/SENT), `nodeStats[]` (incl. `failureClass` for a failed `n_vehicleRc`), `dlqTopicRef` (set only for the FAILED statuses), `sweepDeadline`. Unknown runId → `404` empty body |

Auth negatives: missing/bad `X-Ops-Token` → `401 {"error":"UNAUTHENTICATED","message":"invalid or missing X-Ops-Token"}`; missing `X-User-Id` → `401 {"error":"UNAUTHENTICATED","message":"X-User-Id header is required for the ops API"}`; `?status=BOGUS` → `400 {"error":"BAD_REQUEST","message":"unknown status 'BOGUS' ..."}`.

---

**Section summary — outcome matrix for vehicle-rc-verification**

| Permutation | Full‑stack lever (`registrationNumber` / condition) | Engine‑only response (`cap.verification.response.v1`) | Terminal node | Ops status |
|---|---|---|---|---|
| ACTIVE+CLEAR | any ≠ `XX00YY0000` | OK, inner `rcStatus:ACTIVE`+`blackListStatus:CLEAR` | `n_proceed` | `COMPLETED_APPROVED` |
| Blacklisted | `XX00YY0000` | OK, `blackListStatus:BLACKLIST` | `n_decline` | `COMPLETED_DECLINED` |
| Status not ACTIVE | (not producible by WireMock) | OK, `rcStatus:SUSPENDED` | `n_decline` | `COMPLETED_DECLINED` |
| TRANSIENT | 5xx / connection refused | ERROR `errorClass:TRANSIENT` | `n_rcError` | `FAILED_SFDC_NOTIFIED` (both modes; `FAILED_NOTIFY_PENDING` only if the `orig.decision.v1` publish is unconfirmed) |
| PERMANENT | 4xx / 404 no‑stub / empty body | ERROR `errorClass:PERMANENT` | `n_rcError` | as above |
| AMBIGUOUS | delayed stub > 20s read timeout | ERROR `errorClass:AMBIGUOUS` | `n_rcError` | as above |
| BREAKER_OPEN | not reachable | (behaves like other errors) | `n_rcError` | as above |
| Duplicate resend | resend same `notificationId`/`correlationId` | — (do not resend response) | single run only | unchanged |
| Unknown type | `type` with no `type-to-journey` row | — | none (poison) | none → `orig.sfdc.pl.v1.dlq` |
| Stuck/sweeper | never deliver the response | omit the response | `__timeout__` | `RUNNING`→`FAILED_SFDC_NOTIFIED` |


---

<a id="sec-negative-area"></a>

---

## Journey: negative-area-verification

**What it is.** A single-hop Karza address-risk verification. `journeyKey=negative-area-verification`, `version=1`, `schemaVersion=2`, `startNodeId=n_negativeArea`. Graph: `n_negativeArea` (task, `capability=verification`, `operation=ENT_KARZA_NEGATIVE_AREA_TAGGING`, `idempotent:true`, `onFailure:n_negativeError`) → `n_negativeDecision` (branch) → `n_proceed` | `n_decline`; any technical failure of the task routes to `n_negativeError`.

Exact branch arm (→ `n_proceed`):
```
context.negativeArea.ISSUCCESS == 'True' && context.negativeArea.DATA.result[0].result.is_negative == false
```
`default` → `n_decline`. Terminals: `n_proceed` (`status:completed`, emit `NegativeAreaClear`), `n_decline` (`status:rejected`, emit `NegativeAreaFlagged`), `n_negativeError` (`status:failed`, emit `NegativeAreaError`). Task input mapping `{ addressId: context.addressId, consent: 'Y' }` → outbound Karza body `{ addressId, consent }`. Node output binds the whole verification envelope to `context.negativeArea`.

**Reachability caveat (read first).** This journey is **not live by default**: it is absent from `idfc.engine.journey-resources` (base loads only `journeys/loan-origination.journey.json`) and has **no `idfc.engine.type-to-journey` row**. It ships in `resources/journeys/` and today is exercised only by the in-process test `full-flow-it/.../Step3VerificationEndToEndTest.java`. To drive it from Kafka you must first make it loadable and routable — see the Prerequisite block.

### Prerequisite — make the journey loadable + routable

Do BOTH, then restart the engine (`origination-journey`):

1. **Load the definition** — either classpath mode (add to the list) or registry mode (publish it; see the maker-checker permutation below):
   ```
   IDFC_ENGINE_JOURNEY_SOURCE=classpath
   # add journeys/negative-area-verification.journey.json to idfc.engine.journey-resources
   ```
2. **Add a routing row** mapping an inbound `type` to the journey key:
   ```
   idfc.engine.type-to-journey.NEGATIVE_AREA_CHECK: negative-area-verification
   ```

All permutations below use `type = "NEGATIVE_AREA_CHECK"`. The engine selects the journey from the envelope **`type` field, not the topic**, so you may publish the start envelope to any configured origination topic; examples use **`orig.sfdc.pl.v1`**.

### Fixed facts for this section

| Thing | Value |
|---|---|
| Start topic (any origination topic) | `orig.sfdc.pl.v1` (key = `notificationId`) |
| Engine consumer group | `origination-journey-engine` |
| Capability request topic (engine → cap) | `cap.verification.request.v1` (key = `journeyInstanceId`) |
| Capability response topic (you publish in engine-only mode) | `cap.verification.response.v1` (key = `journeyInstanceId`) |
| `nodeId` you steer | `n_negativeArea` |
| Capability DLQ (full-stack technical failure) | `cap.verification.dlq.v1` + notify `sfdc.response.notify.v1` |
| Engine decision topic | `orig.decision.v1` (key = `applicationRef`) |
| Engine poison DLQ (unroutable/undeserializable start) | `<sourceTopic>.dlq` → `orig.sfdc.pl.v1.dlq` |
| Engine response poison DLQ (bad response enum) | `cap.verification.response.v1.dlq` |
| instanceId derivation | `"ji-" + firstNonNull(correlationId, originalCorrelationId, notificationId, applicationRef)` |
| Ops API | `GET http://localhost:8082/ops/...`, headers `X-Ops-Token: dev-ops-token`, `X-User-Id: ops.analyst@bank` |
| Registry API | `http://localhost:8104/api/v1/...`, header `X-Registry-Token: dev-registry-token` (+`X-User-Id` on writes) |
| WireMock (full-stack) | `infra/mock-vendors/karza/mappings/negative-area-{pass,fail}.json`, `urlPath /karza/negative-area`, match field **`addressId`** |
| Liveness | run-budget 900s (force-fail), stuck-flag threshold 840s, sweep interval 60s |

**Full-stack vs engine-only:** *Full-stack* = verification capability + WireMock running; the single lever is the **`addressId` value in the start payload** (PASS/DECLINE) or a **transport failure** (ERROR). *Engine-only* = capabilities down; you hand-publish one `cap.verification.response.v1` to steer the run. In engine-only mode the capability-side effects (`cap.verification.dlq.v1`, `sfdc.response.notify.v1`) do **not** fire — only the engine's decision + ops state do.

---

### P1 — PASS arm: address clear → `n_proceed` (COMPLETED_APPROVED)

**Entry** (Kafka) — topic `orig.sfdc.pl.v1`, key `ntf-neg-pass-001`:
```json
{
  "transactionId": "tx-neg-pass-001",
  "schemaVersion": "sfdc-ingress.v1",
  "source": "SFDC",
  "type": "NEGATIVE_AREA_CHECK",
  "notificationId": "ntf-neg-pass-001",
  "orgId": "00D6D00000020HoUAI",
  "sfdcRecordId": "a0Xneg001",
  "applicationRef": "APP-NEG-1001",
  "correlationId": "corr-neg-pass-001",
  "originalCorrelationId": "corr-neg-pass-001",
  "payloadRef": null,
  "payloadContentType": "application/json",
  "occurredAt": "2026-07-03T10:15:30Z",
  "payload": { "addressId": "SAFE" }
}
```
Derived `journeyInstanceId = ji-corr-neg-pass-001`.

**Drive to outcome**
- *Full-stack:* start payload `addressId` = **any value ≠ `"RISK"`** (e.g. `"SAFE"`). `negative-area-pass.json` returns HTTP 200 `result.is_negative:false, score:0.10` → branch arm true.
- *Engine-only:* publish to `cap.verification.response.v1`, key `ji-corr-neg-pass-001`:
```json
{
  "journeyInstanceId": "ji-corr-neg-pass-001",
  "correlationId": "corr-neg-pass-001",
  "nodeId": "n_negativeArea",
  "capabilityKey": "verification",
  "status": "OK",
  "result": {
    "ISSUCCESS": "True",
    "DATA": {
      "Status": "SUCCESS",
      "errorMessage": "",
      "result": [ { "requestId": "r1", "statusCode": "200", "result": { "is_negative": false, "score": 0.10 } } ]
    }
  },
  "errorClass": null
}
```

**Expected result.** Terminal `n_proceed`, outcome **APPROVED**, emit `NegativeAreaClear`. Transitions: `n_negativeArea COMPLETED` → `n_negativeDecision` (arm) → `n_proceed`. Decision on `orig.decision.v1` (key `APP-NEG-1001`):
```json
{
  "journeyInstanceId": "ji-corr-neg-pass-001",
  "correlationId": "corr-neg-pass-001",
  "applicationRef": "APP-NEG-1001",
  "outcome": "APPROVED",
  "loanId": null,
  "terminalNodeId": "n_proceed",
  "emitted": ["NegativeAreaClear"],
  "source": "SFDC",
  "notificationId": "ntf-neg-pass-001",
  "sfdcRecordId": "a0Xneg001"
}
```
Ops status = **`COMPLETED_APPROVED`**. Verify: `GET /ops/runs/search?key=corr-neg-pass-001` → one summary `status:"COMPLETED_APPROVED"`; `GET /ops/runs/ji-corr-neg-pass-001` → `terminalNodeId:"n_proceed"`, `terminalOutcome:"APPROVED"`, `dlqTopicRef:null`.

---

### P2 — DECLINE arm: address flagged → `n_decline` (COMPLETED_DECLINED)

**Entry** — topic `orig.sfdc.pl.v1`, key `ntf-neg-dec-001`:
```json
{
  "transactionId": "tx-neg-dec-001",
  "schemaVersion": "sfdc-ingress.v1",
  "source": "SFDC",
  "type": "NEGATIVE_AREA_CHECK",
  "notificationId": "ntf-neg-dec-001",
  "orgId": "00D6D00000020HoUAI",
  "sfdcRecordId": "a0Xneg002",
  "applicationRef": "APP-NEG-1002",
  "correlationId": "corr-neg-dec-001",
  "originalCorrelationId": "corr-neg-dec-001",
  "payloadRef": null,
  "payloadContentType": "application/json",
  "occurredAt": "2026-07-03T10:16:30Z",
  "payload": { "addressId": "RISK" }
}
```
Derived `journeyInstanceId = ji-corr-neg-dec-001`.

**Drive to outcome**
- *Full-stack:* start payload `addressId` = **exactly `"RISK"`**. `negative-area-fail.json` returns HTTP 200 `result.is_negative:true, score:0.85` → arm false → default.
- *Engine-only:* publish to `cap.verification.response.v1`, key `ji-corr-neg-dec-001` — identical to P1 but inner `is_negative: true`:
```json
{
  "journeyInstanceId": "ji-corr-neg-dec-001",
  "correlationId": "corr-neg-dec-001",
  "nodeId": "n_negativeArea",
  "capabilityKey": "verification",
  "status": "OK",
  "result": {
    "ISSUCCESS": "True",
    "DATA": {
      "Status": "SUCCESS",
      "errorMessage": "",
      "result": [ { "requestId": "r1", "statusCode": "200", "result": { "is_negative": true, "score": 0.85 } } ]
    }
  },
  "errorClass": null
}
```

**Expected result.** Terminal `n_decline`, outcome **REJECTED**, emit `NegativeAreaFlagged`. This is a clean **business** decline (HTTP-200 envelope), NOT a technical failure — no DLQ, no capability notify. Decision on `orig.decision.v1`: `outcome:"REJECTED"`, `terminalNodeId:"n_decline"`, `emitted:["NegativeAreaFlagged"]`, `loanId:null`. Ops status = **`COMPLETED_DECLINED`** (teal, not red). Verify: `GET /ops/runs/search?key=corr-neg-dec-001` → `status:"COMPLETED_DECLINED"`; detail `terminalOutcome:"REJECTED"`, `dlqTopicRef:null`.

---

### P3 — ERROR class TRANSIENT → `n_negativeError` (FAILED_SFDC_NOTIFIED)

**Entry** — topic `orig.sfdc.pl.v1`, key `ntf-neg-t-001`:
```json
{
  "transactionId": "tx-neg-t-001",
  "schemaVersion": "sfdc-ingress.v1",
  "source": "SFDC",
  "type": "NEGATIVE_AREA_CHECK",
  "notificationId": "ntf-neg-t-001",
  "orgId": "00D6D00000020HoUAI",
  "sfdcRecordId": "a0Xneg003",
  "applicationRef": "APP-NEG-1003",
  "correlationId": "corr-neg-t-001",
  "originalCorrelationId": "corr-neg-t-001",
  "payloadRef": null,
  "payloadContentType": "application/json",
  "occurredAt": "2026-07-03T10:17:30Z",
  "payload": { "addressId": "SAFE" }
}
```
Derived `journeyInstanceId = ji-corr-neg-t-001`.

**Drive to outcome**
- *Full-stack:* force a **5xx or connection failure** on `/karza/negative-area` — e.g. stub returns **HTTP 503** (`KarzaClient`: 5xx → `TRANSIENT`, code `HTTP_503`), or **stop `mock-karza`** (connect/IO → `TRANSIENT`, code `IO`). The capability retries per `idempotentReads` policy, then emits one terminal ERROR, writes `cap.verification.dlq.v1` + notifies `sfdc.response.notify.v1`.
- *Engine-only:* publish to `cap.verification.response.v1`, key `ji-corr-neg-t-001`:
```json
{
  "journeyInstanceId": "ji-corr-neg-t-001",
  "correlationId": "corr-neg-t-001",
  "nodeId": "n_negativeArea",
  "capabilityKey": "verification",
  "status": "ERROR",
  "result": {},
  "errorClass": "TRANSIENT"
}
```

**Expected result.** `n_negativeArea` has **no `retry` spec**, so the engine does not retry — it routes `onFailure` → terminal `n_negativeError` on the first ERROR. Outcome **ERROR**, emit `NegativeAreaError`. Decision on `orig.decision.v1`: `outcome:"ERROR"`, `terminalNodeId:"n_negativeError"`, `emitted:["NegativeAreaError"]`, `loanId:null`. Ops status = **`FAILED_SFDC_NOTIFIED`** (`sfdcNotified:"SENT"`). Verify: `GET /ops/runs/ji-corr-neg-t-001` → `status:"FAILED_SFDC_NOTIFIED"`, `terminalNodeId:"n_negativeError"`, `terminalOutcome:"ERROR"`, `dlqTopicRef:"orig.sfdc.dlq.v1"`, and `nodeStats:[{"nodeId":"n_negativeArea","attempts":1,"failureClass":"TRANSIENT"}]`. (Full-stack DLQ topic actually written = `cap.verification.dlq.v1`.)

---

### P4 — ERROR class PERMANENT → `n_negativeError` (FAILED_SFDC_NOTIFIED)

**Entry** — topic `orig.sfdc.pl.v1`, key `ntf-neg-p-001`:
```json
{
  "transactionId": "tx-neg-p-001",
  "schemaVersion": "sfdc-ingress.v1",
  "source": "SFDC",
  "type": "NEGATIVE_AREA_CHECK",
  "notificationId": "ntf-neg-p-001",
  "orgId": "00D6D00000020HoUAI",
  "sfdcRecordId": "a0Xneg004",
  "applicationRef": "APP-NEG-1004",
  "correlationId": "corr-neg-p-001",
  "originalCorrelationId": "corr-neg-p-001",
  "payloadRef": null,
  "payloadContentType": "application/json",
  "occurredAt": "2026-07-03T10:18:30Z",
  "payload": { "addressId": "SAFE" }
}
```
Derived `journeyInstanceId = ji-corr-neg-p-001`.

**Drive to outcome**
- *Full-stack:* force a **4xx or empty body** — point the route at a path with no stub (**WireMock 404** → `PERMANENT`, code `HTTP_404`), return **HTTP 400** (`PERMANENT`, `HTTP_400`), or an **empty 200 body** (`PERMANENT`, `EMPTY_RESPONSE`). No retry (PERMANENT is not retryable); immediate DLQ + notify.
- *Engine-only:* publish to `cap.verification.response.v1`, key `ji-corr-neg-p-001`:
```json
{
  "journeyInstanceId": "ji-corr-neg-p-001",
  "correlationId": "corr-neg-p-001",
  "nodeId": "n_negativeArea",
  "capabilityKey": "verification",
  "status": "ERROR",
  "result": {},
  "errorClass": "PERMANENT"
}
```

**Expected result.** Terminal `n_negativeError`, outcome **ERROR**, emit `NegativeAreaError`. Ops status = **`FAILED_SFDC_NOTIFIED`**; detail `nodeStats:[{"nodeId":"n_negativeArea","attempts":1,"failureClass":"PERMANENT"}]`. Verify: `GET /ops/runs/search?key=corr-neg-p-001`.

---

### P5 — ERROR class AMBIGUOUS (and null errorClass) → `n_negativeError` (FAILED_SFDC_NOTIFIED)

**Entry** — topic `orig.sfdc.pl.v1`, key `ntf-neg-a-001`:
```json
{
  "transactionId": "tx-neg-a-001",
  "schemaVersion": "sfdc-ingress.v1",
  "source": "SFDC",
  "type": "NEGATIVE_AREA_CHECK",
  "notificationId": "ntf-neg-a-001",
  "orgId": "00D6D00000020HoUAI",
  "sfdcRecordId": "a0Xneg005",
  "applicationRef": "APP-NEG-1005",
  "correlationId": "corr-neg-a-001",
  "originalCorrelationId": "corr-neg-a-001",
  "payloadRef": null,
  "payloadContentType": "application/json",
  "occurredAt": "2026-07-03T10:19:30Z",
  "payload": { "addressId": "SAFE" }
}
```
Derived `journeyInstanceId = ji-corr-neg-a-001`.

**Drive to outcome**
- *Full-stack:* force a **read timeout** — add a fixed-delay stub on `/karza/negative-area` exceeding the **20 s** read timeout (`SocketTimeoutException` → `AMBIGUOUS`, code `READ_TIMEOUT`). Because the node is `idempotent:true`, the verification policy retries AMBIGUOUS, then emits terminal ERROR + DLQ/notify.
- *Engine-only:* publish to `cap.verification.response.v1`, key `ji-corr-neg-a-001`:
```json
{
  "journeyInstanceId": "ji-corr-neg-a-001",
  "correlationId": "corr-neg-a-001",
  "nodeId": "n_negativeArea",
  "capabilityKey": "verification",
  "status": "ERROR",
  "result": {},
  "errorClass": "AMBIGUOUS"
}
```
  *Null-errorClass variant:* send the same body with `"errorClass": null` (or omit the field). The engine's `failureClassOf` treats a missing/null class as **AMBIGUOUS** — same terminal.

**Expected result.** Terminal `n_negativeError`, outcome **ERROR**, emit `NegativeAreaError`. Ops status = **`FAILED_SFDC_NOTIFIED`**; `nodeStats` `failureClass:"AMBIGUOUS"`. Note: since the node declares no `retry`, all of TRANSIENT/PERMANENT/AMBIGUOUS/null yield the **same terminal** (`n_negativeError`/ERROR); the only observable difference is `nodeStats[0].failureClass` in run detail. Verify: `GET /ops/runs/ji-corr-neg-a-001`.

---

### P6 — Failure class BREAKER_OPEN — NOT reachable in this journey

There is **no permutation** that reaches `BREAKER_OPEN` here. `n_negativeArea` declares no `circuitBreaker` policy, and the verification path (`KarzaClient` + `VerificationDispatcher`) only ever classifies `TRANSIENT` / `PERMANENT` / `AMBIGUOUS`. `BREAKER_OPEN` is a value the ops `nodeStats.failureClass` enum can carry, but for this journey it can never be produced. (Recorded here for completeness so a tester does not hunt for it.)

---

### P7 — Idempotency / duplicate resend (same id)

**Entry.** Publish the **exact P1 envelope again** to `orig.sfdc.pl.v1`, same key `ntf-neg-pass-001` (same `correlationId = corr-neg-pass-001`). Do this while the first run is mid-flight and again after it terminated.

**Drive to outcome.** No capability action needed — the dedup is at run start. `dedupKey = corr-neg-pass-001` → `instanceId = ji-corr-neg-pass-001`; `store.insertIfAbsent` loses on the redelivery → logged `journey.start.duplicate` and **dropped** (it does not resume or re-run, even if the winner is still live).

**Expected result.** Still exactly **one** run. No second `cap.verification.request.v1` is emitted, no second decision. Verify: `GET /ops/runs/search?key=corr-neg-pass-001` returns a **single** `RunSummaryDto` (newest-first list has length 1), unchanged `startedAt`. (Contrast: a genuinely new business case must carry a **new** `correlationId`/`notificationId` to get its own run.)

---

### P8 — Unknown-type fail-closed (poison → engine DLQ)

**Entry** — topic `orig.sfdc.pl.v1`, key `ntf-neg-badtype-001`, a `type` with no `type-to-journey` row:
```json
{
  "transactionId": "tx-neg-badtype-001",
  "schemaVersion": "sfdc-ingress.v1",
  "source": "SFDC",
  "type": "NEGATIVE_AREA_TYPO",
  "notificationId": "ntf-neg-badtype-001",
  "orgId": "00D6D00000020HoUAI",
  "sfdcRecordId": "a0Xneg006",
  "applicationRef": "APP-NEG-1006",
  "correlationId": "corr-neg-badtype-001",
  "originalCorrelationId": "corr-neg-badtype-001",
  "payloadRef": null,
  "payloadContentType": "application/json",
  "occurredAt": "2026-07-03T10:20:30Z",
  "payload": { "addressId": "SAFE" }
}
```

**Drive to outcome.** None. `JourneyOrchestrator.onOrigination` → `registry.resolveForType("NEGATIVE_AREA_TYPO")` → `UnroutableTypeException` (A2 fail-closed; no default journey) → wrapped `PoisonMessageException` → straight to DLQ, no retry.

**Expected result.** No run started (no ops record). Message dead-lettered to **`orig.sfdc.pl.v1.dlq`**. Verify: consume `orig.sfdc.pl.v1.dlq` in Kafka UI (the original value + error headers); `GET /ops/runs/search?key=corr-neg-badtype-001` → empty list. (Same DLQ path for an **undeserializable** envelope value → `PoisonMessageException`.)

---

### P9 — Unknown-enum fail-closed on the response (bad `status`/`errorClass`)

**Entry.** Start a normal run first (reuse P1's envelope with a fresh id, `correlationId = corr-neg-enum-001` → `ji-corr-neg-enum-001`). Then, in engine-only mode, publish a **malformed** response to `cap.verification.response.v1`, key `ji-corr-neg-enum-001`:
```json
{
  "journeyInstanceId": "ji-corr-neg-enum-001",
  "correlationId": "corr-neg-enum-001",
  "nodeId": "n_negativeArea",
  "capabilityKey": "verification",
  "status": "MAYBE",
  "result": {},
  "errorClass": "SORT_OF"
}
```

**Drive to outcome.** `status`/`errorClass` are strict enums (`CapabilityStatus` OK|ERROR, `ErrorClass` TRANSIENT|PERMANENT|AMBIGUOUS). `MAYBE`/`SORT_OF` fail Jackson deserialization in `CapabilityResponseConsumer` → `PoisonMessageException` → response is dead-lettered; it never touches the run.

**Expected result.** Message routed to **`cap.verification.response.v1.dlq`**. The run is **not advanced** — `n_negativeArea` stays pending (and will later be caught by the liveness sweeper, see P10). Verify: consume `cap.verification.response.v1.dlq`; `GET /ops/runs/ji-corr-neg-enum-001` → still `status:"RUNNING"` immediately after.

---

### P10 — Stuck run / liveness sweeper (no response ever arrives)

**Entry.** Start a run with the P1 envelope but a fresh id — `notificationId = ntf-neg-stuck-001`, `correlationId = corr-neg-stuck-001` → `ji-corr-neg-stuck-001`. Then **do nothing**: engine-only mode with no `cap.verification.response.v1` ever published (equivalently, full-stack with the verification capability stopped so nothing consumes `cap.verification.request.v1`).

**Drive to outcome.** The run sits `RUNNING` at `n_negativeArea`. Liveness config: run-budget **900 s**, sweep interval **60 s**, stuck-flag threshold **840 s** (`900 − 60`).
- At `startedAt + 840 s` the ops read-model flags it (`isStuck()` true) — surfaced by `stuckOnly`, not yet swept.
- At `startedAt + 900 s` the sweeper force-fails it: publishes an ERROR `JourneyDecision` (**notify first**), `markSfdcNotified`, `fail("__timeout__", ERROR)`, emits ops event `run.sweptTimeout`.

Sweeper decision on `orig.decision.v1` (key `APP-NEG-1007`):
```json
{
  "journeyInstanceId": "ji-corr-neg-stuck-001",
  "correlationId": "corr-neg-stuck-001",
  "applicationRef": "APP-NEG-1007",
  "outcome": "ERROR",
  "loanId": null,
  "terminalNodeId": "__timeout__",
  "emitted": [],
  "source": "SFDC",
  "notificationId": "ntf-neg-stuck-001",
  "sfdcRecordId": "a0Xneg007"
}
```

**Expected result.**
- Between 840–900 s: `GET /ops/runs?stuckOnly=true` lists it; `GET /ops/runs/search?key=corr-neg-stuck-001` shows `status:"RUNNING"`, `stuck:true`, `sweepDeadline = startedAt + 900s`.
- After 900 s: ops status = **`FAILED_SFDC_NOTIFIED`**, `terminalNodeId:"__timeout__"`, `terminalOutcome:"ERROR"`, `sweepDeadline:null`, `stuck:false`. Verify via `GET /ops/runs/ji-corr-neg-stuck-001`.

---

### P11 — Maker-checker lifecycle (publishing THIS journey via the registry)

The registry is how you get `negative-area-verification` into the engine in **registry mode** (`IDFC_ENGINE_JOURNEY_SOURCE=registry`). Base URL `http://localhost:8104/api/v1`; every call needs `X-Registry-Token: dev-registry-token`; writes also need `X-User-Id`. Journey key = `negative-area-verification`.

**P11a — Happy lifecycle (create → draft → submit → approve=publish).**
```bash
REG=http://localhost:8104/api/v1
TOK='X-Registry-Token: dev-registry-token'

# create journey (maker) -> 201
curl -i -X POST $REG/journeys -H "$TOK" -H 'X-User-Id: maker@bank' -H 'Content-Type: application/json' \
  -d '{"key":"negative-area-verification","name":"Negative Area Verification","businessLine":"RETAIL","product":"PL","partner":null}'

# create draft (maker) — config = the journey definition object -> 201 {status:"draft", version:1}
curl -i -X POST $REG/journeys/negative-area-verification/versions -H "$TOK" -H 'X-User-Id: maker@bank' -H 'Content-Type: application/json' \
  -d '{"config":{"journeyKey":"negative-area-verification","version":1,"schemaVersion":2,"context":{"schemaRef":"verification-context@1"},"startNodeId":"n_negativeArea","nodes":[{"id":"n_negativeArea","type":"task","capability":"verification","operation":"ENT_KARZA_NEGATIVE_AREA_TAGGING","input":"{ addressId: context.addressId, consent: '"'"'Y'"'"' }","output":"context.negativeArea","idempotent":true,"onFailure":"n_negativeError","next":["n_negativeDecision"]},{"id":"n_negativeDecision","type":"branch","arms":[{"when":"context.negativeArea.ISSUCCESS == '"'"'True'"'"' && context.negativeArea.DATA.result[0].result.is_negative == false","next":"n_proceed"}],"default":"n_decline"},{"id":"n_proceed","type":"terminal","action":"push_decision_to_channel","emit":["NegativeAreaClear"],"status":"completed"},{"id":"n_decline","type":"terminal","action":"push_decision_to_channel","emit":["NegativeAreaFlagged"],"status":"rejected"},{"id":"n_negativeError","type":"terminal","action":"push_decision_to_channel","emit":["NegativeAreaError"],"status":"failed"}]},"note":"initial"}'

# submit DRAFT->PENDING_APPROVAL (maker) -> 200 {status:"pendingApproval"}
curl -i -X POST $REG/journeys/negative-area-verification/versions/1/submit -H "$TOK" -H 'X-User-Id: maker@bank'

# approve = PUBLISH (DIFFERENT actor = checker) -> 200 {status:"published", approverId:"checker@bank"}
curl -i -X POST $REG/journeys/negative-area-verification/versions/1/approve -H "$TOK" -H 'X-User-Id: checker@bank'
```
Verify published: `GET $REG/published-journeys/negative-area-verification/versions/1 -H "$TOK"` → `PublishedConfigDto` with the config. There is **no `/publish` endpoint** — approve *is* publish, and it moves the active-version pointer.

**P11b — 403 self-approve.** Same actor approves/rejects their own version:
```bash
curl -i -X POST $REG/journeys/negative-area-verification/versions/1/approve -H "$TOK" -H 'X-User-Id: maker@bank'
```
→ **403** `{"error":"FORBIDDEN","message":"maker-checker: author 'maker@bank' may not approve/reject their own version","issues":[]}` (same rule applies to `/reject`).

**P11c — 409 conflict (lifecycle/second-draft).** Each returns `{"error":"CONFLICT",...}`:
- Second editable draft while v1 is DRAFT/PENDING: `POST $REG/journeys/negative-area-verification/versions` → `"journey 'negative-area-verification' already has an editable version (v1)"`.
- Duplicate journey: re-`POST $REG/journeys` with the same key → `"journey 'negative-area-verification' already exists"`.
- `submit` a non-DRAFT (e.g. already published v1) → `"only a DRAFT can be submitted (version 1 is PUBLISHED)"`.
- `approve`/`reject` a non-PENDING version → `"only a PENDING_APPROVAL version can be approved/rejected (version 1 is PUBLISHED)"`.
- `PUT` save on a non-DRAFT → `"version 1 of 'negative-area-verification' is PUBLISHED — published/rejected/pending versions are immutable"`.
- Concurrent approve+reject race loser → `"version 1 of 'negative-area-verification' was already finalized by another checker"`.

**P11d — 422 validation.** `{"error":"VALIDATION_FAILED",...}`:
- Bad key on create (not lowercase kebab): `POST $REG/journeys` with `"key":"Negative_Area"` → `"journey key must be lowercase kebab-case ([a-z0-9-]), e.g. 'loan-origination'"`, `issues:[]`.
- Submit that fails the graph gate — create a draft with an empty/invalid graph then submit:
  ```bash
  curl -i -X POST $REG/journeys/negative-area-verification/versions -H "$TOK" -H 'X-User-Id: maker@bank' -H 'Content-Type: application/json' -d '{"config":{"nodes":[],"edges":[]},"note":"broken"}'
  curl -i -X POST $REG/journeys/negative-area-verification/versions/2/submit -H "$TOK" -H 'X-User-Id: maker@bank'
  ```
  → **422** `{"error":"VALIDATION_FAILED","message":"journey 'negative-area-verification' v2 fails validation","issues":[{"code":"emptyDag","severity":"error",...}]}` (the `issues[]` array is populated).

**Auth negatives.** Missing/invalid `X-Registry-Token` → 401 `"invalid or missing X-Registry-Token"`; missing `X-User-Id` on any write → 401 `"X-User-Id header is required for this operation"`.

---

### P12 — Ops verification surface

Ops API base `http://localhost:8082/ops`; every call needs `X-Ops-Token: dev-ops-token` **and** `X-User-Id`. Set `H='-H X-Ops-Token:dev-ops-token -H X-User-Id:ops.analyst@bank'`.

| Check | Call | Expect |
|---|---|---|
| List (defaults) | `curl -s $H "$OPS/runs"` | `PageDto`, `page:0`, `size:50`, sorted `startedAt` DESC; includes the P1/P2/P3… runs |
| Filter by status | `curl -s $H "$OPS/runs?status=COMPLETED_APPROVED"` | only P1-type runs. Allowed: `RUNNING`, `COMPLETED_APPROVED`, `COMPLETED_DECLINED`, `FAILED_SFDC_NOTIFIED`, `FAILED_NOTIFY_PENDING` |
| Filter — decline | `curl -s $H "$OPS/runs?status=COMPLETED_DECLINED"` | P2 run |
| Filter — failed | `curl -s $H "$OPS/runs?status=FAILED_SFDC_NOTIFIED"` | P3/P4/P5/P10 runs |
| Bad status | `curl -i $H "$OPS/runs?status=BOGUS"` | **400** `{"error":"BAD_REQUEST","message":"unknown status 'BOGUS' ..."}` |
| Filter by journeyKey | `curl -s $H "$OPS/runs?journeyKey=negative-area-verification"` | all runs of this journey (exact match) |
| Time window | `curl -s $H "$OPS/runs?since=2026-07-03T00:00:00Z&until=2026-07-04T00:00:00Z"` | `startedAt` within window; bad instant → 400 |
| stuckOnly | `curl -s $H "$OPS/runs?status=RUNNING&stuckOnly=true"` | only RUNNING past 840 s (P10 between 840–900 s); empty otherwise |
| Paging | `curl -s $H "$OPS/runs?page=0&size=25"` | `size` clamped to 1..200 |
| Exact search | `curl -s $H "$OPS/runs/search?key=corr-neg-pass-001"` | `List<RunSummaryDto>` newest-first; also works with `key=`= `notificationId` (`ntf-neg-pass-001`), `sfdcRecordId` (`a0Xneg001`), or `runId` (`ji-corr-neg-pass-001`) |
| Blank search | `curl -i $H "$OPS/runs/search?key="` | **400** `"query parameter 'key' must be a non-blank exact id ..."` |
| Detail | `curl -s $H "$OPS/runs/ji-corr-neg-t-001"` | `RunDetailDto` with `transitions[]`, `nodeStats[]` (`failureClass`), `terminalNodeId`, `sfdcNotified`, `dlqTopicRef`, `sweepDeadline` |
| Unknown detail | `curl -i $H "$OPS/runs/ji-does-not-exist"` | **404**, empty body |
| Auth negatives | `curl -i "$OPS/runs" -H 'X-User-Id:x'` / `curl -i "$OPS/runs" -H 'X-Ops-Token:dev-ops-token'` | 401 `"invalid or missing X-Ops-Token"` / 401 `"X-User-Id header is required for the ops API"` |

Per-run detail cross-checks for this journey: PASS → `status:COMPLETED_APPROVED`, `terminalNodeId:n_proceed`, `dlqTopicRef:null`; DECLINE → `COMPLETED_DECLINED`, `n_decline`, `dlqTopicRef:null`; each ERROR → `FAILED_SFDC_NOTIFIED`, `n_negativeError`, `nodeStats[0].failureClass` = the class you drove; sweeper → `FAILED_SFDC_NOTIFIED`, `terminalNodeId:__timeout__`.


---

<a id="sec-domain-check"></a>

## Journey: domain-check-verification

Karza email/domain-legitimacy check (`svcName = KARZA_DOMAIN_CHECK`, capability `verification`). Single verification hop, then a business branch.

**Definition** (`orchestration/origination-journey/src/main/resources/journeys/domain-check-verification.journey.json`, `journeyKey=domain-check-verification`, `version=1`, `schemaVersion=2`, `startNodeId=n_domainCheck`):

| node | type | detail |
|---|---|---|
| `n_domainCheck` | task (start, `idempotent:true`) | `capability=verification`, `operation=KARZA_DOMAIN_CHECK`, `input="{ organizationName: context.organizationName, individualName: context.individualName, email: context.email, consent: 'Y' }"`, `output=context.domainCheck`, `onFailure=n_domainError`, `next=[n_domainDecision]` |
| `n_domainDecision` | branch | arm → `n_proceed`; `default` → `n_decline` |
| `n_proceed` | terminal | `status=completed` → APPROVED, emit `DomainCheckApproved` |
| `n_decline` | terminal | `status=rejected` → REJECTED, emit `DomainCheckDeclined` |
| `n_domainError` | terminal | `status=failed` → ERROR, emit `DomainCheckError` |

**Exact PASS (arm) condition** — all four clauses must hold:
```
context.domainCheck.ISSUCCESS == 'True'
 && context.domainCheck.DATA.result[0].result.result == true
 && context.domainCheck.DATA.result[0].result.data.disposable == false
 && context.domainCheck.DATA.result[0].result.additional_info.company_info.org_domain_match[0].match == true
```
Anything else falls to `default` → `n_decline`. Note `==` is a **string** compare (`ExpressionEvaluator`), so booleans stringify to `"true"`/`"false"`; a missing path stringifies to `""`.

**Run-identity fields** (from the starting envelope, `JourneyOrchestrator.onOrigination`): `correlationId` → `originalCorrelationId` → `notificationId` → `applicationRef` (first non-null) becomes the dedup key; `instanceId = "ji-" + dedupKey`. Throughout this section: `correlationId=corr-domain-1` ⇒ `journeyInstanceId=ji-corr-domain-1`; ops search key = `corr-domain-1` (or `ntf-domain-1` / the `sfdcRecordId`).

---

### PREREQUISITE — make the journey reachable (do this once, both modes)

The engine's default `idfc.engine.type-to-journey` has no row for this journey and `idfc.engine.journey-resources` loads only `loan-origination`. You MUST both (a) load the definition and (b) add a routing row, or every attempt fail-closes as poison (see the unknown-type permutation).

- **Classpath mode** (`idfc.engine.journey-source=classpath`): start the engine with
  `--idfc.engine.journey-resources[0]=journeys/loan-origination.journey.json --idfc.engine.journey-resources[1]=journeys/domain-check-verification.journey.json --idfc.engine.type-to-journey.KARZA_DOMAIN_CHECK=domain-check-verification`
- **Registry mode** (`idfc.engine.journey-source=registry`): publish `domain-check-verification` via the maker-checker lifecycle below, and still add the `type-to-journey.KARZA_DOMAIN_CHECK=domain-check-verification` row.

All runs below use envelope `type: "KARZA_DOMAIN_CHECK"` (matches the row above). The engine routes on the `type` field, not the topic, so publish onto any origination door — this section uses `orig.sfdc.pl.v1`.

---

### Permutation 1 — PASS → n_proceed (APPROVED)

**Entry (Kafka).** Topic `orig.sfdc.pl.v1`, key = `ntf-domain-1`:
```json
{
  "transactionId": "tx-domain-1",
  "schemaVersion": "sfdc-ingress.v1",
  "source": "SFDC",
  "type": "KARZA_DOMAIN_CHECK",
  "notificationId": "ntf-domain-1",
  "orgId": "00D6D00000020HoUAI",
  "sfdcRecordId": "a0X6D000001abcdEAA",
  "applicationRef": "APP-DOMAIN-1",
  "correlationId": "corr-domain-1",
  "originalCorrelationId": "corr-domain-1",
  "payloadRef": null,
  "payloadContentType": "application/json",
  "occurredAt": "2026-07-03T10:15:30Z",
  "payload": {
    "email": "john.doe@idfcfirstbank.com",
    "organizationName": "IDFC",
    "individualName": "John Doe"
  }
}
```

**Drive to outcome.**
- **Full-stack (WireMock):** lever is `payload.email`. Any value **≠** `temp@disposable.com` matches `infra/mock-vendors/karza/mappings/domain-check-pass.json` (HTTP 200, inner `result:true`, `data.disposable:false`, `org_domain_match[0].match:true`). Nothing else to set.
- **Engine-only (no capability running):** after the engine dispatches `cap.verification.request.v1`, PUBLISH one message to topic `cap.verification.response.v1` (key = `ji-corr-domain-1`):
```json
{
  "journeyInstanceId": "ji-corr-domain-1",
  "correlationId": "corr-domain-1",
  "nodeId": "n_domainCheck",
  "capabilityKey": "verification",
  "status": "OK",
  "result": {
    "ISSUCCESS": "True",
    "DATA": {
      "Status": "SUCCESS",
      "errorMessage": "",
      "result": [
        {
          "requestId": "r1",
          "statusCode": "200",
          "result": {
            "result": true,
            "data": { "disposable": false },
            "additional_info": { "company_info": { "org_domain_match": [ { "match": true } ] } }
          }
        }
      ]
    }
  },
  "errorClass": null
}
```

**Expected result.** Transitions `n_domainCheck` COMPLETED → branch `n_domainDecision` → `n_proceed` COMPLETED. Decision on `orig.decision.v1` (key = `applicationRef` = `APP-DOMAIN-1`): `outcome:"APPROVED"`, `terminalNodeId:"n_proceed"`, `emitted:["DomainCheckApproved"]`. Ops status vocabulary = **`COMPLETED_APPROVED`**. No DLQ. Verify:
```
GET http://localhost:8082/ops/runs/search?key=corr-domain-1
   -H X-Ops-Token: dev-ops-token  -H X-User-Id: ops.analyst@bank
```
→ one `RunSummaryDto` with `status:"COMPLETED_APPROVED"`, `journeyKey:"domain-check-verification"`. Detail `GET /ops/runs/{runId}` shows `terminalNodeId:"n_proceed"`, `terminalOutcome:"APPROVED"`, `dlqTopicRef:null`.

---

### Permutation 2 — DECLINE via disposable email → n_decline (REJECTED)

**Entry (Kafka).** Same as Permutation 1 but `correlationId`/`originalCorrelationId` = `corr-domain-2`, `notificationId` = `ntf-domain-2`, and `payload.email` = `"temp@disposable.com"`.

**Drive to outcome.**
- **Full-stack:** `payload.email == "temp@disposable.com"` matches `domain-check-fail.json` (HTTP 200, inner `data.disposable:true`, `org_domain_match[0].match:false`, `result:true`) — two clauses go false.
- **Engine-only:** publish to `cap.verification.response.v1` (key `ji-corr-domain-2`), `status:"OK"`, `errorClass:null`, with the `result` map identical to Permutation 1 except the inner object:
```json
"result": {
  "result": true,
  "data": { "disposable": true },
  "additional_info": { "company_info": { "org_domain_match": [ { "match": false } ] } }
}
```
(Set `journeyInstanceId:"ji-corr-domain-2"`, `correlationId:"corr-domain-2"`, `nodeId:"n_domainCheck"`, `capabilityKey:"verification"`.)

**Expected result.** `n_domainCheck` COMPLETED → branch `default` → `n_decline`. Decision: `outcome:"REJECTED"`, `terminalNodeId:"n_decline"`, `emitted:["DomainCheckDeclined"]`. Ops status = **`COMPLETED_DECLINED`** (a clean business decline, not a failure — no DLQ, no notify). Verify via `GET /ops/runs/search?key=corr-domain-2` → `status:"COMPLETED_DECLINED"`.

---

### Permutation 3 — DECLINE variant: org-domain mismatch only (engine-only)

Isolates the third clause. Same entry as Perm 2 with `corr-domain-3`/`ntf-domain-3`; `payload.email` any non-disposable value.

**Drive (engine-only).** `cap.verification.response.v1`, key `ji-corr-domain-3`, `status:"OK"`, `errorClass:null`, inner object with `result:true`, `data.disposable:false`, but `org_domain_match[0].match:false`:
```json
"result": { "result": true, "data": { "disposable": false }, "additional_info": { "company_info": { "org_domain_match": [ { "match": false } ] } } }
```
**Full-stack:** not independently selectable — the single WireMock lever is `email`, and its fail stub flips two clauses at once. This isolated arm is engine-only.

**Expected result.** `default` → `n_decline`, `outcome:"REJECTED"`, `emitted:["DomainCheckDeclined"]`, ops `COMPLETED_DECLINED`.

---

### Permutation 4 — DECLINE variant: `result == false` only (engine-only)

Isolates the first business clause. Entry as above with `corr-domain-4`/`ntf-domain-4`.

**Drive (engine-only).** `cap.verification.response.v1`, key `ji-corr-domain-4`, `status:"OK"`, `errorClass:null`, inner object:
```json
"result": { "result": false, "data": { "disposable": false }, "additional_info": { "company_info": { "org_domain_match": [ { "match": true } ] } } }
```
**Expected result.** `default` → `n_decline`, `outcome:"REJECTED"`, ops `COMPLETED_DECLINED`. (Same terminal as Perm 2/3; distinct because a different single clause drove it.)

> Note on `ISSUCCESS`: the real capability's `VerificationEnvelope.success` always sets `ISSUCCESS:"True"` (the `failure()` builder is never called in main code), so that clause is effectively always true on the OK path. A technical failure never reaches this branch — it becomes `status:"ERROR"` and routes `onFailure` (Permutations 5-7).

---

### Permutation 5 — ERROR / TRANSIENT → n_domainError (FAILED)

**Entry (Kafka).** As Permutation 1 with `corr-domain-5`/`ntf-domain-5`.

**Drive to outcome.**
- **Full-stack:** cause a transport failure, not a body value. Stop `mock-karza` (connection refused) → `KarzaClient` throws → `VerificationDispatcher` classifies **TRANSIENT** → retried up to `idfc.verification.retry.max-attempts=3` (exp backoff + jitter, verifications are idempotent reads) → on exhaustion the capability writes to DLQ `cap.verification.dlq.v1` and notify `sfdc.response.notify.v1`, and emits `CapabilityResponse.status=ERROR, errorClass=TRANSIENT`.
- **Engine-only:** publish ONE `cap.verification.response.v1` (key `ji-corr-domain-5`):
```json
{
  "journeyInstanceId": "ji-corr-domain-5",
  "correlationId": "corr-domain-5",
  "nodeId": "n_domainCheck",
  "capabilityKey": "verification",
  "status": "ERROR",
  "result": {},
  "errorClass": "TRANSIENT"
}
```

**Expected result.** `n_domainCheck` FAILED → `onFailure` → `n_domainError` (`status=failed`). Decision on `orig.decision.v1`: `outcome:"ERROR"`, `terminalNodeId:"n_domainError"`, `emitted:["DomainCheckError"]`. Ops status = **`FAILED_SFDC_NOTIFIED`** (decision pushed; if the decision publish can't be confirmed it is `FAILED_NOTIFY_PENDING`). Full-stack DLQ topic = `cap.verification.dlq.v1` + notify `sfdc.response.notify.v1`. `GET /ops/runs/{runId}` shows `terminalOutcome:"ERROR"`, `dlqTopicRef` populated, and `nodeStats[n_domainCheck].failureClass:"TRANSIENT"`.

> Engine-level caveat: `n_domainCheck` declares **no `retrySpec`**, so the *engine* fails the node on the first ERROR regardless of `errorClass`. The 3-attempt retry-then-DLQ+notify ladder lives inside the verification capability. Therefore in engine-only mode DLQ/notify are NOT produced (no capability running) — only the `n_domainError` terminal + ERROR decision. `errorClass` here is cosmetic (surfaces in `nodeStats`).

---

### Permutation 6 — ERROR / PERMANENT → n_domainError (FAILED)

**Entry (Kafka).** As Permutation 1 with `corr-domain-6`/`ntf-domain-6`.

**Drive to outcome.**
- **Full-stack:** point the route at a path with no matching stub → WireMock **404**, or a **400** — client maps 4xx → **PERMANENT** → straight to DLQ `cap.verification.dlq.v1` + notify `sfdc.response.notify.v1` (no retry).
- **Engine-only:** publish `cap.verification.response.v1` (key `ji-corr-domain-6`) identical to Perm 5's ERROR message but `"errorClass": "PERMANENT"` and `journeyInstanceId/correlationId` = `...domain-6`.

**Expected result.** `onFailure` → `n_domainError`; decision `outcome:"ERROR"`, `terminalNodeId:"n_domainError"`, `emitted:["DomainCheckError"]`; ops **`FAILED_SFDC_NOTIFIED`**; full-stack DLQ `cap.verification.dlq.v1`. `nodeStats[n_domainCheck].failureClass:"PERMANENT"`.

---

### Permutation 7 — ERROR / AMBIGUOUS → n_domainError (FAILED)

**Entry (Kafka).** As Permutation 1 with `corr-domain-7`/`ntf-domain-7`.

**Drive to outcome.**
- **Full-stack:** add a delayed/5xx stub that exceeds the node read timeout (`n_domainCheck` has no explicit timeout → default 20 s) → read-timeout → classified **AMBIGUOUS**. Because verifications are idempotent reads, AMBIGUOUS is retried (up to 3) then DLQ + notify. A missing/null `errorClass` on the wire is also treated as AMBIGUOUS by the engine.
- **Engine-only:** publish `cap.verification.response.v1` (key `ji-corr-domain-7`) with `"errorClass": "AMBIGUOUS"` (or omit `errorClass` / set `null` — engine treats null as AMBIGUOUS), `status:"ERROR"`, `result:{}`.

**Expected result.** `onFailure` → `n_domainError`, `outcome:"ERROR"`, `emitted:["DomainCheckError"]`, ops **`FAILED_SFDC_NOTIFIED`**; full-stack DLQ `cap.verification.dlq.v1`. `nodeStats[n_domainCheck].failureClass:"AMBIGUOUS"`.

---

### Permutation 8 — ERROR / BREAKER_OPEN — NOT REACHABLE (documented negative)

`BREAKER_OPEN` appears only as an ops `nodeStats.failureClass` enum name; it is **not** a member of the `ErrorClass` enum (`TRANSIENT`/`PERMANENT`/`AMBIGUOUS`), so it cannot be hand-crafted onto a `cap.verification.response.v1` message, and `n_domainCheck` declares no circuit-breaker policy. There is no lever to produce `BREAKER_OPEN` for this journey. Expected: no such run exists; any attempt to set `"errorClass":"BREAKER_OPEN"` deserializes as an unknown enum and is handled as a poison/AMBIGUOUS response, not a breaker transition.

---

### Permutation 9 — Idempotency: duplicate origination resend (same id)

**Entry (Kafka).** Re-publish the **exact** Permutation 1 envelope to `orig.sfdc.pl.v1` (same key `ntf-domain-1`, same `correlationId:"corr-domain-1"`) — before OR after the first run completes.

**Drive to outcome.** No capability action needed; the second copy is stopped at the start gate.

**Expected result.** `instanceId = ji-corr-domain-1` already exists → `store.insertIfAbsent` loses → logged `journey.start.duplicate`, the resend is **dropped** (it does not resume or re-run, even if the winner is still mid-flight). Verify: `GET /ops/runs/search?key=corr-domain-1` still returns exactly **one** run (not two). No new decision, no DLQ.

---

### Permutation 10 — Idempotency: duplicate capability response (same instance+node)

**Entry.** Engine-only. Start Permutation 1, publish the PASS `cap.verification.response.v1`, let `n_domainCheck` complete, then PUBLISH the **same** response message again (same `journeyInstanceId:"ji-corr-domain-1"`, `nodeId:"n_domainCheck"`).

**Expected result.** The engine's duplicate/late guard: `n_domainCheck` is already COMPLETED (and/or the run is terminal), so the second response is **dropped** — no re-advance, no second branch evaluation, no duplicate decision. `(journeyInstanceId, nodeId)` is effectively consumed once. Ops detail transitions show a single `n_domainCheck` COMPLETED entry.

---

### Permutation 11 — Unknown-type fail-closed (poison → DLQ)

**Entry (Kafka).** Publish to `orig.sfdc.pl.v1` an envelope identical to Permutation 1 but with `"type": "KARZA_DOMAIN_CHKX"` (any value with **no** `type-to-journey` row) — e.g. the case where you forgot the prerequisite config row.
```json
{
  "transactionId": "tx-domain-11", "schemaVersion": "sfdc-ingress.v1", "source": "SFDC",
  "type": "KARZA_DOMAIN_CHKX",
  "notificationId": "ntf-domain-11", "orgId": "00D6D00000020HoUAI", "sfdcRecordId": "a0X6D000001abcdEAA",
  "applicationRef": "APP-DOMAIN-11", "correlationId": "corr-domain-11", "originalCorrelationId": "corr-domain-11",
  "payloadRef": null, "payloadContentType": "application/json", "occurredAt": "2026-07-03T10:15:30Z",
  "payload": { "email": "john.doe@idfcfirstbank.com", "organizationName": "IDFC", "individualName": "John Doe" }
}
```

**Expected result.** `JourneyOrchestrator` → `registry.resolveForType` finds no row → `UnroutableTypeException` (fail-closed A2) → the message is dead-lettered as poison to **`orig.sfdc.pl.v1.dlq`** (source topic + `.dlq`). No run is started — `GET /ops/runs/search?key=corr-domain-11` returns an empty list. (Same fail-closed path applies to a value-undeserializable envelope via `PoisonMessageException`.)

---

### Permutation 12 — Stuck run → sweeper force-fail (`__timeout__`)

**Entry (Kafka).** Start Permutation 1 (`corr-domain-12`/`ntf-domain-12`) but in engine-only mode **never** publish the `cap.verification.response.v1`. In full-stack, keep `mock-karza` reachable but make the capability itself unresponsive so the response never lands (the point is: `n_domainCheck` dispatched, no response).

**Drive to outcome.** Wait. The liveness sweeper runs every `idfc.engine.liveness.sweep-interval-ms` (default 60 s); run budget `idfc.engine.liveness.run-budget-seconds` = 900 s.

**Expected result / verification timeline:**
- **~840 s** (`runBudget − sweepInterval` = 900 − 60): the run is derived **stuck** while still `RUNNING`. Verify with the stuckOnly filter:
  ```
  GET /ops/runs?status=RUNNING&stuckOnly=true&journeyKey=domain-check-verification
  ```
  → the run appears with `status:"RUNNING"`, `stuck:true`, `sweepDeadline` = `startedAt + 900s`.
- **900 s**: sweeper force-fails it — publishes ERROR `JourneyDecision` (`outcome:"ERROR"`, `terminalNodeId:"__timeout__"`, `loanId:null`) FIRST, marks SFDC notified, `fail("__timeout__", ERROR)` → `FAILED`, emits ops event `run.sweptTimeout`.
- Final ops status = **`FAILED_SFDC_NOTIFIED`**; detail `terminalNodeId:"__timeout__"`, `terminalOutcome:"ERROR"`, `stuck:false`, `sweepDeadline:null`. Verify `GET /ops/runs/search?key=corr-domain-12`.

---

### Maker-checker — publishing `domain-check-verification` (registry, port 8104)

Base `http://localhost:8104/api/v1`. Every call: `X-Registry-Token: dev-registry-token`. Writes also need `X-User-Id`. `Content-Type: application/json` on bodied requests. (Below, the draft `config` object is the contents of `domain-check-verification.journey.json`; the server overwrites `config.journeyKey`/`config.version`.)

#### Permutation 13 — Happy lifecycle (create → draft → submit → approve = publish)

1. **Create journey (maker).** `POST /journeys`, headers `X-User-Id: maker@bank`:
```json
{ "key": "domain-check-verification", "name": "Domain Check Verification", "businessLine": "RETAIL", "product": "VERIFICATION", "partner": null }
```
→ **201** `JourneyDto` (`activeVersion:null`).

2. **Create draft (maker).** `POST /journeys/domain-check-verification/versions`, `X-User-Id: maker@bank`:
```json
{ "config": { "startNodeId": "n_domainCheck", "nodes": [ /* the five nodes from the journey file */ ] }, "note": "domain-check v1" }
```
→ **201** `VersionDto` (`status:"draft"`, `version:1`, `authorId:"maker@bank"`).

3. **Validate (no actor).** `POST /journeys/domain-check-verification/versions/1/validate` → **200** `ValidationResultDto` (must have no `severity:"error"` issue to submit).
4. **Submit (maker).** `POST /journeys/domain-check-verification/versions/1/submit`, `X-User-Id: maker@bank` → **200** `VersionDto` (`status:"pendingApproval"`).
5. **Approve = publish (checker, different actor).** `POST /journeys/domain-check-verification/versions/1/approve`, `X-User-Id: checker@bank` → **200** `VersionDto` (`status:"published"`, `approverId:"checker@bank"`). The published pointer moves; `GET /published-journeys/domain-check-verification/versions/1` now returns the config (there is no separate `/publish` endpoint — approve IS publish).

#### Permutation 14 — 403 self-approve

After step 4 above, the **author** tries to approve/reject their own version. `POST .../versions/1/approve` with `X-User-Id: maker@bank`:
→ **403** `{"error":"FORBIDDEN","message":"maker-checker: author 'maker@bank' may not approve/reject their own version","issues":[]}`. Same 403 on `.../reject` by the author.

#### Permutation 15 — 409 conflicts

- **Second editable draft:** with v1 still DRAFT or PENDING_APPROVAL, `POST /journeys/domain-check-verification/versions` (`X-User-Id: maker@bank`, body `{"config":{}}`) → **409** `{"error":"CONFLICT","message":"journey 'domain-check-verification' already has an editable version (v1)","issues":[]}`.
- **Duplicate journey:** `POST /journeys` again with `"key":"domain-check-verification"` → **409** `"journey 'domain-check-verification' already exists"`.
- **saveDraft on non-DRAFT:** after submit, `PUT /journeys/domain-check-verification/versions/1` → **409** `"version 1 of 'domain-check-verification' is PENDING_APPROVAL — published/rejected/pending versions are immutable"`.
- **submit on non-DRAFT:** `POST .../versions/1/submit` when already PENDING_APPROVAL → **409** `"only a DRAFT can be submitted (version 1 is PENDING_APPROVAL)"`.
- **approve/reject on non-PENDING:** approve v1 after it is PUBLISHED → **409** `"only a PENDING_APPROVAL version can be approved/rejected (version 1 is PUBLISHED)"`.
- **Lost checker race:** concurrent approve + reject; CAS loser → **409** `"version 1 of 'domain-check-verification' was already finalized by another checker"`.

#### Permutation 16 — 422 validation

- **Bad key on create:** `POST /journeys` with `"key":"Domain_Check"` (not lowercase kebab) → **422** `{"error":"VALIDATION_FAILED","message":"journey key must be lowercase kebab-case ([a-z0-9-]), e.g. 'loan-origination'","issues":[]}`.
- **Empty/non-object config on draft:** `POST /journeys/domain-check-verification/versions` with `{"config":{},"note":"empty"}` then submit → **422** with `issues:[{"code":"emptyDag","severity":"error",...}]`.
- **Submit fails graph gate:** `POST .../submit` when the validator returns any `severity:"error"` issue → **422** `{"error":"VALIDATION_FAILED","message":"journey 'domain-check-verification' v1 fails validation","issues":[ ...findings... ]}`.

---

### Ops read-model (port 8082) — list / filters / search / detail

Base `http://localhost:8082/ops`. Every call: `X-Ops-Token: dev-ops-token` and `X-User-Id: ops.analyst@bank` (a different secret from the registry token by design; both required, else 401). GET-only.

- **List (defaults):** `GET /ops/runs` → `PageDto` (`page:0`, `size:50`, sorted `startedAt` DESC).
- **Filter by status:** `GET /ops/runs?status=COMPLETED_APPROVED` (also `COMPLETED_DECLINED`, `FAILED_SFDC_NOTIFIED`, `FAILED_NOTIFY_PENDING`, `RUNNING`; case-insensitive). Unknown value → **400** `{"error":"BAD_REQUEST","message":"unknown status 'BOGUS' (allowed: [...])"}`.
- **Filter by journeyKey:** `GET /ops/runs?journeyKey=domain-check-verification` (exact match) — isolates this journey's runs (Perms 1-12).
- **Time window:** `GET /ops/runs?since=2026-07-03T00:00:00Z&until=2026-07-04T00:00:00Z` (ISO-8601; bad format → **400**). Filters `startedAt`.
- **stuckOnly:** `GET /ops/runs?status=RUNNING&stuckOnly=true&journeyKey=domain-check-verification` → only RUNNING runs past the 840 s threshold (Perm 12 between ~840 s and 900 s), each with `stuck:true` and a `sweepDeadline`.
- **Pagination:** `GET /ops/runs?page=0&size=25` (`size` clamped 1..200).
- **Exact search:** `GET /ops/runs/search?key=corr-domain-1` → `List<RunSummaryDto>` newest-first; `key` matches any of `runId | correlationId | notificationId | sfdcRecordId` (so `key=ntf-domain-1` or `key=a0X6D000001abcdEAA` also resolve the same run). Blank `key` → **400** `"query parameter 'key' must be a non-blank exact id (runId | correlationId | notificationId | sfdcRecordId)"`.
- **Detail:** `GET /ops/runs/{runId}` → `RunDetailDto` with `status`, `sfdcNotified` (`NONE|PENDING|SENT`), `terminalNodeId`/`terminalOutcome`, ordered `transitions[]`, `nodeStats[]` (`n_domainCheck` `failureClass` on the error permutations), `dlqTopicRef` (non-null only on the FAILED_* statuses), `stuck`, `sweepDeadline`. Unknown `runId` → **404** empty body.

**Per-permutation ops expectations:** Perm 1 → `COMPLETED_APPROVED`; Perms 2-4 → `COMPLETED_DECLINED`; Perms 5-7 → `FAILED_SFDC_NOTIFIED` (`nodeStats[n_domainCheck].failureClass` = TRANSIENT/PERMANENT/AMBIGUOUS respectively); Perm 9 → still a single run; Perm 11 → no run (empty search); Perm 12 → `RUNNING`+`stuck:true` then `FAILED_SFDC_NOTIFIED` with `terminalNodeId:"__timeout__"`.


---

<a id="sec-payment-execution"></a>

---

## Journey: payment-execution (IMPS / UPI_MANDATE / BILL_PAY / unsupported)

### 0. Status of this journey — READ THIS FIRST

`payment-execution` is a **config-shown-not-run** demo journey. Unlike every other journey in this document, it is **not runnable as shipped**. Three independent facts each block it, and they define the only outcomes reachable today:

1. **No journey file exists.** There is no `payment-execution.journey.json` in `orchestration/origination-journey/src/main/resources/journeys/` (the 8 files there do not include it). The classpath source never loads it and the registry is not seeded with it. The only artifact is a **provisional** config in `docs/DEMO_PAYMENTS_CONFIG_SHOWCASE.md` (lines 27-45).
2. **No `type-to-journey` row.** `idfc.engine.type-to-journey` has no key that maps to `payment-execution`. Routing is fail-closed: an inbound `type` with no row throws `UnroutableTypeException`.
3. **The `payments` capability is a stub.** `capabilities/payments/.../PaymentsApplication.java` starts Spring Boot and serves only `/actuator/health` — **no `Capability` bean, no operations**. Nothing consumes `cap.payments.request.v1`. So even if a run started, no vendor/WireMock lever exists and **full-stack mode cannot produce any per-rail outcome** — the run would hang.

Additionally, the provisional JSON itself would not load even if pasted into a journey file:
- it uses `capabilityKey` / `expression` and omits `operation`; the real schemaVersion-2 shape uses `capability` / `operation` and branch `when` + `default`;
- its branch expressions are bare `rail == 'IMPS'`; the real evaluator roots everything under `context`, so `rail` resolves to null;
- it has **no `default` arm** — with the real engine, an unmatched rail hits `JourneyEngine.chooseArm` (line ~375), which throws `IllegalStateException("no branch arm matched and no default …")`. The documented "-> default unsupported" behavior is **not present**; unknown/absent rail fails hard, it does not route to a default.

The section is therefore split into: **(A)** the two behaviors reachable **today with no setup**, **(B)** a **test scaffold** to make the per-rail permutations reachable, then **(C)** every per-rail / failure-class / idempotency permutation driven in **engine-only manual mode** (the only mode that can drive them, because the capability is a stub), then maker-checker authoring and ops verification.

---

## A. Behaviors reachable TODAY (no setup)

### A1. Unknown-type fail-closed at the engine (the honest current state)

This is what actually happens if a QA engineer produces a payments message right now.

- **Entry** (Kafka): topic **`orig.sfdc.pl.v1`**, message **KEY** = `PAY-UNROUTABLE-1-n`, value:

```json
{
  "transactionId": "tx-pay-unroutable-1",
  "schemaVersion": "sfdc-ingress.v1",
  "source": "SFDC",
  "type": "PAYMENT_EXECUTION",
  "notificationId": "PAY-UNROUTABLE-1-n",
  "orgId": "00D6D00000020HoUAI",
  "sfdcRecordId": "a0Xpay0000UNRT",
  "applicationRef": "PAY-APP-UNROUTABLE-1",
  "correlationId": "PAY-UNROUTABLE-1",
  "originalCorrelationId": "PAY-UNROUTABLE-1",
  "payloadRef": null,
  "payloadContentType": "application/json",
  "occurredAt": "2026-07-03T10:15:30Z",
  "payload": { "rail": "IMPS", "amount": 25000 }
}
```

- **Drive to outcome**: nothing to drive. `OriginationConsumer` deserializes, `JourneyOrchestrator.onOrigination` calls `registry.resolveForType("PAYMENT_EXECUTION")` → no row → `UnroutableTypeException` → poison.
- **Expected result**:
  - No run is created (no `ji-…` instance). Ops status vocabulary: **none** — there is nothing in the ops store.
  - Message is dead-lettered to **`orig.sfdc.pl.v1.dlq`** (engine consumer poison DLQ; `<sourceTopic>.dlq` suffix).
  - **VERIFY**: `GET /ops/runs/search?key=PAY-UNROUTABLE-1` returns `[]` (empty list). Confirm the DLQ message landed on `orig.sfdc.pl.v1.dlq` in Kafka UI.
- **Note (edge tier)**: if instead sent through the SFDC SOAP edge (`SVCNAME__c=PAYMENT_EXECUTION`) or the digital edge (`type:"PAYMENT_EXECUTION"`), there is likewise no routing row, so the edge fail-closes first: SFDC → `ConfigNotFoundException` → DLQ **`orig.sfdc.dlq.v1`** + `ACK_DLQ_PERMANENT`; digital → **422** `{"status":"INVALID"}`/`UNROUTABLE`. Payments never reaches the engine through an edge either.

### A2. Stuck → sweeper force-fail (payments stub never answers)

After the B scaffold makes the type routable and the journey loadable, but with the payments capability still a stub (or in engine-only mode if you simply never publish a response), a started run **hangs at `n_validate`** and is caught by the liveness sweeper. This is the realistic default outcome for payment-execution.

- **Entry** (Kafka): topic **`orig.sfdc.pl.v1`**, KEY = `PAY-STUCK-1-n`, value = the IMPS envelope from §C1 but with `correlationId`/`notificationId` set to `PAY-STUCK-1` / `PAY-STUCK-1-n`. Then **publish no `cap.payments.response.v1`**.
- **Drive to outcome**: do nothing. The engine publishes `cap.payments.request.v1` for `n_validate`; the stub never responds.
- **Expected result** (`idfc.engine.liveness`, run-budget 900s, sweep 60s):
  - `t ≈ 0`–`840s`: store state `RUNNING`, ops status **`RUNNING`**, `stuck:false`.
  - `t ≈ 840s` (`startedAt ≤ now − (900−60)`): ops derivation flags **`stuck:true`** while state is still `RUNNING`.
  - `t ≈ 900s`: sweeper force-fails: publishes ERROR `JourneyDecision` (`outcome=ERROR`, `terminalNodeId="__timeout__"`, `loanId=null`) to `orig.decision.v1`, marks SFDC notified, `fail("__timeout__", ERROR)`, emits ops event **`run.sweptTimeout`**. Final ops status: **`FAILED_SFDC_NOTIFIED`**, `terminalNodeId="__timeout__"`, `terminalOutcome="ERROR"`.
  - **VERIFY**: while stuck, `GET /ops/runs?stuckOnly=true` lists it and `sweepDeadline` = `startedAt + 900s`. After the sweep, `GET /ops/runs/search?key=PAY-STUCK-1` shows `status:"FAILED_SFDC_NOTIFIED"`, and the detail's `dlqTopicRef` = `orig.sfdc.dlq.v1`, `sfdcNotified:"SENT"`.

---

## B. Test scaffold to make the per-rail permutations reachable

The per-rail permutations in §C require you to (1) map a `type` to the journey and (2) load a **corrected, loadable** journey definition. Both are test-only scaffolding — **nothing below is a shipped artifact.**

**B1. Add the routing row.** Add to `idfc.engine.type-to-journey` (env `IDFC_ENGINE_TYPE_TO_JOURNEY` or the engine `application.yml`):
```
PAYMENT_EXECUTION: payment-execution
```

**B2. Load a corrected journey.** Either add a file to `idfc.engine.journey-resources` (classpath mode) or POST it through the registry (maker-checker, §D). This is the provisional demo JSON translated to the real schemaVersion-2 shape (fields verified against `emandate-cancel.journey.json`), with `context.`-rooted `when` conditions and an explicit `default` arm so "unsupported" has a clean terminal. The task `operation` values are illustrative — the `payments` capability declares none, so they matter only for clean logs; in engine-only mode the operation is never executed.

```json
{
  "journeyKey": "payment-execution",
  "version": 1,
  "schemaVersion": 2,
  "startNodeId": "n_validate",
  "nodes": [
    { "id": "n_validate", "type": "task", "capability": "payments", "operation": "validate", "output": "context.validation", "next": ["n_route"] },
    { "id": "n_route", "type": "branch",
      "arms": [
        { "when": "context.rail == 'IMPS'", "next": "n_imps" },
        { "when": "context.rail == 'UPI_MANDATE'", "next": "n_mandate" },
        { "when": "context.rail == 'BILL_PAY'", "next": "n_bill" }
      ],
      "default": "n_unsupported" },
    { "id": "n_imps",    "type": "task", "capability": "payments", "operation": "executeImps",       "output": "context.execution", "next": ["n_confirm"] },
    { "id": "n_mandate", "type": "task", "capability": "payments", "operation": "executeUpiMandate", "output": "context.execution", "next": ["n_confirm"] },
    { "id": "n_bill",    "type": "task", "capability": "payments", "operation": "executeBillPay",    "output": "context.execution", "next": ["n_confirm"] },
    { "id": "n_confirm", "type": "task", "capability": "payments", "operation": "confirm",           "output": "context.confirmation", "next": ["n_notify"] },
    { "id": "n_notify",      "type": "terminal", "action": "notify_channel", "emit": ["PaymentExecuted"],        "status": "completed" },
    { "id": "n_unsupported", "type": "terminal", "action": "notify_channel", "emit": ["PaymentRailUnsupported"], "status": "rejected" }
  ]
}
```

**Key mechanics for the tester:**
- `context.rail` resolves from the **starting payload key `rail`** (payload is merged into the context root; `rail` is not one of the authoritative identity fields, so it is not shadowed). This is the sole branch driver.
- `instanceId = "ji-" + correlationId` (correlationId is the first non-null dedup key). Every response you publish must echo that exact `journeyInstanceId` + the correct `nodeId`.
- Node chain per rail: `n_validate` → (branch) → `n_<rail>` → `n_confirm` → `n_notify`. So a happy rail needs **three** OK responses (`n_validate`, `n_<rail>`, `n_confirm`).
- No payment node declares `retry`, `onFailure`, `optional`, or a circuit-breaker policy. Therefore any capability `ERROR` (regardless of `errorClass`) fails the node on the first response and fails the run — there is no retry lane and **`BREAKER_OPEN` is unreachable in this journey** (no CB policy on any node, and no live capability to trip one).

**Full-stack mode note (applies to every §C permutation):** capabilities + WireMock **cannot** drive these outcomes — the `payments` module is a stub with no operations and no vendor mock. There is **no vendor/lever/input value**. Every §C permutation is therefore driven **engine-only** by hand-publishing `cap.payments.response.v1` messages.

---

## C. Per-rail and failure permutations (engine-only manual mode)

All response messages go to topic **`cap.payments.response.v1`** (message key = the `journeyInstanceId`, or leave blank — the engine matches on the JSON body's `journeyInstanceId` + `nodeId`, not the Kafka key).

### C1. rail = IMPS → approved

- **Entry** (Kafka): topic **`orig.sfdc.pl.v1`**, KEY = `PAY-IMPS-1-n`, value:

```json
{
  "transactionId": "tx-pay-imps-1",
  "schemaVersion": "sfdc-ingress.v1",
  "source": "SFDC",
  "type": "PAYMENT_EXECUTION",
  "notificationId": "PAY-IMPS-1-n",
  "orgId": "00D6D00000020HoUAI",
  "sfdcRecordId": "a0Xpay0001IMPS",
  "applicationRef": "PAY-APP-IMPS-1",
  "correlationId": "PAY-IMPS-1",
  "originalCorrelationId": "PAY-IMPS-1",
  "payloadRef": null,
  "payloadContentType": "application/json",
  "occurredAt": "2026-07-03T10:15:30Z",
  "payload": {
    "rail": "IMPS",
    "amount": 25000,
    "beneficiaryAccount": "50100112233",
    "ifsc": "IDFB0000001",
    "invoiceNo": "INV-PAY-IMPS-1"
  }
}
```
Run id = **`ji-PAY-IMPS-1`**.

- **Drive to outcome — engine-only** (publish these three, in order, each after seeing the corresponding `cap.payments.request.v1` for that node):

n_validate OK:
```json
{ "journeyInstanceId": "ji-PAY-IMPS-1", "correlationId": "PAY-IMPS-1", "nodeId": "n_validate", "capabilityKey": "payments", "status": "OK", "result": { "validated": true }, "errorClass": null }
```
n_imps OK (branch has already routed on `context.rail == 'IMPS'`):
```json
{ "journeyInstanceId": "ji-PAY-IMPS-1", "correlationId": "PAY-IMPS-1", "nodeId": "n_imps", "capabilityKey": "payments", "status": "OK", "result": { "rail": "IMPS", "utr": "IMPS-UTR-0001", "railStatus": "SETTLED" }, "errorClass": null }
```
n_confirm OK:
```json
{ "journeyInstanceId": "ji-PAY-IMPS-1", "correlationId": "PAY-IMPS-1", "nodeId": "n_confirm", "capabilityKey": "payments", "status": "OK", "result": { "confirmed": true }, "errorClass": null }
```

- **Expected result**:
  - Transitions: `n_validate` COMPLETED → branch `n_route` → `n_imps` COMPLETED → `n_confirm` COMPLETED → terminal **`n_notify`**, status `completed`, emit **`PaymentExecuted`**.
  - `JourneyDecision` → `orig.decision.v1` (key = `applicationRef` = `PAY-APP-IMPS-1`): `outcome:"APPROVED"`, `terminalNodeId:"n_notify"`, `loanId:null`.
  - Ops status vocabulary: **`COMPLETED_APPROVED`**.
  - **VERIFY**: `GET /ops/runs/search?key=PAY-IMPS-1` → one summary `status:"COMPLETED_APPROVED"`. `GET /ops/runs/{runId}` → `terminalNodeId:"n_notify"`, `terminalOutcome:"APPROVED"`, transitions for the four nodes, `dlqTopicRef:null`.

### C2. rail = UPI_MANDATE → approved

- **Entry** (Kafka): topic **`orig.sfdc.pl.v1`**, KEY = `PAY-MANDATE-1-n`, value = the C1 envelope with these changes: `transactionId:"tx-pay-mandate-1"`, `notificationId:"PAY-MANDATE-1-n"`, `sfdcRecordId:"a0Xpay0002MNDT"`, `applicationRef:"PAY-APP-MANDATE-1"`, `correlationId`/`originalCorrelationId:"PAY-MANDATE-1"`, and `payload`:
```json
{ "rail": "UPI_MANDATE", "amount": 25000, "invoiceNo": "INV-PAY-MANDATE-1", "vpa": "payer@idfcbank" }
```
Run id = **`ji-PAY-MANDATE-1`**.

- **Drive to outcome — engine-only**: three OK responses, with `nodeId` = `n_validate`, then **`n_mandate`**, then `n_confirm` (branch routes on `context.rail == 'UPI_MANDATE'`):
```json
{ "journeyInstanceId": "ji-PAY-MANDATE-1", "correlationId": "PAY-MANDATE-1", "nodeId": "n_validate", "capabilityKey": "payments", "status": "OK", "result": { "validated": true }, "errorClass": null }
```
```json
{ "journeyInstanceId": "ji-PAY-MANDATE-1", "correlationId": "PAY-MANDATE-1", "nodeId": "n_mandate", "capabilityKey": "payments", "status": "OK", "result": { "rail": "UPI_MANDATE", "mandateRef": "UMN-0001", "railStatus": "ACTIVE" }, "errorClass": null }
```
```json
{ "journeyInstanceId": "ji-PAY-MANDATE-1", "correlationId": "PAY-MANDATE-1", "nodeId": "n_confirm", "capabilityKey": "payments", "status": "OK", "result": { "confirmed": true }, "errorClass": null }
```
- **Expected result**: terminal `n_notify`, `completed` → **`COMPLETED_APPROVED`**, emit `PaymentExecuted`; decision `APPROVED` on `orig.decision.v1` (key `PAY-APP-MANDATE-1`). **VERIFY**: `GET /ops/runs/search?key=PAY-MANDATE-1`.

### C3. rail = BILL_PAY → approved

- **Entry** (Kafka): topic **`orig.sfdc.pl.v1`**, KEY = `PAY-BILL-1-n`, value = C1 envelope with `transactionId:"tx-pay-bill-1"`, `notificationId:"PAY-BILL-1-n"`, `sfdcRecordId:"a0Xpay0003BILL"`, `applicationRef:"PAY-APP-BILL-1"`, `correlationId`/`originalCorrelationId:"PAY-BILL-1"`, `payload`:
```json
{ "rail": "BILL_PAY", "amount": 1499, "invoiceNo": "INV-PAY-BILL-1", "billerId": "BBPS-IDFC-01", "consumerNo": "99887766" }
```
Run id = **`ji-PAY-BILL-1`**.

- **Drive to outcome — engine-only**: three OK responses, `nodeId` = `n_validate`, then **`n_bill`**, then `n_confirm`:
```json
{ "journeyInstanceId": "ji-PAY-BILL-1", "correlationId": "PAY-BILL-1", "nodeId": "n_validate", "capabilityKey": "payments", "status": "OK", "result": { "validated": true }, "errorClass": null }
```
```json
{ "journeyInstanceId": "ji-PAY-BILL-1", "correlationId": "PAY-BILL-1", "nodeId": "n_bill", "capabilityKey": "payments", "status": "OK", "result": { "rail": "BILL_PAY", "billRefId": "BILL-0001", "railStatus": "PAID" }, "errorClass": null }
```
```json
{ "journeyInstanceId": "ji-PAY-BILL-1", "correlationId": "PAY-BILL-1", "nodeId": "n_confirm", "capabilityKey": "payments", "status": "OK", "result": { "confirmed": true }, "errorClass": null }
```
- **Expected result**: terminal `n_notify`, `completed` → **`COMPLETED_APPROVED`**, emit `PaymentExecuted`; decision `APPROVED` (key `PAY-APP-BILL-1`). **VERIFY**: `GET /ops/runs/search?key=PAY-BILL-1`.

### C4. rail = unsupported enum (e.g. NEFT) → fail-closed decline (corrected journey)

- **Entry** (Kafka): topic **`orig.sfdc.pl.v1`**, KEY = `PAY-NEFT-1-n`, value = C1 envelope with `transactionId:"tx-pay-neft-1"`, `notificationId:"PAY-NEFT-1-n"`, `sfdcRecordId:"a0Xpay0004NEFT"`, `applicationRef:"PAY-APP-NEFT-1"`, `correlationId`/`originalCorrelationId:"PAY-NEFT-1"`, `payload`:
```json
{ "rail": "NEFT", "amount": 25000, "invoiceNo": "INV-PAY-NEFT-1" }
```
Run id = **`ji-PAY-NEFT-1`**.

- **Drive to outcome — engine-only**: publish only **one** OK response for `n_validate`; the branch then evaluates and, because `NEFT` matches no arm, takes `default` → `n_unsupported` (a terminal — no further capability call):
```json
{ "journeyInstanceId": "ji-PAY-NEFT-1", "correlationId": "PAY-NEFT-1", "nodeId": "n_validate", "capabilityKey": "payments", "status": "OK", "result": { "validated": true }, "errorClass": null }
```
- **Expected result** (corrected journey with `default`): terminal **`n_unsupported`**, status `rejected` → **`COMPLETED_DECLINED`** (a clean business decline, not a failure), emit **`PaymentRailUnsupported`**; decision `REJECTED` on `orig.decision.v1` (key `PAY-APP-NEFT-1`), `terminalNodeId:"n_unsupported"`. **VERIFY**: `GET /ops/runs/search?key=PAY-NEFT-1` → `status:"COMPLETED_DECLINED"`.
- **As-written provisional JSON (no `default`)**: the same NEFT input hits `chooseArm` with no matching arm and no default → **`IllegalStateException("no branch arm matched and no default at node 'n_route' …")`**. The branch cannot advance, the run stays `RUNNING`, and it is ultimately **force-failed by the liveness sweeper** at 900s → **`FAILED_SFDC_NOTIFIED`**, `terminalNodeId:"__timeout__"` (as in §A2). This is why the corrected journey adds an explicit `default` — the demo config as documented does **not** route unsupported to a clean terminal.

### C5. rail absent / null → same fail-closed path

- **Entry**: identical to C4 but omit `rail` from the payload entirely (`payload: { "amount": 25000, "invoiceNo": "INV-PAY-NORAIL-1" }`), `correlationId:"PAY-NORAIL-1"`, KEY `PAY-NORAIL-1-n`. Run id `ji-PAY-NORAIL-1`.
- **Drive to outcome — engine-only**: one OK response for `n_validate` (as C4). `context.rail` resolves to missing → stringifies to `""` → no arm matches.
- **Expected result**: corrected journey → `default` `n_unsupported` → **`COMPLETED_DECLINED`**, emit `PaymentRailUnsupported`. Provisional (no default) → `IllegalStateException` → stuck → swept → **`FAILED_SFDC_NOTIFIED`**. **VERIFY**: `GET /ops/runs/search?key=PAY-NORAIL-1`.

### C6. Failure class PERMANENT at n_validate → run FAILED

- **Entry** (Kafka): topic **`orig.sfdc.pl.v1`**, KEY = `PAY-PERM-1-n`, value = C1 envelope with `transactionId:"tx-pay-perm-1"`, `notificationId:"PAY-PERM-1-n"`, `sfdcRecordId:"a0Xpay0005PERM"`, `applicationRef:"PAY-APP-PERM-1"`, `correlationId`/`originalCorrelationId:"PAY-PERM-1"`, `payload:{ "rail":"IMPS", "amount":25000, "invoiceNo":"INV-PAY-PERM-1" }`. Run id `ji-PAY-PERM-1`.
- **Drive to outcome — engine-only**: publish a single ERROR response for `n_validate`:
```json
{ "journeyInstanceId": "ji-PAY-PERM-1", "correlationId": "PAY-PERM-1", "nodeId": "n_validate", "capabilityKey": "payments", "status": "ERROR", "result": {}, "errorClass": "PERMANENT" }
```
- **Expected result**: `n_validate` has no `retry`/`onFailure`/`optional` → the node fails on the first ERROR → `handleNodeFailure` fails the run: emits ERROR `JourneyDecision` (`outcome:"ERROR"`, `terminalNodeId:"n_validate"`, `loanId:null`) to `orig.decision.v1` (notify) → status **`FAILED_SFDC_NOTIFIED`** (or `FAILED_NOTIFY_PENDING` if the decision publish can't be confirmed). The branch is never reached. **VERIFY**: `GET /ops/runs/search?key=PAY-PERM-1` → `FAILED_SFDC_NOTIFIED`; `GET /ops/runs/{runId}` → `terminalNodeId:"n_validate"`, `terminalOutcome:"ERROR"`, `dlqTopicRef:"orig.sfdc.dlq.v1"` (pointer), and `nodeStats` entry `{ "nodeId":"n_validate", "attempts":1, "failureClass":"PERMANENT" }`. (No `cap.payments.response.v1.dlq` message is produced — a well-formed ERROR response is consumed normally, not poison.)

### C7. Failure class TRANSIENT at n_validate → run FAILED (no retry lane)

- **Entry**: as C6 but `notificationId:"PAY-TRANS-1-n"`, `correlationId:"PAY-TRANS-1"`, `applicationRef:"PAY-APP-TRANS-1"`, `sfdcRecordId:"a0Xpay0006TRAN"`, KEY `PAY-TRANS-1-n`. Run id `ji-PAY-TRANS-1`.
- **Drive to outcome — engine-only**:
```json
{ "journeyInstanceId": "ji-PAY-TRANS-1", "correlationId": "PAY-TRANS-1", "nodeId": "n_validate", "capabilityKey": "payments", "status": "ERROR", "result": {}, "errorClass": "TRANSIENT" }
```
- **Expected result**: because `n_validate` declares **no `retrySpec`** (no `retryOn` set contains `TRANSIENT`), `isRetryable` is false → TRANSIENT behaves exactly like PERMANENT here: node fails, run **`FAILED_SFDC_NOTIFIED`**, `terminalNodeId:"n_validate"`. The **only** observable difference from C6 is `nodeStats.failureClass:"TRANSIENT"` in the detail view. **VERIFY**: `GET /ops/runs/{runId}` → `nodeStats[0].failureClass == "TRANSIENT"`.

### C8. Failure class AMBIGUOUS (and null errorClass) at n_validate → run FAILED

- **Entry**: as C6 but `notificationId:"PAY-AMB-1-n"`, `correlationId:"PAY-AMB-1"`, `applicationRef:"PAY-APP-AMB-1"`, `sfdcRecordId:"a0Xpay0007AMBG"`, KEY `PAY-AMB-1-n`. Run id `ji-PAY-AMB-1`.
- **Drive to outcome — engine-only** (either message is equivalent; a null `errorClass` is treated as AMBIGUOUS by the engine):
```json
{ "journeyInstanceId": "ji-PAY-AMB-1", "correlationId": "PAY-AMB-1", "nodeId": "n_validate", "capabilityKey": "payments", "status": "ERROR", "result": {}, "errorClass": "AMBIGUOUS" }
```
or (null class, same effect):
```json
{ "journeyInstanceId": "ji-PAY-AMB-1", "correlationId": "PAY-AMB-1", "nodeId": "n_validate", "capabilityKey": "payments", "status": "ERROR", "result": {}, "errorClass": null }
```
- **Expected result**: no `retrySpec` → not retryable → node fails → **`FAILED_SFDC_NOTIFIED`**, `terminalNodeId:"n_validate"`. Detail `nodeStats.failureClass:"AMBIGUOUS"` (the null-class message also records `AMBIGUOUS`). **VERIFY**: `GET /ops/runs/search?key=PAY-AMB-1`.

### C9. Failure class BREAKER_OPEN → NOT REACHABLE in this journey

- No node in the payment-execution journey declares a circuit-breaker policy, and `BREAKER_OPEN` is not one of the `ErrorClass` enum values a capability response may carry (`ErrorClass` = `TRANSIENT` / `PERMANENT` / `AMBIGUOUS`). `BREAKER_OPEN` surfaces only in `nodeStats.failureClass` when a node with a configured breaker trips — which cannot happen here (no breaker, and the `payments` capability is a stub, so no live call stream to trip one). **There is no way to produce a `BREAKER_OPEN` outcome for this journey.** Document as unreachable.

### C10. Failure at the rail node (n_imps ERROR after a successful validate)

- **Entry**: as C1 but `notificationId:"PAY-IMPSERR-1-n"`, `correlationId:"PAY-IMPSERR-1"`, `applicationRef:"PAY-APP-IMPSERR-1"`, `sfdcRecordId:"a0Xpay0008IERR"`, KEY `PAY-IMPSERR-1-n`, `payload.rail:"IMPS"`. Run id `ji-PAY-IMPSERR-1`.
- **Drive to outcome — engine-only**: OK for `n_validate`, then ERROR for `n_imps`:
```json
{ "journeyInstanceId": "ji-PAY-IMPSERR-1", "correlationId": "PAY-IMPSERR-1", "nodeId": "n_validate", "capabilityKey": "payments", "status": "OK", "result": { "validated": true }, "errorClass": null }
```
```json
{ "journeyInstanceId": "ji-PAY-IMPSERR-1", "correlationId": "PAY-IMPSERR-1", "nodeId": "n_imps", "capabilityKey": "payments", "status": "ERROR", "result": {}, "errorClass": "PERMANENT" }
```
- **Expected result**: branch routed to `n_imps`, which then fails (no retry/onFailure) → run **`FAILED_SFDC_NOTIFIED`**, `terminalNodeId:"n_imps"`, `terminalOutcome:"ERROR"`, `n_confirm`/`n_notify` never reached. **VERIFY**: `GET /ops/runs/{runId}` → transitions show `n_validate` COMPLETED then `n_imps` failed; `nodeStats` has `n_imps` with `failureClass:"PERMANENT"`. (Swap `errorClass` to `TRANSIENT`/`AMBIGUOUS` for the rail-node variants — same FAILED result, different `nodeStats.failureClass`.)

### C11. Failure at n_confirm (rail executed, confirm ERROR)

- **Entry**: as C1 but `notificationId:"PAY-CONFERR-1-n"`, `correlationId:"PAY-CONFERR-1"`, `applicationRef:"PAY-APP-CONFERR-1"`, `sfdcRecordId:"a0Xpay0009CERR"`, KEY `PAY-CONFERR-1-n`, `payload.rail:"IMPS"`. Run id `ji-PAY-CONFERR-1`.
- **Drive to outcome — engine-only**: OK for `n_validate`, OK for `n_imps`, then ERROR for `n_confirm`:
```json
{ "journeyInstanceId": "ji-PAY-CONFERR-1", "correlationId": "PAY-CONFERR-1", "nodeId": "n_validate", "capabilityKey": "payments", "status": "OK", "result": { "validated": true }, "errorClass": null }
```
```json
{ "journeyInstanceId": "ji-PAY-CONFERR-1", "correlationId": "PAY-CONFERR-1", "nodeId": "n_imps", "capabilityKey": "payments", "status": "OK", "result": { "rail": "IMPS", "utr": "IMPS-UTR-9009", "railStatus": "SETTLED" }, "errorClass": null }
```
```json
{ "journeyInstanceId": "ji-PAY-CONFERR-1", "correlationId": "PAY-CONFERR-1", "nodeId": "n_confirm", "capabilityKey": "payments", "status": "ERROR", "result": {}, "errorClass": "AMBIGUOUS" }
```
- **Expected result**: run **`FAILED_SFDC_NOTIFIED`**, `terminalNodeId:"n_confirm"`, `terminalOutcome:"ERROR"`. Note the DAG has **no compensation** on any node (unlike loan-origination's `n_book`), so the settled rail execution is **not** reversed — the failure just ends the run. `dlqTopicRef:"orig.sfdc.dlq.v1"` (pointer), `nodeStats` shows `n_confirm` `failureClass:"AMBIGUOUS"`. **VERIFY**: `GET /ops/runs/search?key=PAY-CONFERR-1`.

### C12. Idempotency — duplicate resend of the same envelope

- **Entry**: re-publish the **exact C1 envelope** to `orig.sfdc.pl.v1` (same `correlationId:"PAY-IMPS-1"`, KEY `PAY-IMPS-1-n`) after C1 has already started (whether C1 is still running or terminal).
- **Drive to outcome**: nothing. The engine derives the same `instanceId = "ji-PAY-IMPS-1"`; `store.insertIfAbsent` loses the race → logged `journey.start.duplicate` and the redelivery is **dropped**. It does **not** start a second run and does **not** resume the first (even if the first is mid-flight; the sweeper is the net).
- **Expected result**: still exactly **one** run `ji-PAY-IMPS-1`. **VERIFY**: `GET /ops/runs/search?key=PAY-IMPS-1` returns a **single** summary (not two), unchanged from C1.

---

## D. Maker-checker: authoring the payment-execution config

Loading the §B2 journey through the registry is itself the payment-execution demo point ("payments authored the same way, versioned + maker-checker"). Base `http://localhost:8104/api/v1`, header `X-Registry-Token: dev-registry-token`; writes also need `X-User-Id`.

### D1. Happy lifecycle (create → draft → submit → approve = publish)

1. **Create journey** (maker):
   - `POST /api/v1/journeys` · headers `X-Registry-Token: dev-registry-token`, `X-User-Id: maker@bank`, `Content-Type: application/json`
   ```json
   { "key": "payment-execution", "name": "Payment Execution", "businessLine": "PAYMENTS", "product": "PAY", "partner": null }
   ```
   → **201** `JourneyDto` (`activeVersion:null`).
2. **Create draft** (maker) — `config` is the §B2 journey object (a real JSON node, not an escaped string; server overwrites `config.journeyKey`/`config.version`):
   - `POST /api/v1/journeys/payment-execution/versions` · `X-User-Id: maker@bank`
   ```json
   { "config": { "journeyKey": "payment-execution", "version": 1, "schemaVersion": 2, "startNodeId": "n_validate", "nodes": [] }, "note": "payments demo config" }
   ```
   → **201** `VersionDto` `{status:"draft", version:1}`.
3. **Save draft** (maker) — `PUT /api/v1/journeys/payment-execution/versions/1` with the full node graph.
4. **Submit** (maker) — `POST /api/v1/journeys/payment-execution/versions/1/submit` · `X-User-Id: maker@bank` → **200** `{status:"pendingApproval"}` (requires the graph to pass the validation gate).
5. **Approve = publish** (checker, different actor) — `POST /api/v1/journeys/payment-execution/versions/1/approve` · `X-User-Id: checker@bank` → **200** `{status:"published", approverId:"checker@bank"}`. The published pointer moves; `GET /api/v1/published-journeys/payment-execution/versions/1` returns the `PublishedConfigDto`.

### D2. 403 self-approve

- `POST /api/v1/journeys/payment-execution/versions/1/approve` with `X-User-Id: maker@bank` (the author) → **403** `{"error":"FORBIDDEN","message":"maker-checker: author 'maker@bank' may not approve/reject their own version","issues":[]}`. Same rule applies to `.../reject` by the author.

### D3. 409 conflicts

- **Second draft while one is editable**: `POST /api/v1/journeys/payment-execution/versions` (maker) while v1 is DRAFT or PENDING_APPROVAL → **409** `{"error":"CONFLICT","message":"journey 'payment-execution' already has an editable version (v1)","issues":[]}`.
- **Duplicate journey**: re-`POST /api/v1/journeys` with `key:"payment-execution"` → **409** `"journey 'payment-execution' already exists"`.
- **Lifecycle 409s**: `saveDraft`/`submit` on a non-DRAFT version, or `approve`/`reject` on a non-PENDING version → **409** (e.g. `"only a PENDING_APPROVAL version can be approved/rejected (version 1 is PUBLISHED)"`).

### D4. 422 validation

- **Bad key**: `POST /api/v1/journeys` with `key:"payment_execution"` (underscore — not lowercase kebab) → **422** `{"error":"VALIDATION_FAILED","message":"journey key must be lowercase kebab-case ([a-z0-9-]), e.g. 'loan-origination'","issues":[]}`. (`payment-execution` with a hyphen is valid.)
- **Submit fails the graph gate**: submit a draft whose config has a graph error (e.g. empty DAG or an orphan node) → **422** `{"error":"VALIDATION_FAILED","message":"journey 'payment-execution' v1 fails validation","issues":[ … severity:"error" … ]}` with `issues[]` populated.

---

## E. Ops verification (list / filters / exact search / detail / stuckOnly)

Base `http://localhost:8082/ops`; headers `X-Ops-Token: dev-ops-token` and `X-User-Id: ops.analyst@bank` on every call (this is a **different** secret from the registry token). Use the runs created in §C.

- **List (defaults)**: `GET /ops/runs` → `PageDto` (page 0, size 50, `startedAt` DESC), includes all payment-execution runs.
- **Filter by status**: `GET /ops/runs?status=COMPLETED_APPROVED` (C1–C3); `?status=FAILED_SFDC_NOTIFIED` (C6–C8, C10–C11, and swept A2/C4-provisional); `?status=COMPLETED_DECLINED` (C4/C5 corrected); `?status=RUNNING` (a run mid-steer). Unknown value → **400** `{"error":"BAD_REQUEST","message":"unknown status 'BOGUS' (allowed: [...])"}`.
- **Filter by journeyKey**: `GET /ops/runs?journeyKey=payment-execution`.
- **Time window**: `GET /ops/runs?since=2026-07-03T00:00:00Z&until=2026-07-04T00:00:00Z` (filters `startedAt`; bad ISO instant → **400**).
- **stuckOnly**: `GET /ops/runs?status=RUNNING&stuckOnly=true` — lists only RUNNING payment runs past the ~840s threshold (see §A2); each carries `sweepDeadline = startedAt + 900s` and `stuck:true`.
- **Pagination**: `GET /ops/runs?page=0&size=25` (size clamped to 1..200).
- **Exact search**: `GET /ops/runs/search?key=PAY-IMPS-1` (correlationId). Also works with the `notificationId` (`PAY-IMPS-1-n`), `sfdcRecordId` (`a0Xpay0001IMPS`), or the `runId`. Returns `List<RunSummaryDto>` newest-first. Blank `key` → **400** (`"query parameter 'key' must be a non-blank exact id …"`).
- **Detail**: `GET /ops/runs/{runId}` (take `runId` from a summary) → `RunDetailDto` with `transitions[]`, `terminalNodeId`/`terminalOutcome`, `sfdcNotified` (`NONE`/`PENDING`/`SENT`), `dlqTopicRef` (set only on `FAILED_*`, = `orig.sfdc.dlq.v1`), `nodeStats[]` (`failureClass` per failed node), `stuck`, `sweepDeadline`, and `compensationOf`/`compensationPending` (always `null`/empty here — this journey has no compensation). Unknown `runId` → **404** empty body.


---

<a id="sec-emandate-autopay"></a>

## Journey: emandate-autopay-setup

Journey file: `orchestration/origination-journey/src/main/resources/journeys/emandate-autopay-setup.journey.json` (`journeyKey=emandate-autopay-setup`, `version=1`, `schemaVersion=2`, `context.schemaRef=emandate-context@1`, `startNodeId=n_setup`).

**Shape (verified against the file):** strictly LINEAR — two nodes, no branch.

| Node | Type | Capability / operation | output | next | Terminal |
|---|---|---|---|---|---|
| `n_setup` | task | `mandate` / `setupAutopayLink` | `context.autopay` | `[n_done]` | — |
| `n_done` | terminal | `action: notify_channel` | — | — | `status:"completed"` → APPROVED, `emit:[AutopayLinkSent]` |

`n_setup` has **no** `retry`, `onFailure`, `optional`, `compensation`, or `meter` policy. Consequences that shape every permutation below:
- There is **exactly one business outcome**: `AutopayLinkSent` / `COMPLETED_APPROVED`. There is no branch node, so the "approved/declined", "each rail", and "found/not-found" arm permutations **do not apply to this journey** — the only non-happy states are hard failures.
- Because there is no `retrySpec`, **any** `CapabilityResponse.status=ERROR` (regardless of `errorClass`) fails the node on the first response → run `FAILED`, terminal node `n_setup`, `outcome=ERROR`, **no emit**.
- `BREAKER_OPEN` is **not reachable** for this journey (no circuit-breaker policy on `n_setup`, and `BREAKER_OPEN` is not a wire `ErrorClass` value — the enum is only `TRANSIENT`/`PERMANENT`/`AMBIGUOUS`). See P5.

`setupAutopayLink` (`MandateService.java` L75-80): requires `payload.invoiceNo` (null/blank → `CapabilityException(PERMANENT, "invoiceNo is required")`); returns `{invoiceNo, autopayLink, sent:true}`. `vendor` is **not** read by this op (`vendorOf` is only called by `register`). The autopay link is produced in-process by `MockAutopayLinkAdapter` (`https://idfcfirst.in/p/<hash>`) — there is **no WireMock stub** for the mandate capability, so "full-stack mode" here means the `mandate` capability running with its in-memory mock adapters.

---

### PREREQUISITE — make this journey reachable (do this once before any Kafka permutation)

This journey ships in `resources/journeys/` but is **NOT loaded and NOT routable by any default profile**: no `idfc.engine.type-to-journey` row maps to `emandate-autopay-setup`, and the classpath loader only lists `journeys/loan-origination.journey.json` (base) or the demo journeys on the local profile — never `emandate-autopay-setup`. You MUST wire BOTH a loader and a type row, then start the engine.

**Option A — classpath mode (simplest for manual runs).** Start the engine (`origination-journey`) with these overrides:
```
--idfc.engine.journey-source=classpath
--idfc.engine.journey-resources=journeys/loan-origination.journey.json,journeys/emandate-autopay-setup.journey.json
--idfc.engine.type-to-journey.EMANDATE_AUTOPAY_SETUP=emandate-autopay-setup
```
**Option B — registry mode.** Set `--idfc.engine.journey-source=registry` (+ `JOURNEY_REGISTRY_URL`, `REGISTRY_AUTH_TOKEN`), publish the journey via the journey-registry maker-checker flow (see P9), and add the same `type-to-journey.EMANDATE_AUTOPAY_SETUP` row.

After wiring, `type:"EMANDATE_AUTOPAY_SETUP"` on an inbound origination envelope resolves to journey key `emandate-autopay-setup`. (The in-process reference test uses `type == journeyKey`; if you prefer, map `--idfc.engine.type-to-journey.emandate-autopay-setup=emandate-autopay-setup` and send `type:"emandate-autopay-setup"`. All examples below use `EMANDATE_AUTOPAY_SETUP`.)

**instanceId derivation** (`JourneyOrchestrator.onOrigination`): `instanceId = "ji-" + firstNonNull(correlationId, originalCorrelationId, notificationId, applicationRef, "unknown")`. With `correlationId:"corr-autopay-1"` → **`instanceId = "ji-corr-autopay-1"`**. Grab the exact value from the engine log line `journey.start instanceId=...`.

---

### P1 — Happy path: AutopayLinkSent (APPROVED) — the only branch arm

**Entry (Kafka).** Topic `orig.sfdc.pl.v1` (any of the four `orig.sfdc.*.v1` topics works — the engine routes on `type`, not topic), message **key = `NOTIF-AUTOPAY-1`** (the `notificationId`), value:
```json
{
  "transactionId": "tx-autopay-1",
  "schemaVersion": "sfdc-ingress.v1",
  "source": "SFDC",
  "type": "EMANDATE_AUTOPAY_SETUP",
  "notificationId": "NOTIF-AUTOPAY-1",
  "orgId": "00D6D00000020HoUAI",
  "sfdcRecordId": "a0X-autopay-1",
  "applicationRef": "APP-AUTOPAY-1",
  "correlationId": "corr-autopay-1",
  "originalCorrelationId": "corr-autopay-1",
  "payloadRef": null,
  "payloadContentType": "application/json",
  "occurredAt": "2026-07-03T10:15:30Z",
  "payload": {
    "invoiceNo": "INV-AUTOPAY-2001"
  }
}
```

**Drive to outcome — Full-stack mode** (`mandate` capability running): the only lever is a **non-blank `payload.invoiceNo`** (`"INV-AUTOPAY-2001"`). `setupAutopayLink` returns OK → `context.autopay = {invoiceNo, autopayLink, sent:true}`. No vendor/WireMock knob is involved.

**Drive to outcome — Engine-only manual mode** (no capabilities): after the start message, the engine publishes `cap.mandate.request.v1` (nodeId `n_setup`, operation `setupAutopayLink`). PUBLISH the response on topic `cap.mandate.response.v1`, **key = `ji-corr-autopay-1`**:
```json
{
  "journeyInstanceId": "ji-corr-autopay-1",
  "correlationId": "corr-autopay-1",
  "nodeId": "n_setup",
  "capabilityKey": "mandate",
  "status": "OK",
  "result": {
    "invoiceNo": "INV-AUTOPAY-2001",
    "autopayLink": "https://idfcfirst.in/p/abc123",
    "sent": true
  },
  "errorClass": null
}
```

**Expected result.**
- Ops status vocabulary: **`COMPLETED_APPROVED`**.
- Terminal node `n_done`, `terminalOutcome=APPROVED`. Transitions: `n_setup COMPLETED` → `n_done` (terminal).
- Decision on `orig.decision.v1` (key = `applicationRef` = `APP-AUTOPAY-1`):
```json
{
  "journeyInstanceId": "ji-corr-autopay-1",
  "correlationId": "corr-autopay-1",
  "applicationRef": "APP-AUTOPAY-1",
  "outcome": "APPROVED",
  "loanId": null,
  "terminalNodeId": "n_done",
  "emitted": ["AutopayLinkSent"],
  "source": "SFDC",
  "notificationId": "NOTIF-AUTOPAY-1",
  "sfdcRecordId": "a0X-autopay-1"
}
```
- Ops events on `ops.journey.events.v1`: `run.started`, `node.dispatched`(n_setup), `node.completed`(n_setup), `run.completed`(outcome APPROVED). No DLQ.
- VERIFY: `GET http://localhost:8082/ops/runs/search?key=corr-autopay-1` (headers `X-Ops-Token: dev-ops-token`, `X-User-Id: ops.analyst@bank`) → one `RunSummaryDto` with `status:"COMPLETED_APPROVED"`, `endedAt` set, `stuck:false`, `sweepDeadline:null`. Also searchable by `key=NOTIF-AUTOPAY-1` or `key=a0X-autopay-1`. Then `GET /ops/runs/{runId}` → `terminalNodeId:"n_done"`, `terminalOutcome:"APPROVED"`, `dlqTopicRef:null`, transition `n_setup COMPLETED`.

---

### P2 — Failure PERMANENT (missing/blank invoiceNo) — full-stack reachable

**Entry (Kafka).** Topic `orig.sfdc.pl.v1`, key = `NOTIF-AUTOPAY-2`:
```json
{
  "transactionId": "tx-autopay-2",
  "schemaVersion": "sfdc-ingress.v1",
  "source": "SFDC",
  "type": "EMANDATE_AUTOPAY_SETUP",
  "notificationId": "NOTIF-AUTOPAY-2",
  "orgId": "00D6D00000020HoUAI",
  "sfdcRecordId": "a0X-autopay-2",
  "applicationRef": "APP-AUTOPAY-2",
  "correlationId": "corr-autopay-2",
  "originalCorrelationId": "corr-autopay-2",
  "payloadRef": null,
  "payloadContentType": "application/json",
  "occurredAt": "2026-07-03T10:15:30Z",
  "payload": {}
}
```
(`payload` omits `invoiceNo`; a blank `"invoiceNo":""` triggers the same path.)

**Drive to outcome — Full-stack mode:** omit/blank `payload.invoiceNo`. `MandateService.required()` throws `CapabilityException(ErrorClass.PERMANENT, "invoiceNo is required")` → dispatcher emits `status=ERROR, errorClass=PERMANENT`. No retry policy → node fails immediately.

**Drive to outcome — Engine-only manual mode:** send the start envelope (with or without invoiceNo — you are supplying the failure yourself), then PUBLISH to `cap.mandate.response.v1`, key `ji-corr-autopay-2`:
```json
{
  "journeyInstanceId": "ji-corr-autopay-2",
  "correlationId": "corr-autopay-2",
  "nodeId": "n_setup",
  "capabilityKey": "mandate",
  "status": "ERROR",
  "result": {},
  "errorClass": "PERMANENT"
}
```

**Expected result.**
- Ops status: **`FAILED_SFDC_NOTIFIED`** (the engine emits the ERROR decision, so the channel was told; `sfdcNotified=SENT`). If the decision publish cannot be confirmed, it is **`FAILED_NOTIFY_PENDING`** instead.
- Terminal node `n_setup`, `terminalOutcome=ERROR`, `emitted=[]` (no emit on failure).
- ERROR decision on `orig.decision.v1` (key `APP-AUTOPAY-2`): `outcome:"ERROR"`, `terminalNodeId:"n_setup"`, `loanId:null`, `emitted:[]`.
- Ops events: `run.started`, `node.dispatched`, `node.failed`(n_setup), `run.failed`(ERROR).
- `RunDetailDto` shows `dlqTopicRef:"orig.sfdc.dlq.v1"` (pointer, set for FAILED_* statuses), `nodeStats:[{nodeId:"n_setup", attempts:1, failureClass:"PERMANENT"}]`.
- VERIFY: `GET /ops/runs/search?key=corr-autopay-2` → `status:"FAILED_SFDC_NOTIFIED"`; `GET /ops/runs/{runId}` → `sfdcNotified:"SENT"`, `terminalNodeId:"n_setup"`, `terminalOutcome:"ERROR"`.

---

### P3 — Failure TRANSIENT (engine-only craft only; no retry ⇒ fails same as permanent)

The five delegating-style capabilities and mandate only ever emit `PERMANENT` on a real error, so **TRANSIENT is not producible in full-stack mode for this op** — it must be hand-crafted. And because `n_setup` has no `retrySpec` containing `TRANSIENT`, a transient error is **not** retried; it fails the run identically to P2 (the only observable difference is `nodeStats.failureClass`).

**Entry (Kafka).** Same as P1/P2 with fresh ids (`notificationId:"NOTIF-AUTOPAY-3"`, `correlationId:"corr-autopay-3"`, `applicationRef:"APP-AUTOPAY-3"`, `payload:{"invoiceNo":"INV-AUTOPAY-2003"}`).

**Drive to outcome — Full-stack mode:** not reachable (mandate mock cannot emit TRANSIENT).

**Drive to outcome — Engine-only manual mode:** PUBLISH to `cap.mandate.response.v1`, key `ji-corr-autopay-3`:
```json
{
  "journeyInstanceId": "ji-corr-autopay-3",
  "correlationId": "corr-autopay-3",
  "nodeId": "n_setup",
  "capabilityKey": "mandate",
  "status": "ERROR",
  "result": {},
  "errorClass": "TRANSIENT"
}
```

**Expected result.** Ops status **`FAILED_SFDC_NOTIFIED`** (no retry attempted, `attempts:1`), terminal `n_setup`, `outcome=ERROR`, `emitted=[]`, `dlqTopicRef:"orig.sfdc.dlq.v1"`. VERIFY via `GET /ops/runs/{runId}` → `nodeStats:[{nodeId:"n_setup", attempts:1, failureClass:"TRANSIENT"}]` (this is the only field that distinguishes it from P2).

---

### P4 — Failure AMBIGUOUS / null errorClass (engine-only craft; no retry ⇒ fails)

A missing/`null` `errorClass` on an ERROR is treated by the engine as `AMBIGUOUS`. With no `retrySpec`, it fails the run.

**Entry (Kafka).** Same envelope shape, fresh ids (`NOTIF-AUTOPAY-4`, `corr-autopay-4`, `APP-AUTOPAY-4`, `payload:{"invoiceNo":"INV-AUTOPAY-2004"}`).

**Drive to outcome — Full-stack mode:** not reachable (mandate mock only emits PERMANENT).

**Drive to outcome — Engine-only manual mode:** PUBLISH to `cap.mandate.response.v1`, key `ji-corr-autopay-4` (either explicit `AMBIGUOUS`, or `"errorClass": null` — both land here):
```json
{
  "journeyInstanceId": "ji-corr-autopay-4",
  "correlationId": "corr-autopay-4",
  "nodeId": "n_setup",
  "capabilityKey": "mandate",
  "status": "ERROR",
  "result": {},
  "errorClass": "AMBIGUOUS"
}
```

**Expected result.** Ops status **`FAILED_SFDC_NOTIFIED`**, terminal `n_setup`, `outcome=ERROR`, `emitted=[]`, `dlqTopicRef:"orig.sfdc.dlq.v1"`, `nodeStats:[{nodeId:"n_setup", attempts:1, failureClass:"AMBIGUOUS"}]` (a `null`-errorClass response yields `failureClass:"AMBIGUOUS"` in the read-model). VERIFY via search key `corr-autopay-4`.

---

### P5 — BREAKER_OPEN — NOT reachable for this journey (documented, no test)

There is no circuit-breaker policy on `n_setup`, and `BREAKER_OPEN` is not a valid wire `ErrorClass` (the enum accepted in `cap.mandate.response.v1` is only `TRANSIENT` / `PERMANENT` / `AMBIGUOUS`). `BREAKER_OPEN` can appear only as a `nodeStats.failureClass` value on journeys that declare a breaker policy — this journey has none. **No message can drive a BREAKER_OPEN outcome here.** (If you publish a garbage `errorClass` string, the response value is undeserializable → routed to `cap.mandate.response.v1.dlq`; it does not fail the run as BREAKER_OPEN.)

---

### P6 — Idempotency / duplicate resend (same id)

**Entry (Kafka).** PUBLISH the P1 envelope **twice** to `orig.sfdc.pl.v1` with the **same** `correlationId:"corr-autopay-1"` (same key `NOTIF-AUTOPAY-1`) — identical body both times.

**Drive to outcome — either mode:** the engine derives the same `instanceId = "ji-corr-autopay-1"` for both. `store.insertIfAbsent(instance)` is the exactly-once gate: the **first** message starts the run; the **second** loses the insert, is logged `journey.start.duplicate`, and is **dropped** (it does NOT resume or re-dispatch, even if the first run is still mid-flight — the sweeper is the net). In engine-only mode, still send only ONE `cap.mandate.response.v1` for `ji-corr-autopay-1`/`n_setup`; a duplicate response for the same `(journeyInstanceId, nodeId)` after `n_setup` completed is dropped by the late/duplicate guard.

**Expected result.** Exactly **one** run. Ops status `COMPLETED_APPROVED` (from the winner). VERIFY: `GET /ops/runs/search?key=corr-autopay-1` returns a **single** `RunSummaryDto` (the search returns a list newest-first — here length 1), not two. No second decision on `orig.decision.v1`, no DLQ.

---

### P7 — Unknown-type fail-closed (unmapped type ⇒ DLQ poison, no run)

Routing is fail-closed. If you publish an origination envelope whose `type` has **no** `idfc.engine.type-to-journey` row (e.g. you forgot the prerequisite row, or you send `type:"EMANDATE_AUTOPAY"` while only `EMANDATE_AUTOPAY_SETUP` is mapped), `JourneyRegistry.resolveForType` throws `UnroutableTypeException` → the message is treated as poison and dead-lettered; **no run starts**.

**Entry (Kafka).** Topic `orig.sfdc.pl.v1`, key `NOTIF-AUTOPAY-7`:
```json
{
  "transactionId": "tx-autopay-7",
  "schemaVersion": "sfdc-ingress.v1",
  "source": "SFDC",
  "type": "EMANDATE_AUTOPAY",
  "notificationId": "NOTIF-AUTOPAY-7",
  "orgId": "00D6D00000020HoUAI",
  "sfdcRecordId": "a0X-autopay-7",
  "applicationRef": "APP-AUTOPAY-7",
  "correlationId": "corr-autopay-7",
  "originalCorrelationId": "corr-autopay-7",
  "payloadRef": null,
  "payloadContentType": "application/json",
  "occurredAt": "2026-07-03T10:15:30Z",
  "payload": {
    "invoiceNo": "INV-AUTOPAY-2007"
  }
}
```

**Drive to outcome:** no lever — the unmapped `type` alone fails closed. (Same fail-closed path if the JSON value is undeserializable → `PoisonMessageException`.)

**Expected result.** No `JourneyInstance`, no decision, no ops event. The engine dead-letters the origination record to the source topic's DLQ = **`orig.sfdc.pl.v1.dlq`** (`.dlq` suffix on the consumed topic). VERIFY: `GET /ops/runs/search?key=corr-autopay-7` → **empty list** (no run was created). Confirm the poison landed on `orig.sfdc.pl.v1.dlq` via Kafka UI.

---

### P8 — Stuck / sweeper force-fail (`__timeout__`)

**Entry (Kafka).** Start the run exactly as P1 with fresh ids (`NOTIF-AUTOPAY-8`, `corr-autopay-8`, `APP-AUTOPAY-8`, `payload:{"invoiceNo":"INV-AUTOPAY-2008"}`) → `instanceId = "ji-corr-autopay-8"`.

**Drive to outcome — Full-stack mode:** stop the `mandate` capability (or don't run it) so the `cap.mandate.request.v1` for `n_setup` never gets a response.

**Drive to outcome — Engine-only manual mode:** send the start envelope and **deliberately do NOT publish any `cap.mandate.response.v1`**. The run stays `RUNNING`.

**Expected result — two observable phases (liveness sweeper: `run-budget-seconds=900`, `sweep-interval-ms=60000`):**
1. **Stuck window (~840s after `startedAt`, before force-fail):** store state still `RUNNING` → ops status **`RUNNING`**, but `stuck:true` (threshold `startedAt <= now − (900 − 60) = now − 840s`), `sweepDeadline = startedAt + 900s`. Surfaced only via `stuckOnly`.
2. **Force-failed (at `startedAt + 900s`):** sweeper publishes an ERROR `JourneyDecision` (`outcome:"ERROR"`, `terminalNodeId:"__timeout__"`, `loanId:null`) FIRST, then `markSfdcNotified()`, then `fail("__timeout__", ERROR)` → store `FAILED`, then emits ops event `run.sweptTimeout`. Final ops status: **`FAILED_SFDC_NOTIFIED`**, `terminalNodeId:"__timeout__"`, `terminalOutcome:"ERROR"`, `dlqTopicRef:"orig.sfdc.dlq.v1"`.

**VERIFY.**
- During phase 1: `GET /ops/runs?stuckOnly=true` lists this run with `status:"RUNNING"`, `stuck:true`; `GET /ops/runs/search?key=corr-autopay-8` shows the same, with `sweepDeadline` populated.
- After phase 2: `GET /ops/runs/{runId}` → `status:"FAILED_SFDC_NOTIFIED"`, `sfdcNotified:"SENT"`, `terminalNodeId:"__timeout__"`, `terminalOutcome:"ERROR"`, `sweepDeadline:null`. The ERROR decision appears on `orig.decision.v1` (key `APP-AUTOPAY-8`) with `terminalNodeId:"__timeout__"`.

---

### P9 — Maker-checker lifecycle for `emandate-autopay-setup` (registry, Option B)

Base `http://localhost:8104/api/v1`. Every call needs `X-Registry-Token: dev-registry-token`. Write/lifecycle ops also need `X-User-Id` (the actor). Content-Type `application/json` on bodied calls.

Common headers table:

| Header | Value | Required on |
|---|---|---|
| `X-Registry-Token` | `dev-registry-token` | every `/api/*` call |
| `X-User-Id` | e.g. `maker@bank` / `checker@bank` | writes only (create journey/draft, save, submit, approve, reject) |
| `Content-Type` | `application/json` | bodied requests |

**Happy lifecycle:**

1. **Create journey** — `POST /api/v1/journeys`, headers `X-User-Id: maker@bank`, body:
```json
{ "key": "emandate-autopay-setup", "name": "Emandate Autopay Setup", "businessLine": "RETAIL", "product": "MANDATE", "partner": null }
```
→ `201` JourneyDto (`activeVersion:null`).

2. **Create draft** — `POST /api/v1/journeys/emandate-autopay-setup/versions`, `X-User-Id: maker@bank`, body (`config` is a real JSON object = the journey artifact; server overwrites `config.journeyKey`/`config.version`):
```json
{
  "config": {
    "journeyKey": "emandate-autopay-setup",
    "version": 1,
    "schemaVersion": 2,
    "context": { "schemaRef": "emandate-context@1" },
    "startNodeId": "n_setup",
    "nodes": [
      { "id": "n_setup", "type": "task", "capability": "mandate", "operation": "setupAutopayLink", "output": "context.autopay", "next": ["n_done"] },
      { "id": "n_done", "type": "terminal", "action": "notify_channel", "emit": ["AutopayLinkSent"], "status": "completed" }
    ]
  },
  "note": "autopay setup v1"
}
```
→ `201` VersionDto `{status:"draft", version:1}`.

3. **Save draft** (optional edit) — `PUT /api/v1/journeys/emandate-autopay-setup/versions/1`, `X-User-Id: maker@bank`, same body shape → `200`.
4. **Validate** — `POST /api/v1/journeys/emandate-autopay-setup/versions/1/validate` (no actor) → `200` ValidationResultDto (`issues:[]` when clean).
5. **Submit** — `POST /api/v1/journeys/emandate-autopay-setup/versions/1/submit`, `X-User-Id: maker@bank` → `200` `{status:"pendingApproval"}`.
6. **Approve = publish** — `POST /api/v1/journeys/emandate-autopay-setup/versions/1/approve`, `X-User-Id: checker@bank` (must differ from author) → `200` `{status:"published", approverId:"checker@bank"}`. Moves the published pointer; engine can now fetch it via `GET /api/v1/published-journeys/emandate-autopay-setup/versions/1`.

**403 self-approve** — `POST .../versions/1/approve` (or `/reject`) with `X-User-Id: maker@bank` (the author):
```json
{ "error": "FORBIDDEN", "message": "maker-checker: author 'maker@bank' may not approve/reject their own version", "issues": [] }
```

**409 conflicts** (all `error:"CONFLICT"`):
- Second draft while v1 is editable — `POST /api/v1/journeys/emandate-autopay-setup/versions` (`X-User-Id: maker@bank`, body `{"config":{}}`) → `"journey 'emandate-autopay-setup' already has an editable version (v1)"`.
- Duplicate journey — repeat step 1 → `"journey 'emandate-autopay-setup' already exists"`.
- Save/submit on a non-DRAFT version → `"version 1 of 'emandate-autopay-setup' is PUBLISHED — published/rejected/pending versions are immutable"` / `"only a DRAFT can be submitted (version 1 is ...)"`.
- Approve/reject a non-PENDING version → `"only a PENDING_APPROVAL version can be approved/rejected (version 1 is ...)"`; concurrent approve+reject loser → `"version 1 of 'emandate-autopay-setup' was already finalized by another checker"`.

**422 validation** (`error:"VALIDATION_FAILED"`):
- Bad key on create — `POST /api/v1/journeys` with `"key":"Emandate_Autopay"` (not lowercase kebab) → `"journey key must be lowercase kebab-case ([a-z0-9-]), e.g. 'loan-origination'"`, `issues:[]`.
- Submit with a graph error (e.g. draft config `{"nodes":[],"edges":[]}`) → `"journey 'emandate-autopay-setup' v1 fails validation"` with `issues:[{code:"emptyDag", severity:"error", ...}]` populated.

---

### P10 — Ops read-model: list, filters, exact search, detail, stuckOnly

Base `http://localhost:8082/ops`. Both headers required on **every** call: `X-Ops-Token: dev-ops-token`, `X-User-Id: ops.analyst@bank`. GET-only.

| Header | Value |
|---|---|
| `X-Ops-Token` | `dev-ops-token` (distinct from the registry token) |
| `X-User-Id` | any non-blank, e.g. `ops.analyst@bank` |

- **List (defaults):** `GET /ops/runs` → `PageDto` (`page:0, size:50`, sorted `startedAt` DESC).
- **Filter by status:** `GET /ops/runs?status=COMPLETED_APPROVED` (P1 run), `?status=FAILED_SFDC_NOTIFIED` (P2/P3/P4/P8), `?status=RUNNING` (P8 stuck window). Allowed values: `RUNNING`, `COMPLETED_APPROVED`, `COMPLETED_DECLINED`, `FAILED_SFDC_NOTIFIED`, `FAILED_NOTIFY_PENDING` (case-insensitive). Unknown → `400 {"error":"BAD_REQUEST","message":"unknown status 'BOGUS' (allowed: [...])"}`. Note: `COMPLETED_DECLINED` will never appear for this journey (no decline branch).
- **Filter by journeyKey:** `GET /ops/runs?journeyKey=emandate-autopay-setup` (exact match).
- **Time window:** `GET /ops/runs?since=2026-07-03T00:00:00Z&until=2026-07-04T00:00:00Z` (ISO-8601; bad format → `400`).
- **stuckOnly:** `GET /ops/runs?status=RUNNING&stuckOnly=true` → only the P8 run in its ~840s–900s window (`stuck:true`, `sweepDeadline` set).
- **Pagination:** `GET /ops/runs?page=0&size=25` (`size` clamped `1..200`).
- **Exact-id search:** `GET /ops/runs/search?key=corr-autopay-1` — exact match across `runId | correlationId | notificationId | sfdcRecordId`; returns `List<RunSummaryDto>` newest-first. Blank `key` → `400 "query parameter 'key' must be a non-blank exact id (runId | correlationId | notificationId | sfdcRecordId)"`. (Also try `key=NOTIF-AUTOPAY-1`, `key=a0X-autopay-1`.)
- **Detail:** `GET /ops/runs/{runId}` → `RunDetailDto` with `status`, `sfdcNotified` (`NONE|PENDING|SENT`), `terminalNodeId`, `terminalOutcome`, `transitions[]`, `dlqTopicRef`, `stuck`, `sweepDeadline`, `nodeStats[]`, `compensationOf` (null here — no compensation node), `compensationPending` (empty). Unknown runId → `404` empty body.
- **Auth failures:** missing/bad `X-Ops-Token` → `401 {"error":"UNAUTHENTICATED","message":"invalid or missing X-Ops-Token"}`; missing `X-User-Id` → `401 {"error":"UNAUTHENTICATED","message":"X-User-Id header is required for the ops API"}`.


---

<a id="sec-emandate-cancel"></a>

---

## Journey: emandate-cancel (found / not-found)

> **Journey definition** (`orchestration/origination-journey/src/main/resources/journeys/emandate-cancel.journey.json`): `journeyKey=emandate-cancel`, `version=1`, `schemaVersion=2`, `startNodeId=n_cancel`.
> Linear-then-branch DAG: `n_cancel` (task `mandate/cancel`) → `n_decide` (branch) → `n_done` (terminal, `completed`, emit `MandateCancelled`) **or** `n_notFound` (terminal, `rejected`, emit `MandateNotFound`).
> Branch `n_decide` has exactly one arm — `when: "context.cancel.found == true"` → `n_done` — and `default: n_notFound`. `==` is a **string** compare (`ExpressionEvaluator.compare`): Boolean `true` stringifies to `"true"`; `false`/absent/null → no match → `default`.
> `context.cancel` is the `result` map of the `cancel` op: `{ invoiceNo, found, cancelled }` (`cancelled == found`). **`found` is the only field that selects the arm.**

### Prerequisite (READ FIRST — this journey is NOT reachable by default)

There is **no `idfc.engine.type-to-journey` row** for `emandate-cancel`, and `emandate-cancel.journey.json` is **not** in the default `idfc.engine.journey-resources` list (classpath source loads only `loan-origination`). The file ships in `resources/journeys/` but the engine does not load or route to it out of the box. To run it from a live Kafka topic you MUST do **both**:

1. **Load the journey definition** — either
   - (classpath) add `journeys/emandate-cancel.journey.json` to `idfc.engine.journey-resources`, **or**
   - (registry mode, `journey-source=registry`) publish it through the maker-checker lifecycle (see the **Maker-checker** subsection below).
2. **Add a routing row** — `idfc.engine.type-to-journey` mapping an inbound `type` → the journey key, e.g. `EMANDATE_CANCEL: emandate-cancel`.

Every permutation below assumes both are done and uses envelope `type: "EMANDATE_CANCEL"`. Fixed IDs reused throughout:

| Thing | Value |
|---|---|
| `correlationId` (→ instanceId seed) | `CORR-CANCEL-1` |
| derived `journeyInstanceId` | `ji-CORR-CANCEL-1` (`"ji-" + firstNonNull(correlationId, originalCorrelationId, notificationId, applicationRef)`) |
| `applicationRef` | `APP-CANCEL-1` |
| `notificationId` | `NOTIF-CANCEL-1` |
| origination topic to publish onto | `orig.sfdc.pl.v1` (any of the four `orig.sfdc.*.v1` — engine routes on `type`, not topic) |
| cap request (engine → mandate) | topic `cap.mandate.request.v1`, key `ji-CORR-CANCEL-1`, `operation:"cancel"`, `nodeId:"n_cancel"`, `idempotencyKey:"ji-CORR-CANCEL-1:n_cancel"` |
| cap response (mandate → engine) | topic `cap.mandate.response.v1` (correlate on JSON body `journeyInstanceId`+`nodeId`; Kafka key ignored for correlation) |
| decision topic | `orig.decision.v1`, key = `applicationRef` |
| ops events topic | `ops.journey.events.v1`, key = `journeyInstanceId` |

**Mock lever (full-stack):** `MockCbsNachAdapter.enquire(invoiceNo)` returns `found=true` **unless** `invoiceNo` contains `"MISSING"` (case-insensitive). So in full-stack mode the branch is driven entirely by the content of `payload.invoiceNo`. A **missing/blank `invoiceNo`** → `CapabilityException(PERMANENT)` (fails the node before enquiry).

---

### Permutation 1 — FOUND → n_done → MandateCancelled (APPROVED / COMPLETED_APPROVED)

**Entry (Kafka).** Topic `orig.sfdc.pl.v1`, key = `NOTIF-CANCEL-1`, value:

```json
{
  "transactionId": "tx-cancel-1",
  "schemaVersion": "sfdc-ingress.v1",
  "source": "SFDC",
  "type": "EMANDATE_CANCEL",
  "notificationId": "NOTIF-CANCEL-1",
  "orgId": "00D6D00000020HoUAI",
  "sfdcRecordId": "a0X6D000001CANCLA",
  "applicationRef": "APP-CANCEL-1",
  "correlationId": "CORR-CANCEL-1",
  "originalCorrelationId": "CORR-CANCEL-1",
  "payloadRef": null,
  "payloadContentType": "application/json",
  "occurredAt": "2026-07-03T10:15:30Z",
  "payload": {
    "invoiceNo": "INV-2002"
  }
}
```

**Drive to outcome.**
- **Full-stack (mandate cap + mocks running):** use `payload.invoiceNo` **not** containing `"MISSING"` (`INV-2002`). `enquire` → `found=true`; `cancel` runs; store status set `FAILURE`; result `{invoiceNo:"INV-2002", found:true, cancelled:true}`.
- **Engine-only manual mode:** after the engine dispatches `cap.mandate.request.v1` for `n_cancel`, PUBLISH to `cap.mandate.response.v1`:

```json
{
  "journeyInstanceId": "ji-CORR-CANCEL-1",
  "correlationId": "CORR-CANCEL-1",
  "nodeId": "n_cancel",
  "capabilityKey": "mandate",
  "status": "OK",
  "result": {
    "invoiceNo": "INV-2002",
    "found": true,
    "cancelled": true
  },
  "errorClass": null
}
```

**Expected result.** Branch takes the arm → terminal `n_done` (`status: completed`) → outcome **APPROVED**, emit `MandateCancelled`. Ops status = **`COMPLETED_APPROVED`**. Transitions: `n_cancel` COMPLETED → `n_done` COMPLETED. `JourneyDecision` on `orig.decision.v1` (key `APP-CANCEL-1`): `outcome:"APPROVED"`, `terminalNodeId:"n_done"`, `loanId:null`. Ops events: `run.started` → `node.dispatched`/`node.completed`(n_cancel) → `run.completed`. No DLQ.
**Verify:** `GET /ops/runs/search?key=CORR-CANCEL-1` → one `RunSummaryDto` with `status:"COMPLETED_APPROVED"`, `journeyKey:"emandate-cancel"`, `endedAt` set, `stuck:false`.

---

### Permutation 2 — NOT-FOUND → n_notFound → MandateNotFound (REJECTED / COMPLETED_DECLINED)

**Entry (Kafka).** Topic `orig.sfdc.pl.v1`, key = `NOTIF-CANCEL-1`, value identical to Permutation 1 **except** `payload.invoiceNo`:

```json
{
  "transactionId": "tx-cancel-2",
  "schemaVersion": "sfdc-ingress.v1",
  "source": "SFDC",
  "type": "EMANDATE_CANCEL",
  "notificationId": "NOTIF-CANCEL-1",
  "orgId": "00D6D00000020HoUAI",
  "sfdcRecordId": "a0X6D000001CANCLA",
  "applicationRef": "APP-CANCEL-1",
  "correlationId": "CORR-CANCEL-1",
  "originalCorrelationId": "CORR-CANCEL-1",
  "payloadRef": null,
  "payloadContentType": "application/json",
  "occurredAt": "2026-07-03T10:15:30Z",
  "payload": {
    "invoiceNo": "INV-MISSING-3003"
  }
}
```

**Drive to outcome.**
- **Full-stack:** `invoiceNo` containing `"MISSING"` (`INV-MISSING-3003`) → `enquire` → `found=false`; `cancel` NOT called; result `{invoiceNo:"INV-MISSING-3003", found:false, cancelled:false}`.
- **Engine-only manual mode:** PUBLISH to `cap.mandate.response.v1`:

```json
{
  "journeyInstanceId": "ji-CORR-CANCEL-1",
  "correlationId": "CORR-CANCEL-1",
  "nodeId": "n_cancel",
  "capabilityKey": "mandate",
  "status": "OK",
  "result": {
    "invoiceNo": "INV-MISSING-3003",
    "found": false,
    "cancelled": false
  },
  "errorClass": null
}
```

**Expected result.** `found != true` → branch `default` → terminal `n_notFound` (`status: rejected`) → outcome **REJECTED**, emit `MandateNotFound`. Ops status = **`COMPLETED_DECLINED`** (a clean business decline, not a failure). `JourneyDecision`: `outcome:"REJECTED"`, `terminalNodeId:"n_notFound"`, `loanId:null`. Ops events end with `run.completed` (outcome REJECTED). No DLQ.
**Verify:** `GET /ops/runs/search?key=CORR-CANCEL-1` → `status:"COMPLETED_DECLINED"`, `terminalOutcome:"REJECTED"` in `GET /ops/runs/{runId}`.

---

### Permutation 3 — n_cancel PERMANENT failure (missing invoiceNo → FAILED_SFDC_NOTIFIED)

`n_cancel` has **no** `retry`/`onFailure`/`optional` policy, so any `status:ERROR` fails the run on the first response (there is no compensation node in this DAG, so no saga).

**Entry (Kafka).** Topic `orig.sfdc.pl.v1`, key = `NOTIF-CANCEL-1`, value as Permutation 1 but with an **empty payload** (no `invoiceNo`):

```json
{
  "transactionId": "tx-cancel-3",
  "schemaVersion": "sfdc-ingress.v1",
  "source": "SFDC",
  "type": "EMANDATE_CANCEL",
  "notificationId": "NOTIF-CANCEL-1",
  "orgId": "00D6D00000020HoUAI",
  "sfdcRecordId": "a0X6D000001CANCLA",
  "applicationRef": "APP-CANCEL-1",
  "correlationId": "CORR-CANCEL-1",
  "originalCorrelationId": "CORR-CANCEL-1",
  "payloadRef": null,
  "payloadContentType": "application/json",
  "occurredAt": "2026-07-03T10:15:30Z",
  "payload": {}
}
```

**Drive to outcome.**
- **Full-stack:** omit `invoiceNo` (or set it blank). `MandateService.cancel` → `required(p,"invoiceNo")` throws `CapabilityException(PERMANENT, "invoiceNo is required")` → dispatcher emits `status:ERROR`, `errorClass:PERMANENT`.
- **Engine-only manual mode:** PUBLISH to `cap.mandate.response.v1`:

```json
{
  "journeyInstanceId": "ji-CORR-CANCEL-1",
  "correlationId": "CORR-CANCEL-1",
  "nodeId": "n_cancel",
  "capabilityKey": "mandate",
  "status": "ERROR",
  "result": {},
  "errorClass": "PERMANENT"
}
```

**Expected result.** No retrySpec → node fails for good → run fails with an ERROR decision; the channel IS notified (`JourneyDecision` published then `markSfdcNotified`). Ops status = **`FAILED_SFDC_NOTIFIED`** (if the notify publish confirms). `JourneyDecision`: `outcome:"ERROR"`, `terminalNodeId:"n_cancel"`, `loanId:null`. Ops event `node.failed`(n_cancel) → `run.failed`. `RunDetailDto`: `sfdcNotified:"SENT"`, `terminalOutcome:"ERROR"`, `dlqTopicRef:"orig.sfdc.dlq.v1"`, `nodeStats:[{nodeId:"n_cancel", attempts:1, failureClass:"PERMANENT"}]`.
> If the notify could not be confirmed the run would surface as **`FAILED_NOTIFY_PENDING`** (`sfdcNotified:"PENDING"`) until re-notified — same failing node, different notify state.
**Verify:** `GET /ops/runs/search?key=CORR-CANCEL-1` → `status:"FAILED_SFDC_NOTIFIED"`; detail shows `nodeStats` failureClass `PERMANENT`.

---

### Permutation 4 — n_cancel TRANSIENT failure (engine-only; still fails, no retry lane)

Because `n_cancel` declares **no `retrySpec`**, a `TRANSIENT` error is NOT retried — it fails identically to PERMANENT. (Full-stack cannot naturally produce TRANSIENT here: `MandateService` collapses to PERMANENT. This arm is exercisable only by hand-crafting the response.)

**Entry (Kafka).** Same start envelope as Permutation 1 (`invoiceNo:"INV-2002"`).

**Drive to outcome (engine-only manual mode only).** PUBLISH to `cap.mandate.response.v1`:

```json
{
  "journeyInstanceId": "ji-CORR-CANCEL-1",
  "correlationId": "CORR-CANCEL-1",
  "nodeId": "n_cancel",
  "capabilityKey": "mandate",
  "status": "ERROR",
  "result": {},
  "errorClass": "TRANSIENT"
}
```

**Expected result.** No `retryOn` set contains `TRANSIENT` (there is no retrySpec at all) → node fails on first ERROR → ERROR decision, channel notified → ops status **`FAILED_SFDC_NOTIFIED`**. `nodeStats` failureClass `TRANSIENT`, `terminalNodeId:"n_cancel"`, `terminalOutcome:"ERROR"`, `dlqTopicRef:"orig.sfdc.dlq.v1"`.
**Verify:** `GET /ops/runs/{runId}` → `nodeStats:[{nodeId:"n_cancel", attempts:1, failureClass:"TRANSIENT"}]`.

---

### Permutation 5 — n_cancel AMBIGUOUS / null-errorClass failure (engine-only; fails)

Missing/null `errorClass` on an ERROR response is treated as **AMBIGUOUS** by the engine (`failureClassOf`). No retrySpec → still fails.

**Entry (Kafka).** Same start envelope as Permutation 1.

**Drive to outcome (engine-only manual mode).** PUBLISH either of these to `cap.mandate.response.v1` (explicit AMBIGUOUS, or null which the engine coerces to AMBIGUOUS):

```json
{
  "journeyInstanceId": "ji-CORR-CANCEL-1",
  "correlationId": "CORR-CANCEL-1",
  "nodeId": "n_cancel",
  "capabilityKey": "mandate",
  "status": "ERROR",
  "result": {},
  "errorClass": "AMBIGUOUS"
}
```

**Expected result.** Node fails (AMBIGUOUS not retryable without a matching retrySpec) → ERROR decision, notified → ops status **`FAILED_SFDC_NOTIFIED`**, `nodeStats` failureClass `AMBIGUOUS`, `terminalNodeId:"n_cancel"`.
**Verify:** `GET /ops/runs/{runId}` → `terminalOutcome:"ERROR"`, `nodeStats[0].failureClass:"AMBIGUOUS"`.

---

### Permutation 6 — BREAKER_OPEN (NOT REACHABLE in this journey)

`BREAKER_OPEN` is a `nodeStats.failureClass` enum name but is **not** a valid `CapabilityResponse.errorClass` (that enum is only `TRANSIENT|PERMANENT|AMBIGUOUS`) and `n_cancel` declares **no circuit-breaker policy**. There is no lever — full-stack or engine-only — to drive a `BREAKER_OPEN` failure in `emandate-cancel`. Document as **not applicable / unreachable** for this journey. (A crafted `"errorClass":"BREAKER_OPEN"` on the response would fail deserialization of the `ErrorClass` enum.)

---

### Permutation 7 — Idempotency / duplicate origination resend (same id → dropped)

**Entry (Kafka).** After Permutation 1 (or 2) has started, re-publish the **exact same** origination envelope again to `orig.sfdc.pl.v1` (same `correlationId:"CORR-CANCEL-1"` → same `instanceId:"ji-CORR-CANCEL-1"`).

**Drive to outcome.** No steering needed — the duplicate is gated at start.

**Expected result.** `store.insertIfAbsent(instance)` loses → the redelivery is logged `journey.start.duplicate` and **dropped** (it does NOT resume or re-run, even if the winner is still mid-flight; the liveness sweeper is the net for a stuck winner). No second run, no second decision.
**Verify:** `GET /ops/runs/search?key=CORR-CANCEL-1` returns **exactly one** run (not two). Re-sending after the first run reached terminal likewise yields no new run.

---

### Permutation 8 — Duplicate / late capability response (same instanceId+nodeId → dropped)

**Entry.** A run at (or past) `n_cancel`. In engine-only mode, PUBLISH the Permutation-1 `cap.mandate.response.v1` message **twice** (identical `journeyInstanceId:"ji-CORR-CANCEL-1"`, `nodeId:"n_cancel"`).

**Expected result.** First response advances the run; the second is dropped by the duplicate/late guard — `n_cancel` is already COMPLETED (or the run is terminal/non-live), so the engine ignores it (it only re-drives still-pending publishes). Each `(journeyInstanceId, nodeId)` is effectively consumed once. Outcome unchanged from Permutation 1; no duplicate transition, no duplicate decision.
**Verify:** `GET /ops/runs/{runId}` transitions list contains a single `n_cancel` COMPLETED entry.

---

### Permutation 9 — Unknown-type fail-closed (routing → poison DLQ)

**Entry (Kafka).** Topic `orig.sfdc.pl.v1`, key = `NOTIF-CANCEL-1`, an envelope whose `type` has **no** `type-to-journey` row (e.g. the `EMANDATE_CANCEL` row was never added, or a typo `EMANDATE_CANCELL`):

```json
{
  "transactionId": "tx-cancel-9",
  "schemaVersion": "sfdc-ingress.v1",
  "source": "SFDC",
  "type": "EMANDATE_CANCELL",
  "notificationId": "NOTIF-CANCEL-1",
  "orgId": "00D6D00000020HoUAI",
  "sfdcRecordId": "a0X6D000001CANCLA",
  "applicationRef": "APP-CANCEL-1",
  "correlationId": "CORR-CANCEL-1",
  "originalCorrelationId": "CORR-CANCEL-1",
  "payloadRef": null,
  "payloadContentType": "application/json",
  "occurredAt": "2026-07-03T10:15:30Z",
  "payload": { "invoiceNo": "INV-2002" }
}
```

**Expected result.** `registry.resolveForType(type)` finds no mapping → `UnroutableTypeException` → the message is treated as **poison** and dead-lettered to the origination topic's DLQ **`orig.sfdc.pl.v1.dlq`** (`<sourceTopic>.dlq`). **No run is started** — routing is fail-closed; there is never a default journey.
**Verify:** consume `orig.sfdc.pl.v1.dlq` in Kafka UI to see the poisoned envelope; `GET /ops/runs/search?key=CORR-CANCEL-1` returns **no run**.

---

### Permutation 10 — Unknown/absent branch value fail-closed (found != true → n_notFound)

The branch has only the `found == true` arm and a `default`. There is no explicit `false` arm — **any** non-`true` value (missing `found`, `null`, `"false"`, a typo, wrong type) fails closed to `n_notFound` (REJECTED / declined), never an error.

**Entry (Kafka).** Same start envelope as Permutation 1.

**Drive to outcome (engine-only manual mode).** PUBLISH an OK response whose `result` **omits** `found` (or sets a non-`true` value):

```json
{
  "journeyInstanceId": "ji-CORR-CANCEL-1",
  "correlationId": "CORR-CANCEL-1",
  "nodeId": "n_cancel",
  "capabilityKey": "mandate",
  "status": "OK",
  "result": {
    "invoiceNo": "INV-2002",
    "cancelled": false
  },
  "errorClass": null
}
```

**Expected result.** `context.cancel.found` resolves to null/missing → stringifies to `""` → `"" == "true"` is false → `default` → terminal `n_notFound` → **REJECTED** / ops **`COMPLETED_DECLINED`**, emit `MandateNotFound`. Same terminal as Permutation 2 (fail-closed to "not found", not to an error).
**Verify:** `GET /ops/runs/search?key=CORR-CANCEL-1` → `status:"COMPLETED_DECLINED"`, `terminalNodeId:"n_notFound"`.

---

### Permutation 11 — Stuck run → liveness sweeper force-fail (RUN_SWEPT_TIMEOUT)

**Entry (Kafka).** Start a run exactly as Permutation 1, then **never deliver the `n_cancel` response**.
- **Engine-only mode:** simply do NOT publish any `cap.mandate.response.v1` — the run parks at `n_cancel`.
- **Full-stack mode:** the mandate capability WILL respond, so to reproduce you must stop/disconnect the `mandate` capability (or its `cap-mandate` consumer) so `n_cancel` gets no response.

**Expected result.** Run stays `RUNNING` at `n_cancel`. Timeline (defaults: run-budget 900s, sweep-interval 60s):
- **~840s** (`startedAt <= now − (900 − 60)`): `OpsRunQueryService.isStuck()` flags it — surfaced only via `stuckOnly`/`stuckCount`/`sweepDeadline`. Store state still `RUNNING` → ops status **`RUNNING`**, `stuck:true`, `sweepDeadline = startedAt + 900s`.
- **900s**: `JourneyLivenessSweeper` force-fails it — publishes ERROR `JourneyDecision` (`outcome:"ERROR"`, `terminalNodeId:"__timeout__"`, `loanId:null`) FIRST, `markSfdcNotified()`, `fail("__timeout__", ERROR)` → state `FAILED`, emits ops event `run.sweptTimeout` (`RUN_SWEPT_TIMEOUT`). Resulting ops status = **`FAILED_SFDC_NOTIFIED`** (`sfdcNotified:"SENT"`, `terminalNodeId:"__timeout__"`).
**Verify (stuck window):** `GET /ops/runs?status=RUNNING&stuckOnly=true` lists the run (`stuck:true`, `sweepDeadline` populated). **Verify (post-sweep):** `GET /ops/runs/search?key=CORR-CANCEL-1` → `status:"FAILED_SFDC_NOTIFIED"`; `GET /ops/runs/{runId}` → `terminalNodeId:"__timeout__"`, `terminalOutcome:"ERROR"`.

---

### Maker-checker: publishing the `emandate-cancel` journey (registry mode)

Only needed when using `journey-source=registry` (Prerequisite step 1, registry option). Registry base `http://localhost:8104/api/v1`; every call needs `X-Registry-Token: dev-registry-token`; write/lifecycle ops also need `X-User-Id`.

**Happy lifecycle** (create → draft → save → submit → approve = publish):

```bash
REG=http://localhost:8104/api/v1
TOK='X-Registry-Token: dev-registry-token'

# 1. create journey (maker) -> 201
curl -i -X POST $REG/journeys -H "$TOK" -H 'X-User-Id: maker@bank' -H 'Content-Type: application/json' \
  -d '{"key":"emandate-cancel","name":"E-Mandate Cancel","businessLine":"RETAIL","product":"MANDATE","partner":null}'

# 2. create draft v1 (maker) -> 201 {status:"draft"}  (config = the emandate-cancel.journey.json object)
curl -i -X POST $REG/journeys/emandate-cancel/versions -H "$TOK" -H 'X-User-Id: maker@bank' -H 'Content-Type: application/json' \
  -d '{"config":{"nodes":[],"edges":[]},"note":"first cut"}'

# 3. save draft (maker) -> 200   (paste the real journey graph into config)
curl -i -X PUT $REG/journeys/emandate-cancel/versions/1 -H "$TOK" -H 'X-User-Id: maker@bank' -H 'Content-Type: application/json' \
  -d '{"config":{"nodes":[{"id":"n_cancel"}],"edges":[]},"note":"wip"}'

# 4. submit (maker) DRAFT->PENDING_APPROVAL -> 200 {status:"pendingApproval"}
curl -i -X POST $REG/journeys/emandate-cancel/versions/1/submit -H "$TOK" -H 'X-User-Id: maker@bank'

# 5. approve = PUBLISH (checker, DIFFERENT actor) -> 200 {status:"published", approverId:"checker@bank"}
curl -i -X POST $REG/journeys/emandate-cancel/versions/1/approve -H "$TOK" -H 'X-User-Id: checker@bank'
```
Approve is the publish — there is no separate `/publish`. After it, `GET /published-journeys/emandate-cancel/versions/1` (no actor) returns the `PublishedConfigDto` the engine bootstraps from.

**403 self-approve** (`error:"FORBIDDEN"`): the maker who authored v1 tries to approve or reject their own version.

```bash
curl -i -X POST $REG/journeys/emandate-cancel/versions/1/approve -H "$TOK" -H 'X-User-Id: maker@bank'
# -> 403 {"error":"FORBIDDEN","message":"maker-checker: author 'maker@bank' may not approve/reject their own version","issues":[]}
```

**409 conflicts** (`error:"CONFLICT"`):
- **Second draft while v1 editable** — `POST /journeys/emandate-cancel/versions` while v1 is DRAFT or PENDING_APPROVAL → `"journey 'emandate-cancel' already has an editable version (v1)"`.
- **Duplicate journey** — `POST /journeys` with `key:"emandate-cancel"` again → `"journey 'emandate-cancel' already exists"`.
- **Lifecycle/immutability** — `PUT .../versions/1` (saveDraft) once v1 is not DRAFT → `"version 1 of 'emandate-cancel' is <STATUS> — published/rejected/pending versions are immutable"`; `submit` on non-DRAFT → `"only a DRAFT can be submitted (...)"`; `approve`/`reject` on non-PENDING → `"only a PENDING_APPROVAL version can be approved/rejected (...)"`; concurrent approve+reject loser → `"version 1 of 'emandate-cancel' was already finalized by another checker"`.

```bash
curl -i -X POST $REG/journeys/emandate-cancel/versions -H "$TOK" -H 'X-User-Id: maker@bank' -H 'Content-Type: application/json' -d '{"config":{}}'
# -> 409 {"error":"CONFLICT","message":"journey 'emandate-cancel' already has an editable version (v1)","issues":[]}
```

**422 validation** (`error:"VALIDATION_FAILED"`):
- **Bad key on create** — a key that is not lowercase kebab-case (e.g. `Emandate_Cancel`) → `"journey key must be lowercase kebab-case ([a-z0-9-]), e.g. 'loan-origination'"`, `issues:[]`. (`emandate-cancel` itself is valid.)
- **Submit fails the graph gate** — submitting a config with any `severity:"error"` issue → `"journey 'emandate-cancel' v1 fails validation"` with a **populated `issues[]`** (e.g. empty DAG → `{code:"emptyDag",severity:"error",...}`).

```bash
curl -i -X POST $REG/journeys -H "$TOK" -H 'X-User-Id: maker@bank' -H 'Content-Type: application/json' \
  -d '{"key":"Emandate_Cancel","name":"x","businessLine":"RETAIL","product":"MANDATE","partner":null}'
# -> 422 {"error":"VALIDATION_FAILED","message":"journey key must be lowercase kebab-case ([a-z0-9-]), e.g. 'loan-origination'","issues":[]}
```

---

### Ops verification (ops-query, GET-only)

Base `http://localhost:8082/ops`; every call needs `X-Ops-Token: dev-ops-token` **and** `X-User-Id` (any non-blank). The ops token is a distinct secret from the registry token.

```bash
OPS=http://localhost:8082/ops
H='-H X-Ops-Token:dev-ops-token -H X-User-Id:ops.analyst@bank'
```

| Ops case | Call | Expected for emandate-cancel runs |
|---|---|---|
| **List (defaults)** | `curl -s $H "$OPS/runs"` | `PageDto` (page 0, size 50, `startedAt` DESC) including this journey's runs. |
| **Filter: status** | `curl -s $H "$OPS/runs?status=COMPLETED_APPROVED"` | Only found/approved cancels (P1). Try each: `COMPLETED_DECLINED` (P2/P10), `FAILED_SFDC_NOTIFIED` (P3–P5, P11), `RUNNING` (P11 pre-sweep), `FAILED_NOTIFY_PENDING`. |
| **Filter: journeyKey** | `curl -s $H "$OPS/runs?journeyKey=emandate-cancel"` | Exact-match, only `emandate-cancel` runs. |
| **Filter: time window** | `curl -s $H "$OPS/runs?since=2026-07-03T00:00:00Z&until=2026-07-04T00:00:00Z"` | Runs with `startedAt` in `[since, until]`. Bad ISO instant → 400. |
| **Filter: stuckOnly** | `curl -s $H "$OPS/runs?status=RUNNING&stuckOnly=true"` | The P11 run once past ~840s (`stuck:true`, `sweepDeadline` set); empty otherwise. |
| **Pagination** | `curl -s $H "$OPS/runs?page=0&size=25"` | `size` clamped 1..200; `page` negatives floored to 0. |
| **Bad status** | `curl -i $H "$OPS/runs?status=BOGUS"` | **400** `{"error":"BAD_REQUEST","message":"unknown status 'BOGUS' (...)"}`. |
| **Exact search** | `curl -s $H "$OPS/runs/search?key=CORR-CANCEL-1"` | `List<RunSummaryDto>` newest-first; matches on `runId` \| `correlationId` \| `notificationId` \| `sfdcRecordId`. Also works with `key=NOTIF-CANCEL-1` or `key=a0X6D000001CANCLA`. Blank `key` → **400**. |
| **Detail** | `curl -s $H "$OPS/runs/{runId}"` | `RunDetailDto`: `status`, `sfdcNotified` (NONE\|PENDING\|SENT), `terminalNodeId` (`n_done`/`n_notFound`/`n_cancel`/`__timeout__`), `terminalOutcome` (APPROVED/REJECTED/ERROR), `transitions[]`, `nodeStats[]` (n_cancel `failureClass` on the failure permutations), `dlqTopicRef` (`orig.sfdc.dlq.v1` only on FAILED_*). Unknown runId → **404** empty body. |
| **Auth failures** | `curl -i "$OPS/runs" -H 'X-User-Id:ops.analyst@bank'` | **401** `invalid or missing X-Ops-Token`; missing `X-User-Id` → **401** `X-User-Id header is required for the ops API`. |

**Cross-check summary (this journey's outcome vocabulary):** found → `COMPLETED_APPROVED` (P1); not-found / non-true → `COMPLETED_DECLINED` (P2, P10); any `n_cancel` ERROR or sweeper timeout → `FAILED_SFDC_NOTIFIED` (P3–P5, P11), degrading to `FAILED_NOTIFY_PENDING` only if notify is unconfirmed; unroutable type → no run + `orig.sfdc.pl.v1.dlq` (P9); duplicate start/response → single run, no duplication (P7, P8).


---

<a id="sec-device-validation"></a>

---

## Journey: device-validation (brand-as-config, real HTTP -> WireMock)

**Two entry doors, ONE journey.** The REAL production front door is an **SFDC Outbound Messaging SOAP call** — `SVCNAME__c = Post_Disbursal_Apple` (Apple post-disbursal device validation) — which the SFDC ingress edge normalizes and routes here; the payload has **no brand field** (brand=**APPLE** is implicit in the svcName) and the device id is `imei` (see *Permutation 0* below + `docs/DEVICE_VALIDATION_SFDC_ENTRY.md`). The **demo Kafka door** (`type:"DEVICE_VALIDATION"`, `payload.brand`/`payload.imei`/`payload.serial`) is the secondary, journey-only path used by `demo/run-demo1.sh` for the four-outcome set (Permutations 1+).

Either way it is ONE journey that runs up to **three config-gated activities — `validate`, `block`, `unblock`** — where **every per-brand difference is a config row, not code**: which activities the brand supports, how the device is identified (`validate-by: imei | serial`), the auth scheme (OAUTH / BASIC / NA), and the "valid" pass-path/pass-value all come from `device-validation.brands.*`. Each activity runs only on the **INTERSECTION** of (the request asks for it, via its `status`) AND (the brand supports it, via its flag). The capability (`device-validation`, ops `decideActivities` / `validate` / `block` / `unblock`) makes a **real HTTP POST** to the WireMock vendor at `http://localhost:19106/vendor/device-validation/{validate|block|unblock}`; only the response DATA is mocked.

**Request `status` selects activities** (config `device-validation.status-activities`): `status "1"` = **validate + block** (disbursal); `status "2"` = **unblock** (closure). An absent/blank status **defaults to "1"**. `decideActivities` then intersects the status-selected set with the brand's per-activity flags to produce the run plan (`runValidate` / `runBlock` / `runUnblock`).

### Pinned journey facts (device-validation v1, classpath-loaded on the local profile)

Nodes (start `n_decide`):

| id | type | op / condition | output | true-arm → | default → |
|---|---|---|---|---|---|
| `n_decide` | task | `decideActivities`, input `{ brand, type, status }` | `context.plan` = `{brand, validateBy, authType, runValidate, runBlock, runUnblock}` | — | `n_gate_validate` |
| `n_gate_validate` | branch | `context.plan.runValidate == true` | — | `n_validate` | `n_gate_block` |
| `n_validate` | task | `validate`, input `{ brand, type, deviceId, imei, serial }` | `context.validateResult` = `{brand, valid, authType, vendor}` | — | `n_after_validate` |
| `n_after_validate` | branch | `context.validateResult.valid == true` | — | `n_gate_block` | `n_invalid` |
| `n_gate_block` | branch | `context.plan.runBlock == true` | — | `n_block` | `n_gate_unblock` |
| `n_block` | task | `block`, input `{ brand, type, deviceId, imei, serial }` | `context.blockResult` = `{brand, valid, authType, vendor}` | — | `n_after_block` |
| `n_after_block` | branch | `context.blockResult.valid == true` | — | `n_gate_unblock` | `n_invalid` |
| `n_gate_unblock` | branch | `context.plan.runUnblock == true` | — | `n_unblock` | `n_valid` |
| `n_unblock` | task | `unblock`, input `{ brand, type, deviceId, imei, serial }` | `context.unblockResult` = `{brand, valid, authType, vendor}` | — | `n_after_unblock` |
| `n_after_unblock` | branch | `context.unblockResult.valid == true` | — | `n_valid` | `n_invalid` |
| `n_valid` | terminal | `status:"completed"`, `emit:["DeviceValidationValid"]` | → outcome **APPROVED** | — | — |
| `n_invalid` | terminal | `status:"rejected"`, `emit:["DeviceValidationInvalid"]` | → outcome **REJECTED** | — | — |

Key derived facts (all confirmed in code):
- `==` is a **string compare**; the booleans stringify (`true`→`"true"`). Branch drivers: `plan.runValidate`, `validateResult.valid`, `plan.runBlock`, `blockResult.valid`, `plan.runUnblock`, `unblockResult.valid`.
- **Each `n_gate_*` skips its activity when the matching `run*` flag is false** (its default arm hops straight to the next gate). Any activity that **runs** and returns `valid:false` routes to `n_invalid`. Reaching the end with every run-activity valid (or with **nothing to run**) routes to `n_valid`.
- The run plan is the **intersection** of status and brand flags: a `status "1"` request runs `validate`/`block` **only for the brands whose `validate`/`block` flag is true**; a `status "2"` request runs `unblock` only if the brand's `unblock` flag is true. A brand with a false flag simply skips that activity's gate.
- **Brand + device-id resolution:** the capability reads `payload.brand` if present (demo Kafka door); otherwise it derives the brand from the svcName (`type`) via the brand row's `svc-name` (real SFDC door — e.g. `Post_Disbursal_Apple → APPLE`). The device id comes from the field named by the brand's **`validate-by`**: an `imei` brand reads `payload.imei`, a `serial` brand reads `payload.serial`; a generic `payload.deviceId` still works as a **fallback** on the Kafka door. Neither a brand nor a device id → PERMANENT (fail closed, "missing brand").
- **No node has a `retrySpec`, `onFailure`, or breaker policy.** Any capability `status:ERROR` fails the node on the first response and fails the run — TRANSIENT and AMBIGUOUS are **not retried** here (they die exactly like PERMANENT); only the recorded `failureClass` differs. There is **no compensation** (no completed compensable node). **`BREAKER_OPEN` is unreachable** in this journey (no circuit-breaker configured, and `ErrorClass` only has TRANSIENT/PERMANENT/AMBIGUOUS — see the N/A permutation below).
- HTTP→ErrorClass map (`DeviceValidationVendorClient`): **4xx → PERMANENT**, **5xx → TRANSIENT**, **read-timeout(>10000ms) → AMBIGUOUS**, **connect/IO refused → TRANSIENT**, empty body → PERMANENT, unknown/missing brand → PERMANENT (fail-closed).

### Config levers (local profile, `application-local.yml`)

| Brand | `auth-type` | `validate` | `block` | `unblock` | `validate-by` | `pass-path` | `pass-value` | vendor pass body |
|---|---|---|---|---|---|---|---|---|
| SAMSUNG | OAUTH | true | true | true | `imei` | `respCode` | `"0"` | `{"respCode":"0"}` |
| GODREJ | NA | false | true | false | `serial` | `status` | `"OK"` | `{"status":"OK"}` |
| BOSCH | BAUTH | true | true | true | `serial` | `result.code` | `"S"` | `{"result":{"code":"S"}}` |
| HISENSE | OAUTH | false | true | false | `imei` | `responseStatus` | `"-4"` | `{"responseStatus":"-4"}` (added at runtime, no rebuild) |
| APPLE | OAUTH | false | true | false | `imei` | `respCode` | `"0"` | `{"respCode":"0"}` (real SFDC door; row also declares `svc-name: Post_Disbursal_Apple`) |

deviceId levers (WireMock, `POST /vendor/device-validation/{validate|block|unblock}` — the same vendor call backs all three activities):
- **`DEV-FAIL`** → `00-fail.json` (priority 1) returns **HTTP 422** for ANY brand → PERMANENT → FAILED.
- **`DEV-DECLINE`** → per-brand decline body at **HTTP 200** (`{"respCode":"1"}` / `{"status":"DECLINED"}` / `{"result":{"code":"F"}}`) → `valid=false` → business **invalid** (teal, `COMPLETED_DECLINED`).
- any other device id → per-brand pass body at HTTP 200 → `valid=true`.

### Entry — two doors

**Real SFDC SOAP door (production — Permutation 0):** an SFDC Outbound Message `POST /api/v1/sfdc/outbound-messages` (edge `:8080`, header `X-Auth-Token: dev-token`) with `SVCNAME__c = Post_Disbursal_Apple`. The edge normalizes it (svcName → `type`, `Request__c` CDATA → inline `payload`) and publishes to `orig.device-validation.v1`; the engine's `type-to-journey.Post_Disbursal_Apple` routes it here. The payload has **no brand field** (brand=APPLE from the svcName) and carries `imei` + `paymentInfo`. The correlationId is **edge-generated** (NOT the `correlationid` header), so the run's ops search key is its **`notificationId`** (`Notification/Id`) or **`sfdcRecordId`** (`sf1:Id`). Full curl + reference envelope: `docs/DEVICE_VALIDATION_SFDC_ENTRY.md`; fixture `full-flow-it/src/test/resources/sfdc-outbound-apple-postdisbursal.xml`.

**Demo Kafka door (secondary — Permutations 1+):** produce to topic **`orig.device-validation.v1`**, **key = `correlationId`**, value = the CanonicalEnvelope with `type:"DEVICE_VALIDATION"`, `payload.brand`, `payload.status` (`"1"`/`"2"`, defaults to `"1"`), and the device id in `payload.imei`/`payload.serial` per the brand's `validate-by` (generic `payload.deviceId` works as a fallback) — the business fields the capability reads on this door. Engine instance id `"ji-" + correlationId` (correlationId is the first non-null dedup key). Ops search keys = `correlationId` / `notificationId` / `sfdcRecordId`.

Full-stack prereq: engine started via `./run-services.sh` (or `bootRun --spring.profiles.active=local --idfc.engine.journey-source=classpath`), the `device-validation` app, and the WireMock mock-vendors server (`:19106`) all running (or just run `demo/run-demo1.sh` for the canned four-outcome set). Engine-only manual prereq: engine running; you hand-publish `cap.device-validation.response.v1` messages to steer each hop.

---

### Permutation 0 — REAL SFDC SOAP entry (Post_Disbursal_Apple → VALID / APPROVED)

The production path. `SVCNAME__c = Post_Disbursal_Apple`, **no brand in the body** (brand=APPLE derived from the svcName), device id = `imei`, status defaults to `"1"`. APPLE supports **block only** (`validate:false`, `block:true`, `unblock:false`), so at status "1" the plan is `runBlock:true` (others false) — a single `n_block` confirmation call, no `n_validate` (structurally like GODREJ).

**Entry** — SOAP POST to the LOCAL edge (`docs/DEVICE_VALIDATION_SFDC_ENTRY.md` has the full envelope inline):
```bash
curl -sS -X POST http://localhost:8080/api/v1/sfdc/outbound-messages \
  -H "X-Auth-Token: dev-token" -H "Content-Type: text/xml" \
  --data-binary @full-flow-it/src/test/resources/sfdc-outbound-apple-postdisbursal.xml
# expected: 200  <Ack>true</Ack>
```

**Drive to outcome**
- **Full-stack:** `apple-pass.json` matches `brand=APPLE` and returns `{"respCode":"0"}` → `valid=true`. (Apple is OAUTH → the client fetches a bearer token from `oauth-token.json` first.)
- **Engine-only:** the instanceId is **edge-generated** — read it from the engine `journey.start instanceId=...` log or `GET /ops/runs/search?key=<notificationId>`. Then publish to `cap.device-validation.response.v1`: `n_decide` result `{"brand":"APPLE","validateBy":"imei","authType":"OAUTH","runValidate":false,"runBlock":true,"runUnblock":false}`, then `n_block` result `{"brand":"APPLE","valid":true,"authType":"OAUTH","vendor":{"respCode":"0"}}`.

**Expected result** — ops status **`COMPLETED_APPROVED`**, terminalNodeId `n_valid`, transitions `n_decide → n_block` (no `n_validate`/`n_unblock`). Emit `DeviceValidationValid`. **Verify:** `GET /ops/runs/search?key=04l7200000Daq5RAbR` (the `notificationId`) — one run, `journeyKey:"device-validation"`. Invalid/technical-fail variants behave exactly as the invalid/fail permutations below (a non-pass vendor body → `COMPLETED_DECLINED` at `n_invalid`; a 4xx/5xx/timeout → `FAILED_*` at `n_block`, failureClass PERMANENT/TRANSIENT/AMBIGUOUS).

---

### Permutation 1 — VALID, validate+block brand (SAMSUNG, status "1", validate + block both pass)

**Entry** — (Kafka) topic `orig.device-validation.v1`, key `corr-df-approve-samsung`:

```json
{
  "transactionId": "corr-df-approve-samsung-t",
  "schemaVersion": "demo.v1",
  "source": "FILE_DEMO",
  "type": "DEVICE_VALIDATION",
  "notificationId": "corr-df-approve-samsung-n",
  "orgId": "DEMO-ORG",
  "sfdcRecordId": "DEV-1",
  "applicationRef": "corr-df-approve-samsung-app",
  "correlationId": "corr-df-approve-samsung",
  "originalCorrelationId": "corr-df-approve-samsung",
  "payloadContentType": "application/json",
  "occurredAt": "2026-07-03T10:00:00Z",
  "payload": { "brand": "SAMSUNG", "status": "1", "imei": "DEV-1" }
}
```

**Drive to outcome**
- **Full-stack:** brand `SAMSUNG` (OAUTH, `validate:true`/`block:true`/`unblock:true`, `validate-by:imei`, passPath `respCode`, passValue `"0"`) at `status "1"` → plan `runValidate:true, runBlock:true, runUnblock:false`. Any device id that is not `DEV-FAIL`/`DEV-DECLINE` (here `imei=DEV-1`). Client fetches a bearer token from `oauth-token.json`, then `samsung-pass.json` returns `{"respCode":"0"}` for both `validate` and `block` → `valid=true` at both.
- **Engine-only manual mode** (instance `ji-corr-df-approve-samsung`), publish to `cap.device-validation.response.v1` in this order:

  n_decide:
  ```json
  { "journeyInstanceId": "ji-corr-df-approve-samsung", "correlationId": "corr-df-approve-samsung", "nodeId": "n_decide", "capabilityKey": "device-validation", "status": "OK", "result": { "brand": "SAMSUNG", "validateBy": "imei", "authType": "OAUTH", "runValidate": true, "runBlock": true, "runUnblock": false }, "errorClass": null }
  ```
  n_validate:
  ```json
  { "journeyInstanceId": "ji-corr-df-approve-samsung", "correlationId": "corr-df-approve-samsung", "nodeId": "n_validate", "capabilityKey": "device-validation", "status": "OK", "result": { "brand": "SAMSUNG", "valid": true, "authType": "OAUTH", "vendor": { "respCode": "0" } }, "errorClass": null }
  ```
  n_block:
  ```json
  { "journeyInstanceId": "ji-corr-df-approve-samsung", "correlationId": "corr-df-approve-samsung", "nodeId": "n_block", "capabilityKey": "device-validation", "status": "OK", "result": { "brand": "SAMSUNG", "valid": true, "authType": "OAUTH", "vendor": { "respCode": "0" } }, "errorClass": null }
  ```

**Expected result** — ops status **`COMPLETED_APPROVED`**; terminal node **`n_valid`**, terminalOutcome **APPROVED**. Transitions: `n_decide`→`n_validate`→`n_block` all `COMPLETED` (branch nodes `n_gate_validate`/`n_after_validate`/`n_gate_block`/`n_after_block`/`n_gate_unblock` evaluated inline; `runUnblock:false` so `n_gate_unblock` hops straight to `n_valid`). Decision on `orig.decision.v1` (key = applicationRef `corr-df-approve-samsung-app`): `outcome:"APPROVED"`, `terminalNodeId:"n_valid"`, `loanId:null`, `emitted:["DeviceValidationValid"]`. No DLQ. **Verify:** `GET /ops/runs/search?key=corr-df-approve-samsung` → one summary, `status:"COMPLETED_APPROVED"`, then `GET /ops/runs/{runId}` → `terminalNodeId:"n_valid"`, `terminalOutcome:"APPROVED"`, `sfdcNotified:"SENT"`, `dlqTopicRef:null`.

### Permutation 2 — VALID, block-only brand (GODREJ, status "1", validate skipped)

**Entry** — topic `orig.device-validation.v1`, key `corr-df-approve-godrej`:

```json
{
  "transactionId": "corr-df-approve-godrej-t",
  "schemaVersion": "demo.v1",
  "source": "FILE_DEMO",
  "type": "DEVICE_VALIDATION",
  "notificationId": "corr-df-approve-godrej-n",
  "orgId": "DEMO-ORG",
  "sfdcRecordId": "DEV-2",
  "applicationRef": "corr-df-approve-godrej-app",
  "correlationId": "corr-df-approve-godrej",
  "originalCorrelationId": "corr-df-approve-godrej",
  "payloadContentType": "application/json",
  "occurredAt": "2026-07-03T10:00:00Z",
  "payload": { "brand": "GODREJ", "status": "1", "serial": "DEV-2" }
}
```

**Drive to outcome**
- **Full-stack:** brand `GODREJ` (`authType=NA` → no `Authorization` header; `validate:false`/`block:true`/`unblock:false`, `validate-by:serial`). At `status "1"` the plan is `runValidate:false, runBlock:true, runUnblock:false`, so `n_gate_validate` takes the **default** arm → `n_gate_block` → `n_block` (no `n_validate`). `godrej-pass.json` returns `{"status":"OK"}`, passPath `status` == `"OK"` → `valid=true`.
- **Engine-only** (instance `ji-corr-df-approve-godrej`):

  n_decide (`runValidate:false` → routes past validate straight to the block gate):
  ```json
  { "journeyInstanceId": "ji-corr-df-approve-godrej", "correlationId": "corr-df-approve-godrej", "nodeId": "n_decide", "capabilityKey": "device-validation", "status": "OK", "result": { "brand": "GODREJ", "validateBy": "serial", "authType": "NA", "runValidate": false, "runBlock": true, "runUnblock": false }, "errorClass": null }
  ```
  n_block:
  ```json
  { "journeyInstanceId": "ji-corr-df-approve-godrej", "correlationId": "corr-df-approve-godrej", "nodeId": "n_block", "capabilityKey": "device-validation", "status": "OK", "result": { "brand": "GODREJ", "valid": true, "authType": "NA", "vendor": { "status": "OK" } }, "errorClass": null }
  ```
  (Do **not** publish an `n_validate` or `n_unblock` response — neither is dispatched.)

**Expected result** — ops status **`COMPLETED_APPROVED`**; terminal `n_valid`, APPROVED, `emitted:["DeviceValidationValid"]`. Transitions: `n_decide`→`n_block` COMPLETED (no `n_validate`/`n_unblock` transition — proving the block-only path). **Verify:** `GET /ops/runs/search?key=DEV-2` (sfdcRecordId works as a search key) → `status:"COMPLETED_APPROVED"`; detail shows only `n_decide` and `n_block` in `transitions`.

### Permutation 3 — VALID, brand added at runtime (HISENSE, block-only, brand-as-config proof)

**Entry** — topic `orig.device-validation.v1`, key `corr-df-approve-hisense`:

```json
{
  "transactionId": "corr-df-approve-hisense-t",
  "schemaVersion": "demo.v1",
  "source": "FILE_DEMO",
  "type": "DEVICE_VALIDATION",
  "notificationId": "corr-df-approve-hisense-n",
  "orgId": "DEMO-ORG",
  "sfdcRecordId": "DEV-9",
  "applicationRef": "corr-df-approve-hisense-app",
  "correlationId": "corr-df-approve-hisense",
  "originalCorrelationId": "corr-df-approve-hisense",
  "payloadContentType": "application/json",
  "occurredAt": "2026-07-03T10:00:00Z",
  "payload": { "brand": "HISENSE", "status": "1", "imei": "DEV-9" }
}
```

**Drive to outcome**
- **Full-stack:** start the `device-validation` app with the seven CLI rows (no rebuild) so a HISENSE config row exists:
  ```
  --device-validation.brands.HISENSE.validate=false \
  --device-validation.brands.HISENSE.block=true \
  --device-validation.brands.HISENSE.unblock=false \
  --device-validation.brands.HISENSE.validate-by=imei \
  --device-validation.brands.HISENSE.auth-type=OAUTH \
  --device-validation.brands.HISENSE.pass-path=responseStatus \
  --device-validation.brands.HISENSE.pass-value=-4
  ```
  (the pass DATA `{"responseStatus":"-4"}` comes from `hisense-pass.json`). Then produce the envelope above. At `status "1"` the plan is block-only (`validate:false`), so `n_gate_validate` default → `n_gate_block` → `n_block`; `hisense-pass.json` returns `{"responseStatus":"-4"}`, passPath `responseStatus` == pass-value `"-4"` → `valid=true`. **Contrast:** without the config row, HISENSE hits Permutation 8 (fail-closed FAILED).
- **Engine-only:** identical to Permutation 2 with `brand:"HISENSE"`, `validateBy:"imei"`, `authType:"OAUTH"`, `runValidate:false, runBlock:true, runUnblock:false`, and `n_block` result `{"brand":"HISENSE","valid":true,"authType":"OAUTH","vendor":{"responseStatus":"-4"}}`.

**Expected result** — ops status **`COMPLETED_APPROVED`**, terminal `n_valid`, APPROVED. This is the headline "add a brand with 7 config lines, zero code" case. **Verify:** `GET /ops/runs/search?key=corr-df-approve-hisense` → `COMPLETED_APPROVED`.

### Permutation 3b — VALID, status-2 unblock (SAMSUNG, only `n_unblock` runs)

The **closure** path. `status "2"` selects `unblock` only; intersected with SAMSUNG's flags (`unblock:true`) the plan is `runValidate:false, runBlock:false, runUnblock:true`. This proves that `status` — not the brand alone — decides which activities run: the same SAMSUNG row that ran validate+block at `status "1"` (Permutation 1) now runs **only** `unblock`.

**Entry** — topic `orig.device-validation.v1`, key `corr-df-unblock-samsung`:

```json
{
  "transactionId": "corr-df-unblock-samsung-t",
  "schemaVersion": "demo.v1",
  "source": "FILE_DEMO",
  "type": "DEVICE_VALIDATION",
  "notificationId": "corr-df-unblock-samsung-n",
  "orgId": "DEMO-ORG",
  "sfdcRecordId": "DEV-10",
  "applicationRef": "corr-df-unblock-samsung-app",
  "correlationId": "corr-df-unblock-samsung",
  "originalCorrelationId": "corr-df-unblock-samsung",
  "payloadContentType": "application/json",
  "occurredAt": "2026-07-03T10:00:00Z",
  "payload": { "brand": "SAMSUNG", "status": "2", "imei": "DEV-10" }
}
```

**Drive to outcome**
- **Full-stack:** brand `SAMSUNG` (OAUTH, `unblock:true`, `validate-by:imei`) + `status "2"` → plan `runValidate:false, runBlock:false, runUnblock:true`. `n_gate_validate` default → `n_gate_block` default → `n_gate_unblock` true → `n_unblock`. `samsung-pass.json` returns `{"respCode":"0"}` for the `unblock` call → `valid=true`.
- **Engine-only** (instance `ji-corr-df-unblock-samsung`):

  n_decide (`runUnblock:true`, others false):
  ```json
  { "journeyInstanceId": "ji-corr-df-unblock-samsung", "correlationId": "corr-df-unblock-samsung", "nodeId": "n_decide", "capabilityKey": "device-validation", "status": "OK", "result": { "brand": "SAMSUNG", "validateBy": "imei", "authType": "OAUTH", "runValidate": false, "runBlock": false, "runUnblock": true }, "errorClass": null }
  ```
  n_unblock:
  ```json
  { "journeyInstanceId": "ji-corr-df-unblock-samsung", "correlationId": "corr-df-unblock-samsung", "nodeId": "n_unblock", "capabilityKey": "device-validation", "status": "OK", "result": { "brand": "SAMSUNG", "valid": true, "authType": "OAUTH", "vendor": { "respCode": "0" } }, "errorClass": null }
  ```
  (Do **not** publish `n_validate` or `n_block` — neither is dispatched at `status "2"`.)

**Expected result** — ops status **`COMPLETED_APPROVED`**; terminal `n_valid`, APPROVED, `emitted:["DeviceValidationValid"]`. Transitions: `n_decide`→`n_unblock` COMPLETED (no `n_validate`/`n_block` — proving `status "2"` runs only `unblock`). **Verify:** `GET /ops/runs/search?key=corr-df-unblock-samsung` → `status:"COMPLETED_APPROVED"`; detail shows only `n_decide` and `n_unblock` in `transitions`.

### Permutation 4 — INVALID at validate (validate+block brand, `validateResult.valid=false`)

**Entry** — topic `orig.device-validation.v1`, key `corr-df-decline-validate`:

```json
{
  "transactionId": "corr-df-decline-validate-t",
  "schemaVersion": "demo.v1",
  "source": "FILE_DEMO",
  "type": "DEVICE_VALIDATION",
  "notificationId": "corr-df-decline-validate-n",
  "orgId": "DEMO-ORG",
  "sfdcRecordId": "DEV-DECLINE",
  "applicationRef": "corr-df-decline-validate-app",
  "correlationId": "corr-df-decline-validate",
  "originalCorrelationId": "corr-df-decline-validate",
  "payloadContentType": "application/json",
  "occurredAt": "2026-07-03T10:00:00Z",
  "payload": { "brand": "SAMSUNG", "status": "1", "imei": "DEV-DECLINE" }
}
```

**Drive to outcome**
- **Full-stack:** brand `SAMSUNG` (`validate:true`) at `status "1"` + `imei=DEV-DECLINE`. `samsung-decline.json` returns HTTP **200** `{"respCode":"1"}` → passPath `respCode` != `"0"` → `valid=false` at `n_validate` → `n_after_validate` default → `n_invalid`. (`n_block` never runs — SAMSUNG is invalid at validate.) Same shape for BOSCH/`DEV-DECLINE` (`{"result":{"code":"F"}}`, `serial=DEV-DECLINE`).
- **Engine-only** (instance `ji-corr-df-decline-validate`): n_decide OK `{...,"runValidate":true,"runBlock":true,"runUnblock":false}`, then n_validate invalid:
  ```json
  { "journeyInstanceId": "ji-corr-df-decline-validate", "correlationId": "corr-df-decline-validate", "nodeId": "n_validate", "capabilityKey": "device-validation", "status": "OK", "result": { "brand": "SAMSUNG", "valid": false, "authType": "OAUTH", "vendor": { "respCode": "1" } }, "errorClass": null }
  ```

**Expected result** — ops status **`COMPLETED_DECLINED`** (a clean business decline — teal, **not** red, no error class); terminal node **`n_invalid`**, terminalOutcome **REJECTED**, `emitted:["DeviceValidationInvalid"]`. Decision on `orig.decision.v1`: `outcome:"REJECTED"`, `terminalNodeId:"n_invalid"`. No DLQ, `dlqTopicRef:null`. **Verify:** `GET /ops/runs/search?key=corr-df-decline-validate` → `status:"COMPLETED_DECLINED"`; detail `transitions` show `n_decide`, `n_validate` COMPLETED (no `n_block`).

### Permutation 5 — INVALID at block (block-only brand, `blockResult.valid=false`)

**Entry** — topic `orig.device-validation.v1`, key `corr-df-decline-block`:

```json
{
  "transactionId": "corr-df-decline-block-t",
  "schemaVersion": "demo.v1",
  "source": "FILE_DEMO",
  "type": "DEVICE_VALIDATION",
  "notificationId": "corr-df-decline-block-n",
  "orgId": "DEMO-ORG",
  "sfdcRecordId": "DEV-DECLINE",
  "applicationRef": "corr-df-decline-block-app",
  "correlationId": "corr-df-decline-block",
  "originalCorrelationId": "corr-df-decline-block",
  "payloadContentType": "application/json",
  "occurredAt": "2026-07-03T10:00:00Z",
  "payload": { "brand": "GODREJ", "status": "1", "serial": "DEV-DECLINE" }
}
```

**Drive to outcome**
- **Full-stack:** brand `GODREJ` (block-only) + `serial=DEV-DECLINE`. Plan `runBlock:true` (validate/unblock false) → `n_gate_validate` default → `n_gate_block` → `n_block`; `godrej-decline.json` returns 200 `{"status":"DECLINED"}` → passPath `status` != `"OK"` → `valid=false` → `n_after_block` default → `n_invalid`.
- **Engine-only** (instance `ji-corr-df-decline-block`): n_decide OK `{...,"runValidate":false,"runBlock":true,"runUnblock":false,"authType":"NA"}`, then n_block invalid:
  ```json
  { "journeyInstanceId": "ji-corr-df-decline-block", "correlationId": "corr-df-decline-block", "nodeId": "n_block", "capabilityKey": "device-validation", "status": "OK", "result": { "brand": "GODREJ", "valid": false, "authType": "NA", "vendor": { "status": "DECLINED" } }, "errorClass": null }
  ```

**Expected result** — ops status **`COMPLETED_DECLINED`**, terminal `n_invalid`, REJECTED, `emitted:["DeviceValidationInvalid"]`. **Verify:** `GET /ops/runs/search?key=corr-df-decline-block` → `COMPLETED_DECLINED`.

### Permutation 6 — INVALID at block AFTER validate passed (validate+block brand, engine-only)

This arm — validate `valid=true` but block `valid=false` → `n_after_block` default → `n_invalid` — has **no full-stack lever** (the default WireMock stubs decline every endpoint for `DEV-DECLINE` and 422 every one for `DEV-FAIL`; there is no per-activity device lever). It is reachable **only in engine-only manual mode**.

**Entry** — topic `orig.device-validation.v1`, key `corr-df-block-only-decline`:

```json
{
  "transactionId": "corr-df-block-only-decline-t",
  "schemaVersion": "demo.v1",
  "source": "FILE_DEMO",
  "type": "DEVICE_VALIDATION",
  "notificationId": "corr-df-block-only-decline-n",
  "orgId": "DEMO-ORG",
  "sfdcRecordId": "DEV-3",
  "applicationRef": "corr-df-block-only-decline-app",
  "correlationId": "corr-df-block-only-decline",
  "originalCorrelationId": "corr-df-block-only-decline",
  "payloadContentType": "application/json",
  "occurredAt": "2026-07-03T10:00:00Z",
  "payload": { "brand": "BOSCH", "status": "1", "serial": "DEV-3" }
}
```

**Drive to outcome** — engine-only (instance `ji-corr-df-block-only-decline`), publish in order:

n_decide (validate + block both run):
```json
{ "journeyInstanceId": "ji-corr-df-block-only-decline", "correlationId": "corr-df-block-only-decline", "nodeId": "n_decide", "capabilityKey": "device-validation", "status": "OK", "result": { "brand": "BOSCH", "validateBy": "serial", "authType": "BAUTH", "runValidate": true, "runBlock": true, "runUnblock": false }, "errorClass": null }
```
n_validate **passes**:
```json
{ "journeyInstanceId": "ji-corr-df-block-only-decline", "correlationId": "corr-df-block-only-decline", "nodeId": "n_validate", "capabilityKey": "device-validation", "status": "OK", "result": { "brand": "BOSCH", "valid": true, "authType": "BAUTH", "vendor": { "result": { "code": "S" } } }, "errorClass": null }
```
n_block **is invalid**:
```json
{ "journeyInstanceId": "ji-corr-df-block-only-decline", "correlationId": "corr-df-block-only-decline", "nodeId": "n_block", "capabilityKey": "device-validation", "status": "OK", "result": { "brand": "BOSCH", "valid": false, "authType": "BAUTH", "vendor": { "result": { "code": "F" } } }, "errorClass": null }
```

**Expected result** — ops status **`COMPLETED_DECLINED`**, terminal `n_invalid`, REJECTED. Transitions: `n_decide`→`n_validate`→`n_block` all COMPLETED, then `n_invalid` (the invalid happened at the second activity). **Verify:** `GET /ops/runs/search?key=corr-df-block-only-decline` → `COMPLETED_DECLINED`; detail confirms `n_validate` COMPLETED yet outcome REJECTED.

### Permutation 7 — FAILED, PERMANENT via vendor 4xx (`DEV-FAIL`)

**Entry** — topic `orig.device-validation.v1`, key `corr-df-fail-permanent`:

```json
{
  "transactionId": "corr-df-fail-permanent-t",
  "schemaVersion": "demo.v1",
  "source": "FILE_DEMO",
  "type": "DEVICE_VALIDATION",
  "notificationId": "corr-df-fail-permanent-n",
  "orgId": "DEMO-ORG",
  "sfdcRecordId": "DEV-FAIL",
  "applicationRef": "corr-df-fail-permanent-app",
  "correlationId": "corr-df-fail-permanent",
  "originalCorrelationId": "corr-df-fail-permanent",
  "payloadContentType": "application/json",
  "occurredAt": "2026-07-03T10:00:00Z",
  "payload": { "brand": "SAMSUNG", "status": "1", "imei": "DEV-FAIL" }
}
```

**Drive to outcome**
- **Full-stack:** device id `DEV-FAIL` → `00-fail.json` (priority 1) returns **HTTP 422** for any brand. Client maps 4xx → `CapabilityException(PERMANENT)`. For SAMSUNG at `status "1"` the first vendor call is `n_validate`, so `n_validate` fails. (For a block-only brand the 422 lands on `n_block`.) No retry (no retrySpec) → run fails immediately.
- **Engine-only** (instance `ji-corr-df-fail-permanent`): n_decide OK (`runValidate:true`), then n_validate ERROR:
  ```json
  { "journeyInstanceId": "ji-corr-df-fail-permanent", "correlationId": "corr-df-fail-permanent", "nodeId": "n_validate", "capabilityKey": "device-validation", "status": "ERROR", "result": {}, "errorClass": "PERMANENT" }
  ```

**Expected result** — ops status **`FAILED_SFDC_NOTIFIED`** (the engine emits the ERROR JourneyDecision to the channel first, then marks failed); terminal node **`n_validate`**, terminalOutcome **ERROR**. `RunDetail.sfdcNotified:"SENT"`, `nodeStats` includes `{nodeId:"n_validate", failureClass:"PERMANENT"}`, `dlqTopicRef` non-null (pointer). Decision on `orig.decision.v1`: `outcome:"ERROR"`, `terminalNodeId:"n_validate"`, `loanId:null`. There is **no Kafka dead-letter** for this (the run fails in place; `dlqTopicRef` is only a detail-view pointer). **Verify:** `GET /ops/runs/search?key=corr-df-fail-permanent` → `status:"FAILED_SFDC_NOTIFIED"`; detail `nodeStats[].failureClass == "PERMANENT"`.

### Permutation 8 — FAILED, PERMANENT via unknown brand (fail-closed at `n_decide`)

This is the "unknown enum / fail-closed" case for this journey: `rowOf(brand)` throws `PERMANENT` when the brand has no config row (the deliberate counter to legacy fail-open orgId).

**Entry** — topic `orig.device-validation.v1`, key `corr-df-unknown-brand`:

```json
{
  "transactionId": "corr-df-unknown-brand-t",
  "schemaVersion": "demo.v1",
  "source": "FILE_DEMO",
  "type": "DEVICE_VALIDATION",
  "notificationId": "corr-df-unknown-brand-n",
  "orgId": "DEMO-ORG",
  "sfdcRecordId": "DEV-7",
  "applicationRef": "corr-df-unknown-brand-app",
  "correlationId": "corr-df-unknown-brand",
  "originalCorrelationId": "corr-df-unknown-brand",
  "payloadContentType": "application/json",
  "occurredAt": "2026-07-03T10:00:00Z",
  "payload": { "brand": "NOKIA", "status": "1", "deviceId": "DEV-7" }
}
```

**Drive to outcome**
- **Full-stack:** brand `NOKIA` (no `device-validation.brands.NOKIA` row). `decideActivities` → `rowOf` → `CapabilityException(PERMANENT, "no config row for brand=NOKIA (fail closed)")` at **`n_decide`** (no HTTP call made). Also produced by a blank/missing `payload.brand` (`"missing brand"`, PERMANENT).
- **Engine-only** (instance `ji-corr-df-unknown-brand`): fail the very first node:
  ```json
  { "journeyInstanceId": "ji-corr-df-unknown-brand", "correlationId": "corr-df-unknown-brand", "nodeId": "n_decide", "capabilityKey": "device-validation", "status": "ERROR", "result": {}, "errorClass": "PERMANENT" }
  ```

**Expected result** — ops status **`FAILED_SFDC_NOTIFIED`**, terminal node **`n_decide`**, terminalOutcome **ERROR**, `nodeStats[].failureClass:"PERMANENT"`. **Verify:** `GET /ops/runs/search?key=corr-df-unknown-brand` → `FAILED_SFDC_NOTIFIED`; detail `terminalNodeId:"n_decide"` (no vendor node ran).

### Permutation 9 — FAILED, TRANSIENT via vendor unreachable / 5xx

**Entry** — topic `orig.device-validation.v1`, key `corr-df-fail-transient`:

```json
{
  "transactionId": "corr-df-fail-transient-t",
  "schemaVersion": "demo.v1",
  "source": "FILE_DEMO",
  "type": "DEVICE_VALIDATION",
  "notificationId": "corr-df-fail-transient-n",
  "orgId": "DEMO-ORG",
  "sfdcRecordId": "DEV-5",
  "applicationRef": "corr-df-fail-transient-app",
  "correlationId": "corr-df-fail-transient",
  "originalCorrelationId": "corr-df-fail-transient",
  "payloadContentType": "application/json",
  "occurredAt": "2026-07-03T10:00:00Z",
  "payload": { "brand": "GODREJ", "status": "1", "serial": "DEV-5" }
}
```

**Drive to outcome**
- **Full-stack:** cause a transport-level failure on the vendor call — either **stop the mock-vendors server on :19106** (connection refused → `IOException` → `TRANSIENT`, `"vendor unreachable"`) or add a stub returning **HTTP 5xx** (→ `TRANSIENT`, `"vendor HTTP 5xx"`). Brand `GODREJ` is used so the failing node is `n_block` (block-only). Because no `retrySpec` is configured, the TRANSIENT class does **not** retry here — the node fails on the first response, same as PERMANENT. (Sub-lever for an OAUTH brand: keep the vendor up but stop the token endpoint → `fetchToken` → TRANSIENT.)
- **Engine-only** (instance `ji-corr-df-fail-transient`): n_decide OK (`runBlock:true`, validate/unblock false), then:
  ```json
  { "journeyInstanceId": "ji-corr-df-fail-transient", "correlationId": "corr-df-fail-transient", "nodeId": "n_block", "capabilityKey": "device-validation", "status": "ERROR", "result": {}, "errorClass": "TRANSIENT" }
  ```

**Expected result** — ops status **`FAILED_SFDC_NOTIFIED`**, terminal node **`n_block`**, terminalOutcome **ERROR**, `nodeStats[].failureClass:"TRANSIENT"`. **Verify:** `GET /ops/runs/search?key=corr-df-fail-transient` → `FAILED_SFDC_NOTIFIED`; detail `nodeStats[].failureClass == "TRANSIENT"` (distinguishes it from the PERMANENT case at the same terminal outcome).

### Permutation 10 — FAILED, AMBIGUOUS via read timeout

**Entry** — topic `orig.device-validation.v1`, key `corr-df-fail-ambiguous`:

```json
{
  "transactionId": "corr-df-fail-ambiguous-t",
  "schemaVersion": "demo.v1",
  "source": "FILE_DEMO",
  "type": "DEVICE_VALIDATION",
  "notificationId": "corr-df-fail-ambiguous-n",
  "orgId": "DEMO-ORG",
  "sfdcRecordId": "DEV-6",
  "applicationRef": "corr-df-fail-ambiguous-app",
  "correlationId": "corr-df-fail-ambiguous",
  "originalCorrelationId": "corr-df-fail-ambiguous",
  "payloadContentType": "application/json",
  "occurredAt": "2026-07-03T10:00:00Z",
  "payload": { "brand": "GODREJ", "status": "1", "serial": "DEV-6" }
}
```

**Drive to outcome**
- **Full-stack:** add a WireMock stub for `POST /vendor/device-validation/block` with a fixed delay **> `read-timeout-ms` (10000ms)** (e.g. `"fixedDelayMilliseconds": 12000`). The client's read times out → `SocketTimeoutException` → `CapabilityException(AMBIGUOUS, "vendor read timeout")`. No retry configured → node fails immediately.
- **Engine-only** (instance `ji-corr-df-fail-ambiguous`): n_decide OK (`runBlock:true`, validate/unblock false), then:
  ```json
  { "journeyInstanceId": "ji-corr-df-fail-ambiguous", "correlationId": "corr-df-fail-ambiguous", "nodeId": "n_block", "capabilityKey": "device-validation", "status": "ERROR", "result": {}, "errorClass": "AMBIGUOUS" }
  ```

**Expected result** — ops status **`FAILED_SFDC_NOTIFIED`**, terminal node **`n_block`**, terminalOutcome **ERROR**, `nodeStats[].failureClass:"AMBIGUOUS"`. **Verify:** `GET /ops/runs/search?key=corr-df-fail-ambiguous` → detail `nodeStats[].failureClass == "AMBIGUOUS"`.

### Permutation 11 — BREAKER_OPEN (NOT REACHABLE in this journey)

No node in `device-validation` declares a circuit-breaker policy, and the response `errorClass` enum only admits `TRANSIENT | PERMANENT | AMBIGUOUS` (`BREAKER_OPEN` exists only as a `nodeStats.failureClass` the engine's breaker would stamp, which is never armed here). **There is no entry or lever that produces `BREAKER_OPEN` for this journey** — recorded here for completeness so a tester does not hunt for it. (It is exercisable only on a journey/node that configures a breaker.)

### Permutation 12 — Idempotency / duplicate resend (same correlationId)

**Entry** — re-produce the **exact** Permutation 1 envelope (same `correlationId:"corr-df-approve-samsung"`) to `orig.device-validation.v1`, key `corr-df-approve-samsung`, a second time (after the first run started/completed):

```json
{
  "transactionId": "corr-df-approve-samsung-t2",
  "schemaVersion": "demo.v1",
  "source": "FILE_DEMO",
  "type": "DEVICE_VALIDATION",
  "notificationId": "corr-df-approve-samsung-n",
  "orgId": "DEMO-ORG",
  "sfdcRecordId": "DEV-1",
  "applicationRef": "corr-df-approve-samsung-app",
  "correlationId": "corr-df-approve-samsung",
  "originalCorrelationId": "corr-df-approve-samsung",
  "payloadContentType": "application/json",
  "occurredAt": "2026-07-03T10:05:00Z",
  "payload": { "brand": "SAMSUNG", "status": "1", "imei": "DEV-1" }
}
```

**Drive to outcome** — no levers needed. `instanceId = "ji-corr-df-approve-samsung"` is identical, so `store.insertIfAbsent(instance)` loses → engine logs `journey.start.duplicate` and **drops** the message. No second run, no duplicate vendor call, no duplicate decision. This holds even if a prior run is still mid-flight (the redelivery does not resume it — the sweeper is the net). Engine-only: nothing extra to publish; the duplicate never dispatches a node.

**Expected result** — still **exactly one** run for `corr-df-approve-samsung` (whatever its outcome was, e.g. `COMPLETED_APPROVED`). **Verify:** `GET /ops/runs/search?key=corr-df-approve-samsung` → the list has a **single** `RunSummaryDto` (search returns newest-first; there is no second run). The second produce added nothing.

### Permutation 13 — Unknown-type fail-closed (poison → DLQ)

**Entry** — topic `orig.device-validation.v1`, key `corr-df-badtype`, with a `type` that has no `type-to-journey` row:

```json
{
  "transactionId": "corr-df-badtype-t",
  "schemaVersion": "demo.v1",
  "source": "FILE_DEMO",
  "type": "DEVICE_VALIDATE",
  "notificationId": "corr-df-badtype-n",
  "orgId": "DEMO-ORG",
  "sfdcRecordId": "DEV-8",
  "applicationRef": "corr-df-badtype-app",
  "correlationId": "corr-df-badtype",
  "originalCorrelationId": "corr-df-badtype",
  "payloadContentType": "application/json",
  "occurredAt": "2026-07-03T10:00:00Z",
  "payload": { "brand": "SAMSUNG", "status": "1", "imei": "DEV-1" }
}
```

**Drive to outcome** — no levers. `type:"DEVICE_VALIDATE"` (typo; the only mapped value is `DEVICE_VALIDATION`) → `JourneyOrchestrator` → `registry.resolveForType` → `UnroutableTypeException` → `OriginationConsumer` treats it as a `PoisonMessageException`. Fail-closed A2: **never** a default journey. Engine-only: same — this fails at ingest, before any node dispatch.

**Expected result** — **no run is started** (nothing in the ops store). The message is dead-lettered to **`orig.device-validation.v1.dlq`** (source topic + `.dlq` suffix). **Verify:** `GET /ops/runs/search?key=corr-df-badtype` → **empty list** (`[]`); confirm the poison landed on `orig.device-validation.v1.dlq` via Kafka UI.

### Permutation 14 — Stuck run → liveness sweeper force-fail

**Entry** — topic `orig.device-validation.v1`, key `corr-df-stuck`:

```json
{
  "transactionId": "corr-df-stuck-t",
  "schemaVersion": "demo.v1",
  "source": "FILE_DEMO",
  "type": "DEVICE_VALIDATION",
  "notificationId": "corr-df-stuck-n",
  "orgId": "DEMO-ORG",
  "sfdcRecordId": "DEV-STUCK",
  "applicationRef": "corr-df-stuck-app",
  "correlationId": "corr-df-stuck",
  "originalCorrelationId": "corr-df-stuck",
  "payloadContentType": "application/json",
  "occurredAt": "2026-07-03T10:00:00Z",
  "payload": { "brand": "SAMSUNG", "status": "1", "imei": "DEV-1" }
}
```

**Drive to outcome** — start the run but never let a capability response arrive, so a node stays pending past the run budget:
- **Engine-only (the practical path):** publish the envelope, then publish **nothing** on `cap.device-validation.response.v1` (or publish only the `n_decide` OK and then stop, leaving `n_validate` pending). The run sits `RUNNING`.
- **Full-stack:** requires the vendor to hang beyond 900s — not practical with the stubs; use the engine-only path.

Timeline (defaults: `run-budget-seconds=900`, `sweep-interval-ms=60000`): at **~840s** (`900 − 60`) the ops read-model flags it `stuck=true` (still store-state `RUNNING`); at **900s** the sweeper force-fails it — publishes ERROR `JourneyDecision` (`outcome:ERROR`, `terminalNodeId:"__timeout__"`, `loanId:null`) FIRST, marks SFDC notified, sets `State.FAILED`, emits ops event `run.sweptTimeout`.

**Expected result** — before 900s: ops status **`RUNNING`**, `stuck:true`, `sweepDeadline = startedAt + 900s`. After 900s: ops status **`FAILED_SFDC_NOTIFIED`** with terminal node **`__timeout__`**, terminalOutcome **ERROR**, ops lifecycle event `run.sweptTimeout` on `ops.journey.events.v1`. **Verify (stuck window):** `GET /ops/runs?stuckOnly=true` → the run appears with `stuck:true`, `status:"RUNNING"`; `GET /ops/runs/search?key=corr-df-stuck` shows `sweepDeadline` non-null. **Verify (after sweep):** `GET /ops/runs/search?key=corr-df-stuck` → `status:"FAILED_SFDC_NOTIFIED"`; detail `terminalNodeId:"__timeout__"`, `sweepDeadline:null`.

---

### Ops-query verification matrix (all runs above; `http://localhost:8082/ops`, headers `X-Ops-Token: dev-ops-token`, `X-User-Id: ops.analyst@bank`)

Run the permutations first so the store is populated, then exercise each ops read. All are GET.

### Ops-A — List (defaults)
`GET /ops/runs` → `PageDto` `{items:[…], page:0, size:50, totalItems, totalPages}`, sorted `startedAt` DESC. **Expected:** every device-validation run above appears (valid, invalid, failed, running).

### Ops-B — Filter by status
- `GET /ops/runs?status=COMPLETED_APPROVED` → Permutations 1, 2, 3, 3b.
- `GET /ops/runs?status=COMPLETED_DECLINED` → Permutations 4, 5, 6.
- `GET /ops/runs?status=FAILED_SFDC_NOTIFIED` → Permutations 7, 8, 9, 10, and 14 (post-sweep).
- `GET /ops/runs?status=RUNNING` → Permutation 14 (pre-sweep), plus any in-flight.
- `GET /ops/runs?status=FAILED_NOTIFY_PENDING` → empty for this journey (the engine notifies-first, so device-validation failures land as `FAILED_SFDC_NOTIFIED`).
- `GET /ops/runs?status=BOGUS` → **400** `{"error":"BAD_REQUEST","message":"unknown status 'BOGUS' ..."}`.

### Ops-C — Filter by journeyKey
`GET /ops/runs?journeyKey=device-validation` → exactly the runs from this section (excludes loan-origination, employee-lwd, etc.). Combine: `GET /ops/runs?journeyKey=device-validation&status=COMPLETED_DECLINED&page=0&size=25`.

### Ops-D — Filter by time window (`since` / `until`)
`GET /ops/runs?since=2026-07-03T00:00:00Z&until=2026-07-04T00:00:00Z` → runs with `startedAt` in the window. Bad instant, e.g. `?since=yesterday` → **400 BAD_REQUEST**.

### Ops-E — stuckOnly
`GET /ops/runs?stuckOnly=true` → only `RUNNING` runs past the ~840s stuck threshold → Permutation 14 during its stuck window (`stuck:true`, `sweepDeadline` set). Empty once that run is swept to FAILED.

### Ops-F — Exact search (`/ops/runs/search?key=`)
Exact match across `runId | correlationId | notificationId | sfdcRecordId`, newest-first `List<RunSummaryDto>`:
- `GET /ops/runs/search?key=corr-df-approve-samsung` (correlationId) → the SAMSUNG approve run.
- `GET /ops/runs/search?key=corr-df-decline-validate-n` (notificationId) → the validate-decline run.
- `GET /ops/runs/search?key=DEV-2` (sfdcRecordId) → the GODREJ approve run.
- `GET /ops/runs/search?key=corr-df-badtype` → **empty `[]`** (unknown-type never started a run — Permutation 13).
- `GET /ops/runs/search?key=` (blank) → **400 BAD_REQUEST** (`"query parameter 'key' must be a non-blank exact id ..."`).

### Ops-G — Detail (`/ops/runs/{runId}`)
Take a `runId` from any search/list result: `GET /ops/runs/{runId}` → `RunDetailDto`. **Check per outcome:**
- Valid run: `status:"COMPLETED_APPROVED"`, `terminalNodeId:"n_valid"`, `terminalOutcome:"APPROVED"`, `sfdcNotified:"SENT"`, `dlqTopicRef:null`, `transitions` for `n_decide`/`n_validate`/`n_block` (or just `n_decide`/`n_block` for block-only brands, or `n_decide`/`n_unblock` for a status-2 unblock).
- Invalid run: `status:"COMPLETED_DECLINED"`, `terminalNodeId:"n_invalid"`, `terminalOutcome:"REJECTED"`, `dlqTopicRef:null`.
- Failed run: `status:"FAILED_SFDC_NOTIFIED"`, `terminalOutcome:"ERROR"`, `nodeStats[].failureClass` = `PERMANENT` / `TRANSIENT` / `AMBIGUOUS` (this field is how you tell the three failure permutations apart at the same terminal status), `dlqTopicRef` non-null.
- Unknown `runId` → **404 with empty body**.

---

### Maker-checker — not applicable to this section

The device-validation journey is loaded from **classpath** on the local profile (`idfc.engine.journey-source=classpath`, `journey-resources` includes `journeys/device-validation.journey.json`); it does **not** pass through the journey-registry maker-checker lifecycle. The registry endpoints (create/draft/submit/approve, 403 self-approve, 409 second-draft/lifecycle, 422 validation) are exercised in the journey-registry section, not here. No maker-checker permutation is triggerable via the device-validation demo door.


---

<a id="sec-employee-lwd"></a>

## Demo: employee-lwd-update file-batch

This journey is the **file-batch** demo: a local-folder CSV edge (`FolderBatchPoller`) turns each CSV row into **one engine run**, publishing a canonical envelope per record to `orig.employee-lwd-update.v1`. The journey graph is **linear with no branch** — one task node `n_update` → one terminal `n_done`. There is therefore exactly **one business outcome** (`COMPLETED_APPROVED`) and a family of **failure** outcomes driven purely by the Fusion HCM HTTP status → `ErrorClass` mapping.

**Prerequisites (all permutations):**
- Engine (`origination-journey`) started via `./run-services.sh` (or `bootRun --spring.profiles.active=local --idfc.engine.journey-source=classpath`). The **`local` profile** (`application-local.yml`) loads `journeys/employee-lwd-update.journey.json`, adds the door topic `orig.employee-lwd-update.v1` to `idfc.engine.origination-topics`, and merges the `type-to-journey` row `EMPLOYEE_LWD_UPDATE → employee-lwd-update` — **there is no separate `demo` profile** (`run-services.sh` sets `IDFC_ENGINE_JOURNEY_SOURCE=classpath`; a bare `--spring.profiles.active=local` defaults to `registry` and needs the `journey-source=classpath` override to load the classpath journeys).
- Full-stack mode additionally needs the `fusion-hcm` capability running (listening on `cap.fusion-hcm.request.v1`) and the `file-batch-edge` started with `--file-batch.enabled=true` (poller scans `demo/batch-inbox/` every 2000 ms), plus the WireMock fusion server on `http://localhost:19107`.
- Ops API: `http://localhost:8082/ops`, headers `X-Ops-Token: dev-ops-token` + `X-User-Id: ops.analyst@bank`.

**Journey definition (pinned facts used below):**

| node | type | capability.operation | input mapping | output | next | terminal status/outcome |
|---|---|---|---|---|---|---|
| `n_update` | task (start) | `fusion-hcm.updateEmployee` | `{ employeeId: context.employeeId, lastWorkingDay: context.lastWorkingDay }` | `context.fusion` | `[n_done]` | — |
| `n_done` | terminal | action `push_decision_to_channel` | — | — | — | `status=completed` → **APPROVED**, emit `EmployeeLwdUpdated` |

`n_update` has **no `retry`, no `onFailure`, no breaker policy**, so *any* `CapabilityResponse.status=ERROR` (regardless of `errorClass`) fails the node immediately → run `FAILED`, `terminalOutcome=ERROR`, `terminalNodeId=n_update`. `context.fusion` is never read by a branch, so any `OK` completes.

**Envelope id roles (from `FolderBatchPoller.envelopeJson`):**
- `batchId = "batch-" + sha256(fileBytes).substring(0,12)` — content-derived; identical content ⇒ identical batchId.
- `correlationId = originalCorrelationId = batchId + "-rN"` (N = 1-based row index). This is the engine's dedup key → **`instanceId = "ji-" + correlationId`**.
- `notificationId = batchId` — shared across the whole batch (the one exact ops-search key that groups it).
- `sfdcRecordId = employeeId`; `applicationRef = batchId + "/" + employeeId`; `orgId = HR-DEMO`; `type = EMPLOYEE_LWD_UPDATE`; `source = FILE_DEMO`; `schemaVersion = file-demo.v1`. Kafka key = `correlationId`.

**Capability contract:** `updateEmployee` reads `payload.employeeId` (blank → `CapabilityException(PERMANENT)`) and POSTs `{"lastWorkingDay":"<value>"}` to `http://localhost:19107/vendor/fusion/employees/{employeeId}`. On OK it returns `result = { updated:true, employeeId:<id>, vendor:<mock body> }`. HTTP → `ErrorClass`: **4xx → PERMANENT**, **5xx → TRANSIENT**, **read-timeout (SocketTimeoutException) → AMBIGUOUS**, **connect/IO → TRANSIENT**, other → PERMANENT.

**WireMock fusion levers** (`infra/mock-vendors/fusion/mappings/`):

| mapping | priority | match | HTTP | body | drives |
|---|---|---|---|---|---|
| `00-update-ok.json` | 1 | `POST /vendor/fusion/employees/.*` AND `lastWorkingDay` matches `/\d{4}-\d{2}-\d{2}/` | 200 | `{"status":"UPDATED"}` | COMPLETED |
| `99-bad-date.json` | 10 | `POST /vendor/fusion/employees/.*` (any body) | 400 | `{"error":"invalid_last_working_day"}` | 400 → PERMANENT → FAILED |

So in full-stack mode the **only lever is the `lastWorkingDay` value**: a `YYYY-MM-DD` date → 200 → COMPLETED; anything else → 400 → PERMANENT → FAILED. TRANSIENT/AMBIGUOUS require transport-level manipulation (see P5/P6).

Throughout, replace the placeholder `batch-abc123def456` with the **real** batchId — read it from the engine log line `batch.dispatched batchId=… records=5 topic=orig.employee-lwd-update.v1`, or from `GET /ops/runs?journeyKey=employee-lwd-update`.

---

### P1 — Canonical batch of 5 (4 COMPLETED + 1 FAILED)

**Entry (full-stack — file drop):** copy this file into `demo/batch-inbox/` as `employees-<epoch>.csv`:

```
employeeId,lastWorkingDay
EMP-001,2026-07-31
EMP-002,2026-08-15
EMP-003,2026-07-20
EMP-004,not-a-date
EMP-005,2026-09-01
```

The poller emits **five** envelopes to `orig.employee-lwd-update.v1` (keys `batch-abc123def456-r1 … -r5`). Row 4 (`EMP-004,not-a-date`) is the intentional failure. The `00-update-ok` regex passes for the four real dates (→ 200 UPDATED) and fails for `not-a-date`, which falls through to `99-bad-date` (→ HTTP 400 → PERMANENT).

**Drive to outcome — full-stack:** nothing further; the `lastWorkingDay` value on each row is the lever. Rows 1/2/3/5 → COMPLETED; row 4 → FAILED (PERMANENT).

**Drive to outcome — engine-only manual mode (no `fusion-hcm` running):** publish these to `cap.fusion-hcm.response.v1` (key = the `journeyInstanceId`), one per record. For the four successes (r1 shown; repeat for r2/r3/r5 with the matching id/employeeId):

```json
{
  "journeyInstanceId": "ji-batch-abc123def456-r1",
  "correlationId": "batch-abc123def456-r1",
  "nodeId": "n_update",
  "capabilityKey": "fusion-hcm",
  "status": "OK",
  "result": { "updated": true, "employeeId": "EMP-001", "vendor": { "status": "UPDATED" } },
  "errorClass": null
}
```

For the r4 failure:

```json
{
  "journeyInstanceId": "ji-batch-abc123def456-r4",
  "correlationId": "batch-abc123def456-r4",
  "nodeId": "n_update",
  "capabilityKey": "fusion-hcm",
  "status": "ERROR",
  "result": {},
  "errorClass": "PERMANENT"
}
```

**Expected result:** 4 runs → ops status **`COMPLETED_APPROVED`** (terminal `n_done`, outcome APPROVED, emit `EmployeeLwdUpdated`); 1 run (`EMP-004`) → **`FAILED_SFDC_NOTIFIED`** (terminal `n_update`, outcome ERROR, `nodeStats.failureClass=PERMANENT`, `dlqTopicRef` populated). Transitions on each success: `run.started` → `node.dispatched(n_update)` → `node.completed(n_update)` → `run.completed(APPROVED)`. On the failure: `run.started` → `node.dispatched(n_update)` → `node.failed(n_update)` → `run.failed(ERROR)`.

**Verify:** the batchId groups the whole batch:

```bash
curl -s -H 'X-Ops-Token: dev-ops-token' -H 'X-User-Id: ops.analyst@bank' \
  "http://localhost:8082/ops/runs/search?key=batch-abc123def456"
```

Returns 5 `RunSummaryDto` newest-first: four `status:"COMPLETED_APPROVED"`, one `status:"FAILED_SFDC_NOTIFIED"` — the "batch of 5: 4 succeeded, 1 failed" view.

---

### P2 — Single well-formed record → COMPLETED_APPROVED

**Entry (full-stack — file drop):** a one-record file in `demo/batch-inbox/`:

```
employeeId,lastWorkingDay
EMP-100,2026-12-31
```

**Entry (Kafka manual — direct produce, bypassing the file edge):** topic **`orig.employee-lwd-update.v1`**, key **`batch-manual-001-r1`**, value:

```json
{
  "transactionId": "batch-manual-001-r1-t",
  "schemaVersion": "file-demo.v1",
  "source": "FILE_DEMO",
  "type": "EMPLOYEE_LWD_UPDATE",
  "notificationId": "batch-manual-001",
  "orgId": "HR-DEMO",
  "sfdcRecordId": "EMP-100",
  "applicationRef": "batch-manual-001/EMP-100",
  "correlationId": "batch-manual-001-r1",
  "originalCorrelationId": "batch-manual-001-r1",
  "payloadContentType": "application/json",
  "occurredAt": "2026-07-03T10:00:00Z",
  "payload": { "employeeId": "EMP-100", "lastWorkingDay": "2026-12-31" }
}
```

`instanceId = ji-batch-manual-001-r1`.

**Drive to outcome — full-stack:** `lastWorkingDay="2026-12-31"` matches the date regex → WireMock 200 `{"status":"UPDATED"}` → OK.

**Drive to outcome — engine-only:** publish to `cap.fusion-hcm.response.v1`:

```json
{
  "journeyInstanceId": "ji-batch-manual-001-r1",
  "correlationId": "batch-manual-001-r1",
  "nodeId": "n_update",
  "capabilityKey": "fusion-hcm",
  "status": "OK",
  "result": { "updated": true, "employeeId": "EMP-100", "vendor": { "status": "UPDATED" } },
  "errorClass": null
}
```

**Expected result:** ops status **`COMPLETED_APPROVED`**; `terminalNodeId=n_done`, `terminalOutcome=APPROVED`, emit `EmployeeLwdUpdated`; `sfdcNotified=SENT`; `dlqTopicRef=null`.

**Verify:** `GET /ops/runs/search?key=batch-manual-001-r1` → single run `status:"COMPLETED_APPROVED"`; then `GET /ops/runs/{runId}` shows transitions `n_update COMPLETED` and terminal `n_done`.

---

### P3 — Bad-date record → FAILED (PERMANENT)

**Entry (full-stack — file drop):**

```
employeeId,lastWorkingDay
EMP-200,not-a-date
```

**Entry (Kafka manual):** topic `orig.employee-lwd-update.v1`, key `batch-manual-002-r1`, value as P2 but `sfdcRecordId`/`payload.employeeId="EMP-200"`, `correlationId="batch-manual-002-r1"`, `notificationId="batch-manual-002"`, `payload.lastWorkingDay="not-a-date"`.

**Drive to outcome — full-stack:** `lastWorkingDay="not-a-date"` fails the `00-update-ok` regex → falls through to `99-bad-date` → HTTP 400 → client maps 4xx → **PERMANENT**.

**Drive to outcome — engine-only:** publish to `cap.fusion-hcm.response.v1`:

```json
{
  "journeyInstanceId": "ji-batch-manual-002-r1",
  "correlationId": "batch-manual-002-r1",
  "nodeId": "n_update",
  "capabilityKey": "fusion-hcm",
  "status": "ERROR",
  "result": {},
  "errorClass": "PERMANENT"
}
```

**Expected result:** ops status **`FAILED_SFDC_NOTIFIED`** (`sfdcNotified=SENT`; `FAILED_NOTIFY_PENDING` only if the ERROR-decision notify publish couldn't be confirmed). `terminalNodeId=n_update`, `terminalOutcome=ERROR`, `nodeStats:[{nodeId:"n_update", attempts:1, failureClass:"PERMANENT"}]`, `dlqTopicRef` populated. No `EmployeeLwdUpdated` emit. Transition `node.failed(n_update)` then `run.failed(ERROR)`.

**Verify:** `GET /ops/runs/search?key=batch-manual-002-r1` → `status:"FAILED_SFDC_NOTIFIED"`; detail shows `terminalOutcome:"ERROR"` and `nodeStats[0].failureClass:"PERMANENT"`.

---

### P4 — Blank employeeId → FAILED (PERMANENT, capability guard)

This is the distinct fail path where the capability itself rejects before any HTTP call (`updateEmployee` throws `CapabilityException(PERMANENT,"blank employeeId")`).

**Entry (full-stack — file drop):** a row whose first field is empty (CSV still has exactly 2 fields, so it parses — the *record* fails, not the file):

```
employeeId,lastWorkingDay
,2026-07-31
```

**Entry (Kafka manual):** topic `orig.employee-lwd-update.v1`, key `batch-manual-003-r1`, value as P2 with `payload.employeeId=""` (blank), `sfdcRecordId=""`, `correlationId="batch-manual-003-r1"`, `notificationId="batch-manual-003"`.

**Drive to outcome — full-stack:** the blank `employeeId` short-circuits inside `updateEmployee` → ERROR/PERMANENT (no Fusion call, WireMock never hit).

**Drive to outcome — engine-only:** same ERROR/PERMANENT response as P3 with `journeyInstanceId="ji-batch-manual-003-r1"`, `correlationId="batch-manual-003-r1"`, `result:{}`, `errorClass:"PERMANENT"`.

**Expected result:** ops status **`FAILED_SFDC_NOTIFIED`**; `terminalNodeId=n_update`, `terminalOutcome=ERROR`, `failureClass=PERMANENT`. Same shape as P3 — the difference is only the failure *source* (capability precondition vs vendor 400), both PERMANENT.

**Verify:** `GET /ops/runs/search?key=batch-manual-003-r1`.

---

### P5 — Fusion 5xx / unreachable → FAILED (TRANSIENT)

Because `n_update` declares **no `retrySpec`**, a TRANSIENT ERROR does **not** retry — it fails the run on the first response, identical control flow to PERMANENT. The only observable difference is `nodeStats.failureClass`.

**Entry:** as P2 (file drop or Kafka manual), e.g. `correlationId="batch-manual-005-r1"`, `notificationId="batch-manual-005"`, a valid date `2026-07-31`.

**Drive to outcome — full-stack:** you must cause a transport failure, not a body value:
- **5xx:** add a WireMock stub for `POST /vendor/fusion/employees/.*` returning HTTP 500 (higher precedence than `00-update-ok`, e.g. `priority:0`) → client maps 5xx → **TRANSIENT**.
- **Unreachable:** stop the mock-vendors fusion server (or point `fusion.base-url` at a dead port) → connection refused/IO → **TRANSIENT**.

**Drive to outcome — engine-only:** publish to `cap.fusion-hcm.response.v1`:

```json
{
  "journeyInstanceId": "ji-batch-manual-005-r1",
  "correlationId": "batch-manual-005-r1",
  "nodeId": "n_update",
  "capabilityKey": "fusion-hcm",
  "status": "ERROR",
  "result": {},
  "errorClass": "TRANSIENT"
}
```

**Expected result:** ops status **`FAILED_SFDC_NOTIFIED`**, `terminalNodeId=n_update`, `terminalOutcome=ERROR`, `nodeStats:[{nodeId:"n_update", attempts:1, failureClass:"TRANSIENT"}]`, `dlqTopicRef` populated. (No retry ladder because the node has no `retryOn` set — TRANSIENT is not retried here.)

**Verify:** `GET /ops/runs/{runId}` → `nodeStats[0].failureClass:"TRANSIENT"`.

---

### P6 — Fusion read-timeout → FAILED (AMBIGUOUS)

**Entry:** as P2, e.g. `correlationId="batch-manual-006-r1"`, `notificationId="batch-manual-006"`, valid date.

**Drive to outcome — full-stack:** add a WireMock stub for `POST /vendor/fusion/employees/.*` with `fixedDelay` greater than `fusion.read-timeout-ms` (default **10000 ms**), e.g. `12000`. The client's `SocketTimeoutException` → `causedBy(SocketTimeoutException)` → **AMBIGUOUS**.

**Drive to outcome — engine-only:** publish to `cap.fusion-hcm.response.v1` with `errorClass:"AMBIGUOUS"`:

```json
{
  "journeyInstanceId": "ji-batch-manual-006-r1",
  "correlationId": "batch-manual-006-r1",
  "nodeId": "n_update",
  "capabilityKey": "fusion-hcm",
  "status": "ERROR",
  "result": {},
  "errorClass": "AMBIGUOUS"
}
```

Note: a null/missing `errorClass` is also treated as AMBIGUOUS by the engine, so omitting the field reaches the same terminal.

**Expected result:** ops status **`FAILED_SFDC_NOTIFIED`**, `terminalNodeId=n_update`, `terminalOutcome=ERROR`, `nodeStats[0].failureClass:"AMBIGUOUS"`, `dlqTopicRef` populated. No retry (no `retryOn`).

**Verify:** `GET /ops/runs/{runId}` → `failureClass:"AMBIGUOUS"`.

---

### P7 — BREAKER_OPEN → not reachable in this journey (documented)

`BREAKER_OPEN` is a `nodeStats.failureClass` enum value produced only when a node carries a circuit-breaker policy that trips. **`n_update` declares no breaker policy**, and `ErrorClass` (the only thing a `cap.*.response.v1` can carry) has just `TRANSIENT / PERMANENT / AMBIGUOUS` — there is no `BREAKER_OPEN` wire value to publish. **There is no permutation that yields `failureClass=BREAKER_OPEN` for employee-lwd-update.** (To exercise BREAKER_OPEN, use a journey/node that declares a breaker, e.g. in the loan-origination family — out of scope for this section.)

---

### P8 — Idempotency: re-drop of identical file content

**Entry (full-stack):** drop the exact P1 CSV a second time into `demo/batch-inbox/` (under any new filename — `run-demo2.sh` does this).

**Drive to outcome:** none — the file edge and engine both refuse the duplicate on two independent guards:
1. **Ledger:** `batchId = "batch-"+sha(content)` is unchanged, `ledger.contains(hash)` → logged `batch.skip.already-processed`, file archived to `batch-inbox/processed/`, **zero envelopes published**.
2. **Engine `insertIfAbsent`:** even if the ledger file were deleted, the per-record `correlationId=batchId-rN` is unchanged → `instanceId=ji-batchId-rN` already exists → the redelivery loses the insert, is logged `journey.start.duplicate`, and is **dropped** (does not resume, does not double-run).

**Expected result:** no new runs. `GET /ops/runs/search?key=batch-abc123def456` still returns exactly the original **5** runs (unchanged status/count) — a re-drop cannot double-book.

---

### P9 — Idempotency: duplicate Kafka resend (same correlationId)

**Entry (Kafka manual):** produce the **exact P2 message a second time** to `orig.employee-lwd-update.v1` — same key `batch-manual-001-r1`, same body (same `correlationId`).

**Drive to outcome:** none. `instanceId=ji-batch-manual-001-r1` already exists → `store.insertIfAbsent` loses → engine logs `journey.start.duplicate` and drops. No second dispatch to `cap.fusion-hcm.request.v1`.

**Expected result:** still exactly **one** run for `batch-manual-001-r1` (whatever terminal it already reached). `GET /ops/runs/search?key=batch-manual-001-r1` returns a single `RunSummaryDto` (the search returns newest-first and could list multiple only if genuinely distinct runs existed — here it stays 1).

---

### P10 — Unknown / unmapped `type` → fail-closed to DLQ

**Entry (Kafka manual):** topic `orig.employee-lwd-update.v1`, key `lwd-badtype-1`, value as P2 but with an unmapped `type`:

```json
{
  "transactionId": "lwd-badtype-1-t",
  "schemaVersion": "file-demo.v1",
  "source": "FILE_DEMO",
  "type": "EMPLOYEE_LWD_BOGUS",
  "notificationId": "batch-badtype-1",
  "orgId": "HR-DEMO",
  "sfdcRecordId": "EMP-900",
  "applicationRef": "batch-badtype-1/EMP-900",
  "correlationId": "lwd-badtype-1",
  "originalCorrelationId": "lwd-badtype-1",
  "payloadContentType": "application/json",
  "occurredAt": "2026-07-03T10:00:00Z",
  "payload": { "employeeId": "EMP-900", "lastWorkingDay": "2026-07-31" }
}
```

**Drive to outcome:** none — `type="EMPLOYEE_LWD_BOGUS"` has no `type-to-journey` row. `JourneyRegistry.resolveForType` throws `UnroutableTypeException` (fail-closed A2, never a default journey).

**Expected result:** message is treated as poison and dead-lettered to **`orig.employee-lwd-update.v1.dlq`** (source topic + `.dlq` suffix). **No run is started** — `GET /ops/runs/search?key=lwd-badtype-1` returns an empty list. Consume `orig.employee-lwd-update.v1.dlq` in Kafka UI to confirm the poisoned envelope landed.

---

### P11 — Undeserializable message → poison → DLQ

**Entry (Kafka manual):** topic `orig.employee-lwd-update.v1`, key `lwd-poison-1`, value = a body the consumer cannot deserialize into `Map<String,Object>`, e.g. non-JSON:

```
not-a-json-envelope
```

**Drive to outcome:** none — `OriginationConsumer` fails to deserialize → `PoisonMessageException`.

**Expected result:** dead-lettered to **`orig.employee-lwd-update.v1.dlq`**; no run started; `GET /ops/runs/search?key=lwd-poison-1` empty. Verify via the DLQ topic.

---

### P12 — Structurally malformed file → whole file quarantined, zero runs

**Entry (full-stack — file drop):** a file whose **header is wrong** or a row has the wrong field count — either quarantines the entire file (`CsvBatchParser` throws `MalformedBatchFileException`). Examples:

Wrong header:
```
emp,lwd
EMP-001,2026-07-31
```

Wrong field count (3 fields on row 2):
```
employeeId,lastWorkingDay
EMP-001,2026-07-31,extra
```

**Drive to outcome:** none — parse fails before any envelope is built.

**Expected result:** log `batch.quarantined batchId=… reason=…`, hash recorded in the ledger (so the poller never loops), file moved to `batch-inbox/quarantine/`. **Zero runs started** — never half-run. Ops shows no runs for this batchId.

---

### P13 — Empty / header-only file → skipped, zero runs

**Entry (full-stack — file drop):** an empty file, or header-only:

```
employeeId,lastWorkingDay
```

**Drive to outcome:** none — `records.isEmpty()`.

**Expected result:** log `batch.empty batchId=…`, hash ledgered, file archived to `batch-inbox/processed/`, **zero runs started** (production shape would email an alert; that adapter is census-gated). Distinct from P12: P13 is a *valid empty* batch (archived to `processed/`), P12 is a *poison* file (archived to `quarantine/`).

---

### P14 — Stuck run + sweeper force-fail (no Fusion response)

**Entry (full-stack):** drop a valid single-record file (as P2) **with the `fusion-hcm` capability stopped** so nothing consumes `cap.fusion-hcm.request.v1`, e.g. `correlationId="batch-stuck-1-r1"`, `notificationId="batch-stuck-1"`.

**Drive to outcome — engine-only:** publish the P2 origination envelope to `orig.employee-lwd-update.v1` and then **publish nothing** to `cap.fusion-hcm.response.v1`. The run dispatches `n_update` and waits with no response.

**Expected result — two observable phases:**
1. **Stuck window (~840 s to 900 s):** store state stays `RUNNING` → ops status **`RUNNING`**, but `stuck:true` (RUNNING and `startedAt ≤ now − 840s`) and `sweepDeadline = startedAt + 900s`. Surfaced by the `stuckOnly` filter / `stuckCount`.
2. **Force-failed at 900 s:** the liveness sweeper (`fixedDelay 60000 ms`, `run-budget 900s`) publishes an ERROR `JourneyDecision` with `terminalNodeId="__timeout__"`, `outcome=ERROR`, `loanId=null`, marks `sfdcNotified=SENT`, `fail("__timeout__", ERROR)` → store `FAILED`, emits ops event `run.sweptTimeout`. Ops status becomes **`FAILED_SFDC_NOTIFIED`** with `terminalNodeId="__timeout__"`, `terminalOutcome="ERROR"`.

**Verify (stuck phase):**
```bash
curl -s -H 'X-Ops-Token: dev-ops-token' -H 'X-User-Id: ops.analyst@bank' \
  "http://localhost:8082/ops/runs?journeyKey=employee-lwd-update&stuckOnly=true"
```
returns the run with `stuck:true` and a `sweepDeadline`. After ~15 min, `GET /ops/runs/search?key=batch-stuck-1-r1` → `status:"FAILED_SFDC_NOTIFIED"`, detail `terminalNodeId:"__timeout__"`.

---

### Maker-checker: promoting the employee-lwd-update journey via the registry

The demo runs this journey from the **classpath** (`journey-source=classpath`), so maker-checker is not on the live path here. It applies only if the engine is switched to `journey-source=registry`. The full lifecycle for this journey key, and every error arm, using the registry API (`http://localhost:8104/api/v1`, `X-Registry-Token: dev-registry-token`):

**Happy lifecycle** (config below is the employee-lwd-update graph; the server overwrites `config.journeyKey`/`config.version`):

```bash
REG=http://localhost:8104/api/v1
TOK='X-Registry-Token: dev-registry-token'

# 1. create journey (maker) -> 201
curl -i -X POST $REG/journeys -H "$TOK" -H 'X-User-Id: maker@bank' -H 'Content-Type: application/json' \
  -d '{"key":"employee-lwd-update","name":"Employee LWD Update","businessLine":"HR","product":"LWD","partner":null}'

# 2. create draft v1 (maker) -> 201 {status:"draft"}
curl -i -X POST $REG/journeys/employee-lwd-update/versions -H "$TOK" -H 'X-User-Id: maker@bank' -H 'Content-Type: application/json' \
  -d '{"config":{"startNodeId":"n_update","nodes":[{"id":"n_update","type":"task","capability":"fusion-hcm","operation":"updateEmployee","input":"{ employeeId: context.employeeId, lastWorkingDay: context.lastWorkingDay }","output":"context.fusion","next":["n_done"]},{"id":"n_done","type":"terminal","action":"push_decision_to_channel","emit":["EmployeeLwdUpdated"],"status":"completed"}]},"note":"first cut"}'

# 3. validate (no actor) -> 200
curl -i -X POST $REG/journeys/employee-lwd-update/versions/1/validate -H "$TOK"

# 4. submit (maker) -> 200 {status:"pendingApproval"}
curl -i -X POST $REG/journeys/employee-lwd-update/versions/1/submit -H "$TOK" -H 'X-User-Id: maker@bank'

# 5. approve = PUBLISH (different actor = checker) -> 200 {status:"published", approverId:"checker@bank"}
curl -i -X POST $REG/journeys/employee-lwd-update/versions/1/approve -H "$TOK" -H 'X-User-Id: checker@bank'
```

**403 self-approve** (`error:"FORBIDDEN"`): the author tries to approve/reject their own version:
```bash
curl -i -X POST $REG/journeys/employee-lwd-update/versions/1/approve -H "$TOK" -H 'X-User-Id: maker@bank'
# -> 403 {"error":"FORBIDDEN","message":"maker-checker: author 'maker@bank' may not approve/reject their own version","issues":[]}
```

**409 conflicts** (`error:"CONFLICT"`):
- Second editable draft while v1 is DRAFT/PENDING: `POST /journeys/employee-lwd-update/versions` → `"journey 'employee-lwd-update' already has an editable version (v1)"`.
- Duplicate journey: re-`POST /journeys` with key `employee-lwd-update` → `"journey 'employee-lwd-update' already exists"`.
- `saveDraft` on a non-DRAFT (e.g. `PUT .../versions/1` after publish) → `"version 1 of 'employee-lwd-update' is PUBLISHED — published/rejected/pending versions are immutable"`.
- `submit` on non-DRAFT → `"only a DRAFT can be submitted (version 1 is PUBLISHED)"`.
- `approve`/`reject` on non-PENDING → `"only a PENDING_APPROVAL version can be approved/rejected (version 1 is PUBLISHED)"`.
- Lost checker race (concurrent approve+reject) → `"version 1 of 'employee-lwd-update' was already finalized by another checker"`.

**422 validation** (`error:"VALIDATION_FAILED"`):
- Bad key on create (not lowercase kebab), e.g. `"key":"Employee_LWD"` → `"journey key must be lowercase kebab-case ([a-z0-9-]), e.g. 'loan-origination'"`, `issues:[]`.
- Submit an empty/invalid graph (draft `config:{}` then submit) → 422 `"journey 'employee-lwd-update' v1 fails validation"` with `issues:[{"code":"emptyDag","severity":"error",...}]` populated.

**401**: missing/invalid `X-Registry-Token` → `"invalid or missing X-Registry-Token"`; missing `X-User-Id` on any write op → `"X-User-Id header is required for this operation"`.

---

### Ops verification — list, filters, exact search, detail, stuckOnly

All calls: `OPS=http://localhost:8082/ops`, headers `-H 'X-Ops-Token: dev-ops-token' -H 'X-User-Id: ops.analyst@bank'`.

**List (defaults, page 0 size 50, `startedAt` DESC):**
```bash
curl -s $H "$OPS/runs"
```

**Filter by journeyKey** (all runs of this journey):
```bash
curl -s $H "$OPS/runs?journeyKey=employee-lwd-update"
```

**Filter by status** — every value that this journey can produce:
```bash
curl -s $H "$OPS/runs?journeyKey=employee-lwd-update&status=COMPLETED_APPROVED"   # the well-formed rows
curl -s $H "$OPS/runs?journeyKey=employee-lwd-update&status=FAILED_SFDC_NOTIFIED" # bad-date / transport / timeout
curl -s $H "$OPS/runs?journeyKey=employee-lwd-update&status=FAILED_NOTIFY_PENDING"# only if ERROR-notify unconfirmed
curl -s $H "$OPS/runs?journeyKey=employee-lwd-update&status=RUNNING"              # in-flight (incl. stuck)
```
This journey has no decline branch, so **`COMPLETED_DECLINED` never appears** for it. Unknown status → 400 `{"error":"BAD_REQUEST","message":"unknown status 'BOGUS' (allowed: [...])"}`:
```bash
curl -i $H "$OPS/runs?status=BOGUS"
```

**Time-window filters** (`since`/`until`, ISO-8601; bad format → 400):
```bash
curl -s $H "$OPS/runs?journeyKey=employee-lwd-update&since=2026-07-03T00:00:00Z&until=2026-07-04T00:00:00Z"
```

**stuckOnly** (only RUNNING runs past the ~840 s threshold — see P14):
```bash
curl -s $H "$OPS/runs?journeyKey=employee-lwd-update&stuckOnly=true"
```

**Pagination:**
```bash
curl -s $H "$OPS/runs?journeyKey=employee-lwd-update&page=0&size=25"
```

**Exact search** (`key` required, blank → 400) across `runId | correlationId | notificationId | sfdcRecordId`:
```bash
# by batchId (=notificationId) -> ALL records of the batch
curl -s $H "$OPS/runs/search?key=batch-abc123def456"
# by per-record correlationId -> ONE run
curl -s $H "$OPS/runs/search?key=batch-abc123def456-r4"
# by employeeId (=sfdcRecordId) -> the matching run(s)
curl -s $H "$OPS/runs/search?key=EMP-004"
```

**Detail** (`GET /ops/runs/{runId}` → `RunDetailDto`; unknown id → 404 empty body). For the P3/P4 failure this shows the load-bearing fields:
```bash
curl -s $H "$OPS/runs/<runId>"
```
Expect for a failed record: `status:"FAILED_SFDC_NOTIFIED"`, `sfdcNotified:"SENT"`, `terminalNodeId:"n_update"`, `terminalOutcome:"ERROR"`, `transitions:[{seq:1,nodeId:"n_update",status:"DISPATCHED",...},{...,status:"FAILED",...}]`, `nodeStats:[{nodeId:"n_update",attempts:1,failureClass:"PERMANENT"|"TRANSIENT"|"AMBIGUOUS"}]`, `dlqTopicRef` populated, `compensationOf:null`, `compensationPending:[]` (this journey has no compensation). For a completed record: `status:"COMPLETED_APPROVED"`, `terminalNodeId:"n_done"`, `terminalOutcome:"APPROVED"`, `dlqTopicRef:null`, `sweepDeadline:null`.


---

<a id="sec-registry"></a>

## Control plane: journey-registry maker-checker (Postman)

This section is exercised **entirely over REST** against the `journey-registry` service. Unlike the engine journeys, the registry has **no capabilities, no WireMock, and no Kafka steering** — every outcome below is driven purely by the **request sequence + headers**, so each permutation gives the exact precondition state instead of a vendor lever or a `cap.*.response.v1` message. The registry does **not** start engine runs, so outcomes are verified with the registry's own `GET` endpoints (list / get-version / get-published), **not** `GET /ops/runs/search`.

### Environment / preconditions

| Var | Value (local/dev) |
|---|---|
| Base URL | `http://localhost:8104/api/v1` |
| `X-Registry-Token` | `dev-registry-token` (env `REGISTRY_AUTH_TOKEN`; fail-closed, service won't start if unset) |
| `X-User-Id` | any non-blank id; use `maker@bank` (author) and `checker@bank` (approver) |

Header rules (verbatim from `RegistryConfiguration`):
- `X-Registry-Token` — required on **every** `/api/*` call (servlet filter, before the controller; `OPTIONS` exempt).
- `X-User-Id` — required **only on writes/lifecycle**: create journey, create draft, save draft, submit, approve, reject. **Not** required on any `GET` or on `/validate`.
- `Content-Type: application/json` on bodied requests.

Status wire names in `VersionDto.status`: `draft` | `pendingApproval` | `published` | `rejected`. All error bodies share `ErrorBody { "error", "message", "issues":[] }` where `error` is the exception `Kind` name; `issues[]` is non-empty only on 422 graph-gate failures.

> Run every permutation against a **freshly started** registry (empty store) unless the permutation explicitly reuses state from an earlier step. Where a permutation depends on prior state, the exact prerequisite calls are listed.

---

### 401 — missing or invalid X-Registry-Token (filter, before controller)

- **Entry**: any `/api/*` call without a valid token. Example:

  **`GET /api/v1/journeys`**

  | Header | Value |
  |---|---|
  | `X-Registry-Token` | *(omitted, or any wrong value)* |

- **Drive to outcome**: token filter rejects before the controller runs; independent of store state.
- **Expected result**: **HTTP 401**
  ```json
  { "error": "UNAUTHENTICATED", "message": "invalid or missing X-Registry-Token", "issues": [] }
  ```
  **Verify**: repeat the identical call with `X-Registry-Token: dev-registry-token` → 200. The `X-Ops-Token`/`dev-ops-token` secret does **not** authorize the registry (D14: deliberately separate secrets).

---

### 401 — missing or blank X-User-Id on a write op

- **Entry**: any lifecycle write with a valid token but no actor. Example:

  **`POST /api/v1/journeys`**

  | Header | Value |
  |---|---|
  | `X-Registry-Token` | `dev-registry-token` |
  | `X-User-Id` | *(omitted or blank)* |
  | `Content-Type` | `application/json` |

  ```json
  { "key": "loan-origination", "name": "Loan Origination", "businessLine": "RETAIL", "product": "PL", "partner": null }
  ```

- **Drive to outcome**: actor-header check fires for write ops only (create/draft/save/submit/approve/reject). A `GET` with no `X-User-Id` succeeds.
- **Expected result**: **HTTP 401**
  ```json
  { "error": "UNAUTHENTICATED", "message": "X-User-Id header is required for this operation", "issues": [] }
  ```
  **Verify**: resend with `X-User-Id: maker@bank` → 201.

---

### Happy lifecycle — step 1: create journey (201)

- **Entry**: **`POST /api/v1/journeys`**

  | Header | Value |
  |---|---|
  | `X-Registry-Token` | `dev-registry-token` |
  | `X-User-Id` | `maker@bank` |
  | `Content-Type` | `application/json` |

  ```json
  { "key": "loan-origination", "name": "Loan Origination", "businessLine": "RETAIL", "product": "PL", "partner": null }
  ```

- **Drive to outcome**: key must be unique and lowercase kebab-case. Store empty for `loan-origination`.
- **Expected result**: **HTTP 201** `JourneyDto` with `id == key == "loan-origination"`, `activeVersion: null` (no published pointer yet), `versions: []`.
  **Verify**: `GET /api/v1/journeys/loan-origination` (no actor) → 200, same body.

---

### Happy lifecycle — step 2: create draft v1 (201)

- **Entry**: **`POST /api/v1/journeys/loan-origination/versions`**

  | Header | Value |
  |---|---|
  | `X-Registry-Token` | `dev-registry-token` |
  | `X-User-Id` | `maker@bank` |
  | `Content-Type` | `application/json` |

  ```json
  { "config": { "nodes": [], "edges": [] }, "note": "first cut" }
  ```

- **Drive to outcome**: no editable (DRAFT/PENDING_APPROVAL) version may already exist for the key. `config` is a **real JSON object node** (not an escaped string); an omitted `config` is treated as `{}`. Server overwrites `config.journeyKey` and `config.version` with the path/allocated values.
- **Expected result**: **HTTP 201** `VersionDto { "journeyKey":"loan-origination", "version":1, "status":"draft", "authorId":"maker@bank", "approverId":null, "note":"first cut", "config":{…} }`.
  **Verify**: `GET /api/v1/journeys/loan-origination/versions/1` → 200 with `config` included (single-version reads include config; list reads null it out).

---

### Happy lifecycle — step 3: save draft (200)

- **Entry**: **`PUT /api/v1/journeys/loan-origination/versions/1`**

  | Header | Value |
  |---|---|
  | `X-Registry-Token` | `dev-registry-token` |
  | `X-User-Id` | `maker@bank` |
  | `Content-Type` | `application/json` |

  ```json
  { "config": { "nodes": [{ "id": "start" }], "edges": [] }, "note": "wip" }
  ```

- **Drive to outcome**: version 1 must be in `draft` status (only DRAFT is mutable). Overwrites the stored config.
- **Expected result**: **HTTP 200** `VersionDto { version:1, status:"draft", note:"wip", config:{…updated…} }`, `updatedAt` advanced.
  **Verify**: `GET .../versions/1` → 200 shows the new config.

---

### Happy lifecycle — step 4: validate (200, no actor)

- **Entry**: **`POST /api/v1/journeys/loan-origination/versions/1/validate`**

  | Header | Value |
  |---|---|
  | `X-Registry-Token` | `dev-registry-token` |

  *(no body, no `X-User-Id`)*

- **Drive to outcome**: read-only server-side validation of the stored §7 graph; does not change status.
- **Expected result**: **HTTP 200** `ValidationResultDto { "issues":[ … ] }`. Each issue = `{ code, severity ("error"|"warning"), message, nodeId }`. A graph with problems returns e.g. `{ "issues":[{ "code":"emptyDag","severity":"error","message":"…","nodeId":null }] }`; validate itself is **always 200** (it reports, it does not gate).
  **Verify**: 200 regardless of issue content — this is the pre-flight the maker runs before submit.

---

### Happy lifecycle — step 5: submit DRAFT → PENDING_APPROVAL (200)

- **Entry**: **`POST /api/v1/journeys/loan-origination/versions/1/submit`**

  | Header | Value |
  |---|---|
  | `X-Registry-Token` | `dev-registry-token` |
  | `X-User-Id` | `maker@bank` |

- **Drive to outcome**: version 1 is `draft` **and** the §7 graph gate returns zero `severity:"error"` issues. (A graph with an error → 422, see the 422 permutation below.) Use a config that passes validation.
- **Expected result**: **HTTP 200** `VersionDto { version:1, status:"pendingApproval" }`.
  **Verify**: `GET .../versions/1` → `status:"pendingApproval"`. `activeVersion` on the journey is still `null` (not published yet).

---

### Happy lifecycle — step 6: approve = PUBLISH PENDING → PUBLISHED (200, checker)

- **Entry**: **`POST /api/v1/journeys/loan-origination/versions/1/approve`**

  | Header | Value |
  |---|---|
  | `X-Registry-Token` | `dev-registry-token` |
  | `X-User-Id` | `checker@bank` |

- **Drive to outcome**: version 1 is `pendingApproval` **and** the actor `checker@bank` ≠ the version's `authorId` (`maker@bank`). **There is no separate `/publish` endpoint — approve IS publish**: it flips PENDING→PUBLISHED and moves the published pointer.
- **Expected result**: **HTTP 200** `VersionDto { version:1, status:"published", approverId:"checker@bank" }`.
  **Verify (three ways)**:
  - `GET /api/v1/journeys/loan-origination` → `activeVersion: 1`.
  - `GET /api/v1/published-journeys` → includes `PublishedConfigDto { journeyKey:"loan-origination", version:1, config:{…} }`.
  - `GET /api/v1/published-journeys/loan-origination/versions/1` → 200 pinned fetch (the exact artifact the engine bootstraps in `registry` mode).

---

### Happy lifecycle — reject arm: PENDING → REJECTED (200, checker)

*(Alternate terminal for the maker-checker cycle. Prereq: a second version `v2` exists in `pendingApproval`, authored by `maker@bank` — create draft v2, submit it. v1 is already published from the step above, so v2 is the single editable version.)*

- **Entry**: **`POST /api/v1/journeys/loan-origination/versions/2/reject`**

  | Header | Value |
  |---|---|
  | `X-Registry-Token` | `dev-registry-token` |
  | `X-User-Id` | `checker@bank` |
  | `Content-Type` | `application/json` |

  ```json
  { "comment": "graph has an orphan node" }
  ```
  *(body is optional and may be omitted entirely)*

- **Drive to outcome**: version 2 is `pendingApproval` and actor ≠ author.
- **Expected result**: **HTTP 200** `VersionDto { version:2, status:"rejected" }`. The published pointer stays at v1 (`activeVersion:1`); a REJECTED version is terminal/immutable.
  **Verify**: `GET .../versions/2` → `status:"rejected"`. Because rejecting freed the editable slot, a new `POST .../versions` now succeeds (v3 draft).

---

### 403 — self-approve (author approves own version)

- **Entry**: **`POST /api/v1/journeys/loan-origination/versions/1/approve`**

  | Header | Value |
  |---|---|
  | `X-Registry-Token` | `dev-registry-token` |
  | `X-User-Id` | `maker@bank` |

  *(Prereq: v1 in `pendingApproval`, `authorId:"maker@bank"`.)*

- **Drive to outcome**: actor `X-User-Id` **equals** the version's `authorId`. Maker-checker separation-of-duty rule.
- **Expected result**: **HTTP 403**
  ```json
  { "error": "FORBIDDEN", "message": "maker-checker: author 'maker@bank' may not approve/reject their own version", "issues": [] }
  ```
  **Verify**: `GET .../versions/1` → still `status:"pendingApproval"` (unchanged). Retry with `X-User-Id: checker@bank` → 200 published.

---

### 403 — self-reject (author rejects own version)

- **Entry**: **`POST /api/v1/journeys/loan-origination/versions/1/reject`**

  | Header | Value |
  |---|---|
  | `X-Registry-Token` | `dev-registry-token` |
  | `X-User-Id` | `maker@bank` |
  | `Content-Type` | `application/json` |

  ```json
  { "comment": "changed my mind" }
  ```
  *(Prereq: v1 in `pendingApproval`, `authorId:"maker@bank"`.)*

- **Drive to outcome**: the 403 self-review guard covers **both** approve and reject — the author cannot finalize their own version either way.
- **Expected result**: **HTTP 403**
  ```json
  { "error": "FORBIDDEN", "message": "maker-checker: author 'maker@bank' may not approve/reject their own version", "issues": [] }
  ```
  **Verify**: `GET .../versions/1` → still `pendingApproval`.

---

### 409 — second draft while an editable version exists (single-editable rule)

- **Entry**: **`POST /api/v1/journeys/loan-origination/versions`**

  | Header | Value |
  |---|---|
  | `X-Registry-Token` | `dev-registry-token` |
  | `X-User-Id` | `maker@bank` |
  | `Content-Type` | `application/json` |

  ```json
  { "config": {} }
  ```
  *(Prereq: v1 already exists in `draft` **or** `pendingApproval`.)*

- **Drive to outcome**: a key may have at most one editable (DRAFT or PENDING_APPROVAL) version at a time.
- **Expected result**: **HTTP 409**
  ```json
  { "error": "CONFLICT", "message": "journey 'loan-origination' already has an editable version (v1)", "issues": [] }
  ```
  **Verify**: `GET /api/v1/journeys/loan-origination/versions` → only v1 exists (no v2 created). After v1 is published or rejected, the same POST succeeds.

---

### 409 — duplicate journey (create existing key)

- **Entry**: **`POST /api/v1/journeys`**

  | Header | Value |
  |---|---|
  | `X-Registry-Token` | `dev-registry-token` |
  | `X-User-Id` | `maker@bank` |
  | `Content-Type` | `application/json` |

  ```json
  { "key": "loan-origination", "name": "Loan Origination", "businessLine": "RETAIL", "product": "PL", "partner": null }
  ```
  *(Prereq: `loan-origination` already created.)*

- **Drive to outcome**: create is **not idempotent** — a resend of an already-created key is a conflict, not a silent no-op. (This is the registry's equivalent of a "duplicate resend": same key → 409, never a double-create.)
- **Expected result**: **HTTP 409**
  ```json
  { "error": "CONFLICT", "message": "journey 'loan-origination' already exists", "issues": [] }
  ```
  **Verify**: `GET /api/v1/journeys` → the key appears exactly once.

---

### 409 — saveDraft on a non-DRAFT version (immutability)

- **Entry**: **`PUT /api/v1/journeys/loan-origination/versions/1`**

  | Header | Value |
  |---|---|
  | `X-Registry-Token` | `dev-registry-token` |
  | `X-User-Id` | `maker@bank` |
  | `Content-Type` | `application/json` |

  ```json
  { "config": { "nodes": [{ "id": "tamper" }], "edges": [] }, "note": "too late" }
  ```
  *(Prereq: v1 in `pendingApproval`, `published`, or `rejected`.)*

- **Drive to outcome**: only a DRAFT is mutable; PENDING/PUBLISHED/REJECTED are immutable. `<STATUS>` in the message is the current uppercase status of the version.
- **Expected result**: **HTTP 409**
  ```json
  { "error": "CONFLICT", "message": "version 1 of 'loan-origination' is PUBLISHED — published/rejected/pending versions are immutable", "issues": [] }
  ```
  **Verify**: `GET .../versions/1` → config unchanged.

---

### 409 — submit on a non-DRAFT version

- **Entry**: **`POST /api/v1/journeys/loan-origination/versions/1/submit`**

  | Header | Value |
  |---|---|
  | `X-Registry-Token` | `dev-registry-token` |
  | `X-User-Id` | `maker@bank` |

  *(Prereq: v1 already in `pendingApproval` (or `published`/`rejected`).)*

- **Drive to outcome**: submit is only valid on a DRAFT.
- **Expected result**: **HTTP 409**
  ```json
  { "error": "CONFLICT", "message": "only a DRAFT can be submitted (version 1 is PENDING_APPROVAL)", "issues": [] }
  ```
  **Verify**: `GET .../versions/1` → status unchanged.

---

### 409 — approve/reject on a non-PENDING version

- **Entry**: **`POST /api/v1/journeys/loan-origination/versions/1/approve`** (same message applies to `/reject`)

  | Header | Value |
  |---|---|
  | `X-Registry-Token` | `dev-registry-token` |
  | `X-User-Id` | `checker@bank` |

  *(Prereq: v1 in `draft`, `published`, or `rejected` — anything but `pendingApproval`.)*

- **Drive to outcome**: approve/reject require `PENDING_APPROVAL`. (Even with a valid checker actor, the wrong lifecycle state is a 409, evaluated independently of the 403 self-review guard.)
- **Expected result**: **HTTP 409**
  ```json
  { "error": "CONFLICT", "message": "only a PENDING_APPROVAL version can be approved/rejected (version 1 is DRAFT)", "issues": [] }
  ```
  **Verify**: `GET .../versions/1` → status unchanged.

---

### 409 — lost checker race (concurrent approve + reject; CAS loser)

- **Entry**: fire two lifecycle finalizers at the **same** `pendingApproval` version near-simultaneously (e.g. two Postman tabs / a Runner with 2 iterations):
  - Tab A: **`POST /api/v1/journeys/loan-origination/versions/1/approve`**, `X-User-Id: checker@bank`
  - Tab B: **`POST /api/v1/journeys/loan-origination/versions/1/reject`**, `X-User-Id: checker2@bank`

  Both with `X-Registry-Token: dev-registry-token`.

- **Drive to outcome**: version 1 in `pendingApproval`, both actors ≠ author. One CAS wins and finalizes; the other loses the compare-and-set.
- **Expected result**: one call → **HTTP 200** (published or rejected); the loser → **HTTP 409**
  ```json
  { "error": "CONFLICT", "message": "version 1 of 'loan-origination' was already finalized by another checker", "issues": [] }
  ```
  **Verify**: `GET .../versions/1` → exactly one terminal status (`published` XOR `rejected`), matching whichever call returned 200.

---

### 422 — bad journey key on create (kebab-case gate)

- **Entry**: **`POST /api/v1/journeys`**

  | Header | Value |
  |---|---|
  | `X-Registry-Token` | `dev-registry-token` |
  | `X-User-Id` | `maker@bank` |
  | `Content-Type` | `application/json` |

  ```json
  { "key": "Loan_Origination", "name": "Bad Key", "businessLine": "RETAIL", "product": "PL", "partner": null }
  ```

- **Drive to outcome**: key must match `[a-z0-9][a-z0-9-]*` (lowercase kebab-case). `Loan_Origination` has uppercase + underscore.
- **Expected result**: **HTTP 422**
  ```json
  { "error": "VALIDATION_FAILED", "message": "journey key must be lowercase kebab-case ([a-z0-9-]), e.g. 'loan-origination'", "issues": [] }
  ```
  (`issues[]` empty on the key-format failure.)
  **Verify**: `GET /api/v1/journeys` → no journey with that key was created.

---

### 422 — submit fails the graph gate (issues[] populated)

- **Entry**: **`POST /api/v1/journeys/loan-origination/versions/1/submit`**

  | Header | Value |
  |---|---|
  | `X-Registry-Token` | `dev-registry-token` |
  | `X-User-Id` | `maker@bank` |

  *(Prereq: v1 is `draft` but its config is a graph with a `severity:"error"` issue — e.g. an empty DAG saved via step 2 with `"config":{"nodes":[],"edges":[]}`, or a graph with an orphan node. Confirm with `/validate` first — it will report the error.)*

- **Drive to outcome**: the §7 validator returns at least one `severity:"error"` issue at submit time, which gates the DRAFT→PENDING transition.
- **Expected result**: **HTTP 422**, and unlike the key gate the **`issues[]` array is populated** with the findings:
  ```json
  { "error": "VALIDATION_FAILED", "message": "journey 'loan-origination' v1 fails validation",
    "issues": [ { "code": "emptyDag", "severity": "error", "message": "…", "nodeId": null } ] }
  ```
  **Verify**: `GET .../versions/1` → still `status:"draft"` (submit was rejected, version not advanced). Fix the graph via `PUT` (save draft), re-run `/validate` (200, no error issues), then resubmit → 200 `pendingApproval`.

---

### 422 — config not a JSON object / not parseable (on draft create or save)

- **Entry**: **`POST /api/v1/journeys/loan-origination/versions`** (same applies to `PUT .../versions/{version}` and to `submit`)

  | Header | Value |
  |---|---|
  | `X-Registry-Token` | `dev-registry-token` |
  | `X-User-Id` | `maker@bank` |
  | `Content-Type` | `application/json` |

  ```json
  { "config": "this-is-a-string-not-an-object", "note": "bad config" }
  ```

- **Drive to outcome**: `config` must be a JSON **object** node. A string/array/non-parseable value fails the config gate.
- **Expected result**: **HTTP 422**
  ```json
  { "error": "VALIDATION_FAILED", "message": "…",
    "issues": [ { "code": "emptyDag", "severity": "error", "message": "…", "nodeId": null } ] }
  ```
  **Verify**: `GET .../versions` → no version was created (create path) / config unchanged (save path).

---

### 404 — unknown journey or version

- **Entry** (any of):
  - **`GET /api/v1/journeys/does-not-exist`**
  - **`GET /api/v1/journeys/loan-origination/versions/999`**
  - **`POST /api/v1/journeys/loan-origination/versions/999/submit`** (`X-User-Id: maker@bank`)

  | Header | Value |
  |---|---|
  | `X-Registry-Token` | `dev-registry-token` |

- **Drive to outcome**: the key/version does not exist in the store.
- **Expected result**: **HTTP 404**
  ```json
  { "error": "NOT_FOUND", "message": "…", "issues": [] }
  ```

---

### 404 — published version never published (pinned fetch)

- **Entry**: **`GET /api/v1/published-journeys/loan-origination/versions/2`**

  | Header | Value |
  |---|---|
  | `X-Registry-Token` | `dev-registry-token` |

  *(Prereq: v2 exists but is `draft`/`pendingApproval`/`rejected` — never approved. v1 may be published.)*

- **Drive to outcome**: `published-journeys/{key}/versions/{version}` resolves only versions whose status is `published`. A version that exists but was never published reads as 404 (distinct from a version that never existed — same status code, this is the "never crossed the pointer" case).
- **Expected result**: **HTTP 404** `{ "error": "NOT_FOUND", … }`.
  **Verify**: `GET /api/v1/journeys/loan-origination/versions/2` → 200 (the version DOES exist) with its non-published status; only the **published** projection 404s.

---

### Reads — list journeys, no filters

- **Entry**: **`GET /api/v1/journeys`**

  | Header | Value |
  |---|---|
  | `X-Registry-Token` | `dev-registry-token` |

  *(no `X-User-Id` needed)*

- **Expected result**: **HTTP 200**, array of `JourneyDto`. Each carries `activeVersion` (published pointer or `null`) and `versions[]` (`VersionDto[]` with `config:null` in the list projection).
  **Verify**: created keys appear; each `activeVersion` matches the latest approved version.

---

### Reads — list journeys with each filter (exact-match)

- **Entry** (run each independently):
  - **`GET /api/v1/journeys?businessLine=RETAIL`**
  - **`GET /api/v1/journeys?product=PL`**
  - **`GET /api/v1/journeys?partner=CRED`**
  - Combined: **`GET /api/v1/journeys?businessLine=RETAIL&product=PL`**

  | Header | Value |
  |---|---|
  | `X-Registry-Token` | `dev-registry-token` |

- **Drive to outcome**: filters are optional and **exact-match** (not partial/case-folded). For a hit, the created journey's field must equal the query value verbatim (e.g. `businessLine:"RETAIL"`, `product:"PL"`, `partner:"CRED"`; the loan-origination fixture has `partner:null`, so `?partner=CRED` returns it filtered out).
- **Expected result**: **HTTP 200**, only journeys matching **all** supplied filters. A filter value with no match → `[]` (empty array, still 200).
  **Verify**: `?businessLine=RETAIL` returns the RETAIL journey; `?businessLine=CORPORATE` (unused) returns `[]`.

---

### Reads — get one journey + versions

- **Entry**: **`GET /api/v1/journeys/loan-origination`**

  | Header | Value |
  |---|---|
  | `X-Registry-Token` | `dev-registry-token` |

- **Expected result**: **HTTP 200** `JourneyDto` with `id == key`, `activeVersion` = published pointer, `versions[]` = all versions (`config:null` in this list projection).

---

### Reads — version list vs single version (config visibility)

- **Entry** (two calls, contrast the config field):
  - **`GET /api/v1/journeys/loan-origination/versions`** → list projection
  - **`GET /api/v1/journeys/loan-origination/versions/1`** → single-version projection

  | Header | Value |
  |---|---|
  | `X-Registry-Token` | `dev-registry-token` |

- **Expected result**: both **HTTP 200**. The **list** returns `VersionDto[]` with `config:null` on every entry; the **single-version** read returns the full `config` JSON node. Each `VersionDto` carries `status` (`draft`/`pendingApproval`/`published`/`rejected`), `authorId`, `approverId`, `note`, `createdAt`, `updatedAt`.
  **Verify**: confirm `config` is present on the single read and null in the list — this is the intended payload-size optimization.

---

### Reads — all published configs (engine bootstrap)

- **Entry**: **`GET /api/v1/published-journeys`**

  | Header | Value |
  |---|---|
  | `X-Registry-Token` | `dev-registry-token` |

- **Drive to outcome**: returns every currently-published version's config (this is what the engine loads at boot when `idfc.engine.journey-source=registry`).
- **Expected result**: **HTTP 200**, array of `PublishedConfigDto { journeyKey, version, config }`. Only PUBLISHED versions appear; drafts/pending/rejected are absent.
  **Verify**: after the happy-path approve, `loan-origination` v1 appears; after a subsequent approve of v2, the entry reflects the newly-pointed version.

---

### Reads — get a specific published version (pinned fetch)

- **Entry**: **`GET /api/v1/published-journeys/loan-origination/versions/1`**

  | Header | Value |
  |---|---|
  | `X-Registry-Token` | `dev-registry-token` |

  *(Prereq: v1 published via the approve step.)*

- **Expected result**: **HTTP 200** `PublishedConfigDto { journeyKey:"loan-origination", version:1, config:{…} }` — the exact pinned artifact the engine resolves per-run. (A never-published version here → 404, covered above.)

---

### Notes for this control plane (no ops-run verification)

- The registry writes **do not** produce engine runs, Kafka messages, DLQ traffic, or ops-status vocabulary. `GET /ops/runs/search?key=<id>` is **not** applicable here — it belongs to the engine/ops sections. Registry state is authoritative only via the `GET` endpoints above.
- The bridge to the engine is the **published pointer**: once a version reaches `published` (via approve), the engine in `registry` mode picks it up from `GET /api/v1/published-journeys`. Exercising an actual run from that published journey is the origination-engine section's concern, not this one.
- "Idempotency" in this control plane is **conflict-based, not silent**: re-creating a journey or opening a second editable draft returns 409 (never a double-write); there is no request-id dedupe key on registry writes.


---

<a id="sec-ops"></a>

## Control plane: ops read window /ops (Postman)

The ops read window is the `ops-query` library auto-configured **into the engine app** (`origination-journey`). It is **GET-only** and reads the engine's `OpsRunStore` (not the Kafka ops-events topic). Nothing here mutates state — every permutation is a query. The "Drive to outcome" for each query therefore describes **how to seed a run into the state the query is meant to surface**, then the exact call + expected body.

**Base:** `http://localhost:8082/ops` (host app `SERVER_PORT`, default `8082`).

**Headers — required on EVERY `/ops/*` call** (filter order `HIGHEST_PRECEDENCE+20`, `OPTIONS` exempt). Every call, allowed or refused, emits an `ops.audit` log line (ids only):

| Header | Local/dev value | Env for real deploys | Missing/blank |
|---|---|---|---|
| `X-Ops-Token` | `dev-ops-token` | `OPS_API_TOKEN` (fail-closed, no default) | 401 `{"error":"UNAUTHENTICATED","message":"invalid or missing X-Ops-Token"}` |
| `X-User-Id` | any non-blank, e.g. `ops.analyst@bank` | — | 401 `{"error":"UNAUTHENTICATED","message":"X-User-Id header is required for the ops API"}` |

The ops token is deliberately a **different secret from the registry token** (D14) — a valid `X-Registry-Token` does NOT authorize `/ops`.

---

### Seed fixtures used throughout this section

All permutations below reference runs of the **`loan-origination`** journey (nodes `n_customer → n_kyc → n_bureau → n_score → n_decide → {n_book → n_done | n_reject}`). Start a run by publishing to **topic `orig.sfdc.pl.v1`**, **key = `notificationId`**, value = the canonical envelope. `type:"PERSONAL_LOAN"` → journey `loan-origination`; `instanceId = "ji-" + correlationId`.

Fixture **A (APPROVED-bound)** — normal PAN, no negativeFlags → `correlationId=corr-appr-1`, run id derived `ji-corr-appr-1`:

```json
{
  "transactionId": "tx-appr-1",
  "schemaVersion": "sfdc-ingress.v1",
  "source": "SFDC",
  "type": "PERSONAL_LOAN",
  "notificationId": "ntf-appr-1",
  "orgId": "00D6D00000020HoUAI",
  "sfdcRecordId": "a0X-appr-1",
  "applicationRef": "APP-appr-1",
  "correlationId": "corr-appr-1",
  "originalCorrelationId": "corr-appr-1",
  "payloadRef": null,
  "payloadContentType": "application/json",
  "occurredAt": "2026-07-03T10:15:30Z",
  "payload": { "pan": "ABCDE1234F", "name": "Asha Rao", "amount": 500000, "tenureMonths": 36 }
}
```

Fixture **B (DECLINE-bound)** — same shape but the substring `LOW` in PAN forces bureauScore 540 < 700 → scoring `REJECTED`. Use `notificationId=ntf-decl-1`, `correlationId=corr-decl-1`, `sfdcRecordId=a0X-decl-1`, `applicationRef=APP-decl-1`, and `payload.pan = "LOWAB1234F"`.

**Engine-only manual steer** (no capabilities running): after starting a run, publish the capability OK responses in order to advance it. Each echoes the run's `journeyInstanceId` + the node's `nodeId`. The four upstream OK responses (reused by several permutations below) for `ji-corr-appr-1`:

```json
{ "journeyInstanceId": "ji-corr-appr-1", "correlationId": "corr-appr-1", "nodeId": "n_customer", "capabilityKey": "customer-party", "status": "OK", "result": { "crn": "CRN-ABCDE1234F", "customerId": "CUST-ABCDE1234F", "customerName": "Asha Rao", "customerStatus": "ACTIVE" }, "errorClass": null }
```
```json
{ "journeyInstanceId": "ji-corr-appr-1", "correlationId": "corr-appr-1", "nodeId": "n_kyc", "capabilityKey": "kyc", "status": "OK", "result": { "kycStatus": "VERIFIED", "kycRefId": "KYC-ABCDE1234F" }, "errorClass": null }
```
```json
{ "journeyInstanceId": "ji-corr-appr-1", "correlationId": "corr-appr-1", "nodeId": "n_bureau", "capabilityKey": "bureau", "status": "OK", "result": { "bureauResults": [ { "type": "CIBIL", "score": 780, "grade": "A", "reportId": "RPT-1", "source": "CIBIL", "fetchedAt": "2026-07-03T10:15:31Z" } ], "bureauScore": 780, "bureauGrade": "A", "reportId": "RPT-1" }, "errorClass": null }
```
```json
{ "journeyInstanceId": "ji-corr-appr-1", "correlationId": "corr-appr-1", "nodeId": "n_score", "capabilityKey": "scoring", "status": "OK", "result": { "decision": "APPROVED", "score": 780, "reasons": ["bureauScore 780 >= threshold 700", "fico=750"] }, "errorClass": null }
```

Publish these to `cap.customer-party.response.v1`, `cap.kyc.response.v1`, `cap.bureau.response.v1`, `cap.scoring.response.v1` respectively (message key = the `journeyInstanceId` or blank; only the JSON body's `journeyInstanceId`+`nodeId` correlate). These four are referenced as **"the four upstream OK responses"** below.

---

### List runs — defaults (no filters)

- **Entry** (REST): `GET /ops/runs`

  | Header | Value |
  |---|---|
  | `X-Ops-Token` | `dev-ops-token` |
  | `X-User-Id` | `ops.analyst@bank` |

  No body.

- **Drive to outcome**: any run(s) in the store. Full-stack: start Fixture A; all capabilities respond and it completes. Engine-only: start Fixture A, publish the four upstream OK responses + the `n_book` OK response (see COMPLETED_APPROVED below). No seeding is strictly required — an empty store returns an empty page, not an error.
- **Expected result**: **200** `PageDto`. Sort `startedAt` DESC, `runId` tiebreak. `page=0`, `size=50`, `totalItems`/`totalPages` reflect the store.
  ```jsonc
  { "items": [ /* RunSummaryDto[] newest-first */ ], "page": 0, "size": 50, "totalItems": 1, "totalPages": 1 }
  ```
  Each `RunSummaryDto` carries the server-computed `status`, `startedAt`, `endedAt`, `correlationId`, `notificationId`, `sfdcRecordId`, `stuck`, `sweepDeadline`.

---

### Filter — `status=RUNNING`

- **Entry**: `GET /ops/runs?status=RUNNING` (same headers as above; `status` is case-insensitive, trimmed).
- **Drive to outcome**:
  - Full-stack: start Fixture A, then **stop the `scoring` capability container** (or any one capability) so `n_score` never gets a response — the run parks at that node in state `RUNNING`. (With all caps up, a loan run completes in well under the 900s budget and won't show as RUNNING for long.)
  - Engine-only: start Fixture A and publish only the four upstream OK responses **through `n_bureau`** (i.e. skip the `n_score` response). The run sits `RUNNING` awaiting the `n_score` response. Simplest: start Fixture A and publish nothing — it parks `RUNNING` at `n_customer`.
- **Expected result**: **200** `PageDto` containing only runs whose computed `status == RUNNING`. Store state `RUNNING`, `endedAt: null`, `sweepDeadline = startedAt + 900s` (non-null while RUNNING), `stuck:false` until 840s elapse.
  - **Verify**: `GET /ops/runs/search?key=corr-appr-1` → the run summary shows `"status":"RUNNING"`.

---

### Filter — `status=COMPLETED_APPROVED`

- **Entry**: `GET /ops/runs?status=COMPLETED_APPROVED`
- **Drive to outcome** — take the APPROVED arm at `n_decide` (needs scoring `decision=="APPROVED"`) then `n_book` OK → `n_done` (`completed`):
  - Full-stack: start **Fixture A** (normal PAN, no `negativeFlags`) → bureauScore 780 ≥ 700 → scoring APPROVED → booking OK → terminal `n_done`.
  - Engine-only: start Fixture A, publish the four upstream OK responses (scoring `decision:"APPROVED"`), then publish the booking OK to `cap.lending-origination.response.v1`:
    ```json
    { "journeyInstanceId": "ji-corr-appr-1", "correlationId": "corr-appr-1", "nodeId": "n_book", "capabilityKey": "lending-origination", "status": "OK", "result": { "loanId": "LN-APP-appr-1", "status": "BOOKED" }, "errorClass": null }
    ```
- **Expected result**: **200** `PageDto` filtered to `COMPLETED_APPROVED`. Terminal node `n_done`, terminal outcome **APPROVED**, emit `LoanBooked`. Key transitions: `n_customer→n_kyc→n_bureau→n_score→n_decide(arm)→n_book→n_done` all `COMPLETED`. `endedAt` set, `sweepDeadline: null`, no DLQ.
  - **Verify**: `GET /ops/runs/search?key=corr-appr-1` → `"status":"COMPLETED_APPROVED"`; open `GET /ops/runs/{runId}` → `terminalNodeId:"n_done"`, `terminalOutcome:"APPROVED"`, `dlqTopicRef:null`, `sfdcNotified:"SENT"`.

---

### Filter — `status=COMPLETED_DECLINED`

- **Entry**: `GET /ops/runs?status=COMPLETED_DECLINED`
- **Drive to outcome** — take the default arm at `n_decide` (scoring `decision != "APPROVED"`) → `n_reject` (`rejected`). This is a **clean business decline, not a failure**:
  - Full-stack: start **Fixture B** (PAN contains `LOW` → bureauScore 540 < 700 → scoring REJECTED). Alternatively a non-empty `payload.negativeFlags` list forces REJECTED at score-time.
  - Engine-only: start Fixture B (run id `ji-corr-decl-1`), publish the four upstream OK responses with that run's ids, but make the **bureau** response low and **scoring** response REJECTED:
    ```json
    { "journeyInstanceId": "ji-corr-decl-1", "correlationId": "corr-decl-1", "nodeId": "n_bureau", "capabilityKey": "bureau", "status": "OK", "result": { "bureauResults": [ { "type": "CIBIL", "score": 540, "grade": "C", "reportId": "RPT-2", "source": "CIBIL", "fetchedAt": "2026-07-03T10:16:00Z" } ], "bureauScore": 540, "bureauGrade": "C", "reportId": "RPT-2" }, "errorClass": null }
    ```
    ```json
    { "journeyInstanceId": "ji-corr-decl-1", "correlationId": "corr-decl-1", "nodeId": "n_score", "capabilityKey": "scoring", "status": "OK", "result": { "decision": "REJECTED", "score": 540, "reasons": ["bureauScore 540 < threshold 700", "fico=750"] }, "errorClass": null }
    ```
    (also publish the `n_customer` and `n_kyc` OK responses with `journeyInstanceId:"ji-corr-decl-1"`/`correlationId:"corr-decl-1"`.) `n_decide` defaults to `n_reject`; no `n_book` response is needed.
- **Expected result**: **200** `PageDto` filtered to `COMPLETED_DECLINED`. Terminal node `n_reject`, terminal outcome **REJECTED**, emit `LoanRejected`. Transitions end at `n_reject COMPLETED`. `endedAt` set, `dlqTopicRef:null` (a decline is a normal completion, never red).
  - **Verify**: `GET /ops/runs/search?key=corr-decl-1` → `"status":"COMPLETED_DECLINED"`; detail → `terminalNodeId:"n_reject"`, `terminalOutcome:"REJECTED"`.

---

### Filter — `status=FAILED_SFDC_NOTIFIED`

- **Entry**: `GET /ops/runs?status=FAILED_SFDC_NOTIFIED`
- **Drive to outcome** — a run that FAILED **and** the channel push was confirmed (`sfdcNotified=SENT`). Two reachable producers:
  1. **Sweeper force-fail (stuck/timeout).** Full-stack: leave a run `RUNNING` past the 900s `run-budget-seconds` (stop a capability so a node never answers); the `JourneyLivenessSweeper` (every 60s) publishes an ERROR `JourneyDecision` with `terminalNodeId="__timeout__"`, marks SFDC notified, then `fail("__timeout__", ERROR)`. Engine-only: start Fixture A, publish nothing, wait 900s.
  2. **Capability ERROR (PERMANENT) at a node with no retry.** Engine-only: start Fixture A, publish the four upstream OK responses, then fail `n_book` (`n_book` has no retrySpec, so first ERROR fails the run and the ERROR `JourneyDecision` is published/confirmed):
     ```json
     { "journeyInstanceId": "ji-corr-appr-1", "correlationId": "corr-appr-1", "nodeId": "n_book", "capabilityKey": "lending-origination", "status": "ERROR", "result": {}, "errorClass": "PERMANENT" }
     ```
     (To fail earlier instead, send `status:"ERROR","errorClass":"PERMANENT"` on `cap.scoring.response.v1` for `n_score`.)
- **Expected result**: **200** `PageDto` filtered to `FAILED_SFDC_NOTIFIED`. Store `State.FAILED`, outcome **ERROR**, `sfdcNotified:"SENT"`. Sweeper path → `terminalNodeId:"__timeout__"` + ops event `run.sweptTimeout`. `dlqTopicRef` is set (pointer only, e.g. `orig.sfdc.dlq.v1`).
  - **Verify**: `GET /ops/runs/search?key=corr-appr-1` → `"status":"FAILED_SFDC_NOTIFIED"`; detail → `sfdcNotified:"SENT"`, `terminalOutcome:"ERROR"`, `dlqTopicRef` non-null, and `nodeStats[].failureClass` = `PERMANENT` (or `AMBIGUOUS`/`TRANSIENT`/`BREAKER_OPEN` per the response's `errorClass`) on the failed node.

---

### Filter — `status=FAILED_NOTIFY_PENDING`

- **Entry**: `GET /ops/runs?status=FAILED_NOTIFY_PENDING`
- **Drive to outcome** — a run marked FAILED whose channel push has **not** been confirmed (`sfdcNotified=PENDING`). This is the window between the engine deciding to fail and the decision publish confirming. To hold a run in it deterministically: block/pause the decision producer (or the broker's ack for `orig.decision.v1`) so `KafkaDelivery.confirm` cannot complete, then fail a node as in the previous permutation (engine-only: publish the `n_book` PERMANENT ERROR from above). The run's raw state is FAILED but notify stays PENDING until the send confirms; the sweeper leaves it RUNNING and retries if the notify can't confirm, so this status is the transient "failed, telling the channel now" state.
- **Expected result**: **200** `PageDto` filtered to `FAILED_NOTIFY_PENDING`. Detail → `sfdcNotified:"PENDING"`, `terminalOutcome:"ERROR"`, `dlqTopicRef` non-null (set for both FAILED_* statuses). Once the push confirms it flips to `FAILED_SFDC_NOTIFIED`.
  - **Verify**: `GET /ops/runs/{runId}` shows `"status":"FAILED_NOTIFY_PENDING"`, `"sfdcNotified":"PENDING"`.

---

### Filter — unknown `status` (fail-closed 400)

- **Entry**: `GET /ops/runs?status=BOGUS`
- **Drive to outcome**: none needed — the controller validates the enum before touching the store.
- **Expected result**: **400** `{"error":"BAD_REQUEST","message":"unknown status 'BOGUS' (allowed: [RUNNING, COMPLETED_APPROVED, COMPLETED_DECLINED, FAILED_SFDC_NOTIFIED, FAILED_NOTIFY_PENDING])"}`. Any status outside that vocabulary (including e.g. `FAILED`, `APPROVED`) is rejected.

---

### Filter — `journeyKey` (exact match)

- **Entry**: `GET /ops/runs?journeyKey=loan-origination`
- **Drive to outcome**: any run of that journey (Fixture A or B). `journeyKey` is exact-match; a wrong/unknown key simply returns an empty page (not 400).
- **Expected result**: **200** `PageDto` with only runs whose `journeyKey == "loan-origination"`. Combinable with `status`, `since`, `until`, `stuckOnly`, paging.
  - **Verify**: every `items[].journeyKey` equals `loan-origination`.

---

### Filter — `since` (startedAt lower bound)

- **Entry**: `GET /ops/runs?since=2026-07-03T00:00:00Z` (ISO-8601 instant).
- **Drive to outcome**: any run; filter keeps `startedAt >= since`.
- **Expected result**: **200** `PageDto` with runs started at/after `since`. Set `since` after a run's `startedAt` to exclude it; before to include it.
  - **Verify**: every `items[].startedAt >= since`.

---

### Filter — `until` (startedAt upper bound)

- **Entry**: `GET /ops/runs?until=2026-07-03T23:59:59Z`
- **Drive to outcome**: any run; filter keeps `startedAt <= until`. Combine with `since` for a window: `?since=2026-07-02T00:00:00Z&until=2026-07-03T00:00:00Z`.
- **Expected result**: **200** `PageDto` with runs started at/before `until`.
  - **Verify**: every `items[].startedAt <= until`.

---

### Filter — bad `since`/`until` format (400)

- **Entry**: `GET /ops/runs?since=not-a-date` (or `until=03-07-2026`).
- **Drive to outcome**: none — parse fails before query.
- **Expected result**: **400** `{"error":"BAD_REQUEST","message":"..."}`. Only strict ISO-8601 instants (e.g. `2026-07-03T10:00:00Z`) are accepted.

---

### Filter — `stuckOnly=true`

- **Entry**: `GET /ops/runs?stuckOnly=true` (optionally `&status=RUNNING`).
- **Drive to outcome**: a **live** run (state `RUNNING`) whose `startedAt <= now − 840s` (= `run-budget 900s − sweep-interval 60s`) but not yet swept. Full-stack: start Fixture A, stop the `scoring` capability, wait ~840s. Engine-only: start Fixture A, publish nothing, wait ~840s. At ~900s the sweeper force-fails it and it leaves the stuck window (becomes `FAILED_SFDC_NOTIFIED`), so query within the ~840–900s window.
- **Expected result**: **200** `PageDto` containing only RUNNING runs flagged `stuck:true`. Each item still shows `status:"RUNNING"` (stuck is a live-problem lens, not a terminal status), `stuck:true`, `sweepDeadline = startedAt + 900s`. `stuckOnly=false` (default) returns all matching runs regardless of stuck flag.
  - **Verify**: every `items[].stuck == true` and `items[].status == "RUNNING"`; the same run in `GET /ops/runs/{runId}` shows `"stuck":true` and a future `sweepDeadline`.

---

### List — pagination `page` / `size` (and clamps)

- **Entry**:
  - `GET /ops/runs?page=1&size=25`
  - Clamp checks: `GET /ops/runs?page=-5&size=0` and `GET /ops/runs?size=500`
- **Drive to outcome**: seed enough runs (repeat Fixture A with distinct `notificationId`/`correlationId`) to exceed one page.
- **Expected result**: **200** `PageDto`. `page` is 0-based (negatives floored to 0); `size` clamped to `1..200` (`MAX_PAGE_SIZE=200`, so `size=0→1`, `size=500→200`). Sort `startedAt` DESC, `runId` tiebreak. `totalItems`/`totalPages` reflect the full filtered set.
  - **Verify**: `?page=-5&size=0` echoes `"page":0,"size":1`; `?size=500` echoes `"size":200`.

---

### Exact search — by `correlationId`

- **Entry**: `GET /ops/runs/search?key=corr-appr-1`
- **Drive to outcome**: start Fixture A (`correlationId=corr-appr-1`).
- **Expected result**: **200** `List<RunSummaryDto>` (newest-first), each matching that exact `correlationId`. EXACT match only — no substring/full-text.
  - **Verify**: the returned summary's `correlationId == "corr-appr-1"` and its `status` reflects the run's current computed state.

---

### Exact search — by `notificationId`

- **Entry**: `GET /ops/runs/search?key=ntf-appr-1`
- **Drive to outcome**: Fixture A (`notificationId=ntf-appr-1`).
- **Expected result**: **200** `List<RunSummaryDto>` matching that exact `notificationId`. (`search` scans four id families: `runId | correlationId | notificationId | sfdcRecordId`.)

---

### Exact search — by `sfdcRecordId`

- **Entry**: `GET /ops/runs/search?key=a0X-appr-1`
- **Drive to outcome**: Fixture A (`sfdcRecordId=a0X-appr-1`).
- **Expected result**: **200** `List<RunSummaryDto>` matching that exact `sfdcRecordId`.

---

### Exact search — by `runId`

- **Entry**: `GET /ops/runs/search?key=ji-corr-appr-1`
- **Drive to outcome**: Fixture A; the engine derives `runId/instanceId = "ji-" + correlationId = ji-corr-appr-1` (grab the exact value from the engine's `journey.start instanceId=...` log line).
- **Expected result**: **200** `List<RunSummaryDto>` with the single matching run (`runId == "ji-corr-appr-1"`).

---

### Exact search — duplicate resend (same id → multiple runs)

- **Entry**: `GET /ops/runs/search?key=a0X-appr-1` (search by the business record id).
- **Drive to outcome**: the engine's `insertIfAbsent` on `instanceId = "ji-"+correlationId` makes a same-`correlationId` resend idempotent (one run). To produce **multiple** runs for one business record, resend the same `sfdcRecordId`/`applicationRef` under **different** `correlationId`s (e.g. `corr-appr-1` then `corr-appr-1b`), which the engine treats as distinct runs. Search then keys on the shared `sfdcRecordId`.
- **Expected result**: **200** `List<RunSummaryDto>` with **several** summaries (newest-first) — the search is designed so a re-sent business record surfaces all its runs. A pure duplicate (identical `correlationId`) yields exactly one run (the redelivery was dropped as `journey.start.duplicate`).

---

### Exact search — blank/missing `key` (400)

- **Entry**: `GET /ops/runs/search?key=` (or `GET /ops/runs/search` with no `key`).
- **Drive to outcome**: none.
- **Expected result**: **400** `{"error":"BAD_REQUEST","message":"query parameter 'key' must be a non-blank exact id (runId | correlationId | notificationId | sfdcRecordId)"}`.

---

### Run detail — by `runId` (200)

- **Entry**: `GET /ops/runs/ji-corr-appr-1`
- **Drive to outcome**: any run (use the COMPLETED_APPROVED fixture for a fully-populated detail).
- **Expected result**: **200** `RunDetailDto`:
  ```jsonc
  { "runId":"ji-corr-appr-1","journeyKey":"loan-origination","journeyVersion":1,
    "status":"COMPLETED_APPROVED","sfdcNotified":"SENT",
    "startedAt":"...","endedAt":"...",
    "terminalNodeId":"n_done","terminalOutcome":"APPROVED",
    "correlationId":"corr-appr-1","notificationId":"ntf-appr-1","sfdcRecordId":"a0X-appr-1",
    "transitions":[ { "seq":1,"nodeId":"n_customer","status":"COMPLETED","at":"...","late":false }, /* ...through n_done */ ],
    "dlqTopicRef": null,
    "stuck": false, "sweepDeadline": null,
    "nodeStats":[ { "nodeId":"n_book","attempts":1,"failureClass":null } /* sorted by nodeId */ ],
    "compensationOf": null, "compensationPending": [] }
  ```
  - `transitions` ordered by `seq` (event order, not wall-clock); `late:true` marks any transition recorded after the run ended.
  - On a FAILED run: `dlqTopicRef` non-null, `nodeStats[].failureClass` = `TRANSIENT|PERMANENT|AMBIGUOUS|BREAKER_OPEN` on the failed node, `terminalOutcome:"ERROR"`. If a compensation saga ran, `compensationOf` = the node that triggered it and `compensationPending` lists undo nodes still queued (head = in flight). (Note: in `loan-origination` as written, `n_book`'s own failure never completes it, so no `reverseBooking` compensation is reachable — `compensationOf` stays `null`.)

---

### Run detail — unknown `runId` (404 empty body)

- **Entry**: `GET /ops/runs/ji-does-not-exist`
- **Drive to outcome**: none.
- **Expected result**: **404 with an empty body** (no `ErrorDto`). Distinct from `search`, which returns `[]` (empty list, 200) for an id with no runs.

---

### Auth — missing token / missing actor (401)

- **Entry**:
  - `GET /ops/runs` with only `X-User-Id: ops.analyst@bank` (no `X-Ops-Token`)
  - `GET /ops/runs` with only `X-Ops-Token: dev-ops-token` (no `X-User-Id`)
  - `GET /ops/runs` with `X-Ops-Token: dev-registry-token` (registry token, wrong secret)
- **Drive to outcome**: none — the servlet filter (`HIGHEST_PRECEDENCE+20`) rejects before the controller.
- **Expected result**: **401** on every `/ops/*` path:
  - bad/missing `X-Ops-Token` (including the registry token) → `{"error":"UNAUTHENTICATED","message":"invalid or missing X-Ops-Token"}`
  - missing/blank `X-User-Id` → `{"error":"UNAUTHENTICATED","message":"X-User-Id header is required for the ops API"}`
  - Every attempt, refused or allowed, still emits an `ops.audit` log line (ids only, never payload).

---

# Appendices

## Appendix A — Idempotency & dedup key rules (every layer)

The platform dedups at four independent layers. `correlationId` is **never** a dedup input at the edges (trace-only), but it IS the highest-priority seed for the engine's run id.

| Layer | Primary key | Fallback key | Effect on a resend |
|---|---|---|---|
| **SFDC ingress edge** | `notificationId` (SOAP `Notification/Id`), CREATE_ONLY insert | `sfdcRecordId + applicationRef` application-pointer | 6 `DedupePath` states: `NEW`/`STALLED`/`FAILED` may publish; `IN_FLIGHT` (within 60s lease), `PUBLISHED`, `DECIDED` are idempotent no-ops. |
| **Digital partner edge** | gate 1 = `requestId` | gate 2 = `partner + "::" + applicationRef` | Routing checked BEFORE any claim (an UNROUTABLE type never burns the request id); `applicationId = DIG-<partnerCode>-<applicationRef>` is deterministic — a resend returns the same id. |
| **Engine run start** | `instanceId = "ji-" + dedupKey`, `dedupKey = firstNonNull(correlationId, originalCorrelationId, notificationId, applicationRef, "unknown")` | — | `store.insertIfAbsent` is the exactly-once gate; a redelivery with the same key logs `journey.start.duplicate` and is dropped (does NOT resume a live winner — the sweeper is the net). |
| **Capability invocation** | `idempotencyKey = <journeyInstanceId>:<nodeId>` | dispatcher derives it if null | A node already completed, or a response for a terminal run, is dropped. Correlation is `journeyInstanceId` + `nodeId` (message key ignored). |

Practical consequences for manual testing:
- To **re-run the same envelope as a fresh run**, change `correlationId` (or, if absent, `notificationId`) — reusing it hits `insertIfAbsent` and is dropped.
- To test the SFDC **STALLED re-drive** vs **IN_FLIGHT no-op**, the 60s publish lease (`idfc.edge.publish-lease-seconds`) is the boundary.
- The C5 poison breaker DLQs a notification after `poison-redelivery-threshold` (default **5**) transient redeliveries; C3 journey re-enqueue is capped at `max-journey-retry` (default **1**).

---

## Appendix B — DLQ topics & how to inspect them

Inspect any DLQ in **Kafka UI** (http://localhost:8085 → cluster `idfc` → the topic → **Messages** tab, offset From beginning). Nothing consumes DLQs automatically; they are terminal parking lots.

| DLQ topic | Fed by | When |
|---|---|---|
| `orig.sfdc.dlq.v1` | SFDC ingress edge (`idfc.edge.dlq-topic`) | Permanent (C2) mapping/config failures and C5 poison. DLQ envelopes have `transactionId="dlq"`. Triggers: missing `Notification/Id`/`OrganizationId`/`SVCNAME__c`/`Request__c`, unknown org (after one refresh), unknown SVCNAME/type, bad `Request__c` JSON (that one notification only). |
| `cap.verification.dlq.v1` | verification capability | Any `CapabilityResponse.status=ERROR` on a Karza journey (HTTP non-2xx, empty body, connect/read timeout) — also fires SFDC notify on `sfdc.response.notify.v1`. |
| `cap.<key>.request.v1.dlq` | platform error handler (`.dlq` suffix) | A capability request that is poison/retry-exhausted on the capability side. |
| `cap.<key>.response.v1.dlq` | engine response consumer | A response the engine cannot deserialize (`PoisonMessageException`). |
| `<origination-topic>.dlq` (e.g. `orig.sfdc.pl.v1.dlq`) | engine `OriginationConsumer` | Undeserializable envelope, or `type` with no `type-to-journey` row (`UnroutableTypeException`) — fail-closed, never a default journey. |

To confirm a message was dead-lettered rather than lost: check the matching `.dlq` topic AND the engine/edge log for the reason line (`edge.soap-unparseable`, `journey.start.duplicate`, `ops.event.dropped`, or an `UnroutableTypeException`).

---

## Appendix C — Ops API query cheat-sheet

Base `http://localhost:8082/ops`. **Both** headers required on every call: `X-Ops-Token: dev-ops-token` and `X-User-Id: <any non-blank>`. GET-only. Every call emits an `ops.audit` log line (ids only).

```bash
OPS=http://localhost:8082/ops
H='-H X-Ops-Token:dev-ops-token -H X-User-Id:ops.analyst@bank'

# list (defaults: page 0, size 50, sorted startedAt DESC)
curl -s $H "$OPS/runs"

# filter by status  (RUNNING | COMPLETED_APPROVED | COMPLETED_DECLINED | FAILED_SFDC_NOTIFIED | FAILED_NOTIFY_PENDING; case-insensitive)
curl -s $H "$OPS/runs?status=RUNNING"

# filter by journeyKey + only stuck live runs + paging
curl -s $H "$OPS/runs?journeyKey=loan-origination&stuckOnly=true&page=0&size=25"

# time window (ISO-8601 instants; filters on startedAt)
curl -s $H "$OPS/runs?since=2026-07-02T00:00:00Z&until=2026-07-03T00:00:00Z"

# EXACT-id search across runId | correlationId | notificationId | sfdcRecordId (newest-first)
curl -s $H "$OPS/runs/search?key=corr-abc-123"

# full run detail (transitions, nodeStats, DLQ ref, compensation, sweepDeadline)
curl -s $H "$OPS/runs/ji-corr-abc-123"
```

Param bounds: `size` clamped `1..200`; `page` floored to 0; unknown `status` → **400**; bad `since`/`until` format → **400**; blank `search?key=` → **400**; unknown `runId` → **404** (empty body). `RunDetailDto` carries `sfdcNotified` (NONE|PENDING|SENT), `terminalNodeId`/`terminalOutcome`, `transitions[]`, `nodeStats[]` (with `attempts` + `failureClass` TRANSIENT|PERMANENT|AMBIGUOUS|BREAKER_OPEN), `dlqTopicRef` (set only for FAILED_* statuses), `compensationOf`/`compensationPending[]`, `stuck`, and `sweepDeadline` (`startedAt + runBudget`, default budget **900s**; a run is flagged `stuck` at ~840s and force-failed at 900s).

Auth failures:
```bash
curl -i "$OPS/runs" -H 'X-User-Id:ops.analyst@bank'   # 401 invalid or missing X-Ops-Token
curl -i "$OPS/runs" -H 'X-Ops-Token:dev-ops-token'    # 401 X-User-Id header is required for the ops API
```

---

## Appendix D — Troubleshooting

| Symptom | Likely cause | Check / fix |
|---|---|---|
| **Message produced but no run starts / no `journey.start` log** | (a) `type` has no `type-to-journey` row → poison to `<topic>.dlq`; (b) same `correlationId`/`notificationId` as a prior run → `insertIfAbsent` drop; (c) engine not consuming that topic. | Check `orig.*.v1.dlq` and the engine log for `UnroutableTypeException` / `journey.start.duplicate`. Confirm `type` is mapped (base: PERSONAL_LOAN, LAP, BUSINESS_LOAN, COMMERCIAL, Inbound_Wrapper → loan-origination; +DEVICE_VALIDATION/EMPLOYEE_LWD_UPDATE in local profile). Use a fresh `correlationId`. Verify the topic is in `idfc.engine.origination-topics`. |
| **Journey not reachable at all** (verification / e-mandate / payment-execution) | Not loaded by the default `classpath` source and/or no `type-to-journey` row. `payment-execution.journey.json` does not exist (payments is a stub with no consumer). | Add the journey file to `idfc.engine.journey-resources` (or publish via registry + `journey-source=registry`) AND add a `type-to-journey` row; then publish an envelope with that `type`. |
| **Run stuck / never completes** | A task node is waiting on a `cap.<key>.response.v1` you haven't published (Mode B), or a capability service is down, or a response was mis-addressed. | In Kafka UI read `cap.<key>.request.v1` for the exact `journeyInstanceId`+`nodeId`, then publish a matching response. After `run-budget-seconds` (default 900s) the liveness sweeper force-fails it → `FAILED_SFDC_NOTIFIED`, `terminalNodeId=__timeout__`. Watch `stuckOnly=true` / `sweepDeadline` in ops. |
| **Capability response ignored (run doesn't advance)** | Wrong `journeyInstanceId` or `nodeId` in the JSON body, response for an already-completed node/terminal run, or malformed JSON (→ `cap.<key>.response.v1.dlq`). | The Kafka key is irrelevant — fix the **body** ids to the exact values from the request message / `journey.start` log. `nodeId` must exist in the pinned journey. Check the response `.dlq`. |
| **Wrong topic vs wrong journey** | Engine routes on the envelope `type`, not the topic name. Publishing to `orig.sfdc.pl.v1` with `type:"COMMERCIAL"` runs the COMMERCIAL mapping. | To mirror production, publish each `type` to its matching topic; but for testing, the `type` field alone selects the journey. `SENDSMS`→`comm.sms.send.v1` and `Inbound_Wrapper`→`orig.sfdc.pl.v1` are the two aliasing rows to watch. |
| **HTTP 401** | Missing/wrong token, or (registry writes / all ops calls) missing `X-User-Id`. | Match the header to the surface: `X-Auth-Token`/`X-Partner-Token`/`X-Registry-Token`/`X-Ops-Token`. Ops needs BOTH token AND `X-User-Id`. The ops token ≠ the registry token. Local values in the tokens table. |
| **HTTP 403 on registry approve/reject** | Maker-checker self-approval: `X-User-Id` equals the version's `authorId`. | Approve/reject with a **different** actor (`checker@bank` vs `maker@bank`). |
| **HTTP 409 on registry** | Second editable draft, duplicate journey key, or a lifecycle op on a non-eligible state (saveDraft/submit on non-DRAFT, approve/reject on non-PENDING), or a lost checker race. | Inspect the current version status via `GET .../versions`; only one DRAFT/PENDING_APPROVAL may exist at a time. |
| **HTTP 422 on registry** | Bad journey key (must be lowercase kebab-case) or a submit that fails the graph validation gate (populated `issues[]`), or config not a JSON object. | Read `issues[]` in the error body; fix the DAG or the key. |
| **Digital edge 422 UNROUTABLE / 503 RETRY** | `type` has no routing row (422), or a transient publish failure (503). | Digital routing rows: PERSONAL_LOAN/LAP/BUSINESS_LOAN/COMMERCIAL only. 503 means retry the same `requestId` (idempotent). |
| **Mock capability only ever fails PERMANENT** | The 5 delegating loan capabilities collapse every runtime error to ERROR/null → PERMANENT; mocks have no TRANSIENT/AMBIGUOUS lever. | To exercise retry lanes, run **Mode B** and hand-craft the `CapabilityResponse` with `errorClass: TRANSIENT` or `AMBIGUOUS` (only retried if the node declares a matching `retrySpec`). |
| **Can't reach Kafka from host tools** | Using the in-cluster listener. | Host bootstrap is `localhost:29092` (not 9092-internal). Kafka UI at 8085 talks to `kafka:9092` inside the network. |
| **Nothing on a DLQ but run failed** | Business decline, not a failure. | `COMPLETED_DECLINED` (REJECTED via the decline branch) is a normal completion — no DLQ, not red. Only `FAILED_*` statuses set `dlqTopicRef`. |
