# Device Financing — real SFDC SOAP entry (Post_Disbursal_Apple)

The production entry for device financing is an **SFDC Outbound Messaging SOAP call**, not a
hand-published Kafka message. `SVCNAME__c = Post_Disbursal_Apple` routes, through the **SFDC ingress
edge**, to the **device-financing journey**. Brand = **APPLE** is implicit in the svcName (the payload
has no brand field); the device id is `imei`. Only the vendor's response DATA is mocked (WireMock);
everything else — edge parse/normalize/route, engine, the capability's real HTTP call — is real.

```
POST /api/v1/sfdc/outbound-messages (edge :8080, X-Auth-Token)
  -> parse SOAP -> normalize (SVCNAME__c -> type, Request__c CDATA -> inline payload)
  -> route (SFDC, Post_Disbursal_Apple) -> publish orig.device-financing.v1
  -> engine type-to-journey Post_Disbursal_Apple -> device-financing journey
  -> n_brand (brand=APPLE derived from svcName) -> n_route (validation off) -> n_block
     (real HTTP to the vendor, imei sent) -> n_decide -> n_approve  => COMPLETED_APPROVED
```

## Run it (local, against the real edge)

Prereqs: infra up (`docker compose -f docker-compose.infra.yml up -d`), and the engine + the
`device-financing` capability running (local profile). The Apple org `00D0w0000008ec7EAA` is in the
edge `known-orgs`; the local edge auth token is `dev-token`.

```bash
curl -sS -X POST http://localhost:8080/api/v1/sfdc/outbound-messages \
  -H "X-Auth-Token: dev-token" \
  -H "Content-Type: text/xml" \
  --data-binary @full-flow-it/src/test/resources/sfdc-outbound-apple-postdisbursal.xml
# expected response: 200  <Ack>true</Ack>
```

(That file is the verbatim UAT sample. To fire it by hand instead, the same envelope is inlined below.)

## Verify in the ops view

The SOAP path stamps an **edge-generated** correlationId (the `correlationid` HTTP header is not the
run key), so search by the **notificationId** (`Notification/Id`) or the **sfdcRecordId** (`sf1:Id`):

```bash
H='-H X-Ops-Token:dev-ops-token -H X-User-Id:you@bank'
curl -s $H 'http://localhost:8082/ops/runs/search?key=04l7200000Daq5RAbR'   # notificationId
# expect one run: journeyKey device-financing, status COMPLETED_APPROVED
# then GET /ops/runs/{runId} -> terminalNodeId n_approve, transitions n_brand -> n_block (no n_validate)
```

In Kafka UI (`:8085`) you'll also see one `cap.device-financing.request.v1` and its
`cap.device-financing.response.v1`, and the decision on `orig.decision.v1`.

## Outcomes

| Case | How | Result |
|---|---|---|
| Approve | vendor returns pass (`respCode:0`) | `COMPLETED_APPROVED`, `n_approve` |
| Decline | vendor returns non-pass | `COMPLETED_DECLINED`, `n_reject` (business no, not red) |
| Technical fail | vendor 4xx/5xx/timeout | `FAILED_SFDC_NOTIFIED`, `n_block` failed, failureClass PERMANENT/TRANSIENT/AMBIGUOUS |
| Unknown svcName | e.g. `Post_Disbursal_Nokia` (no row) | fails closed: edge DLQ (unrouted) or capability "missing brand" (no brand row) |

## Adding a sibling (other brand / pre-disbursal svcName) — config, not code

- **Edge** (`edges/sfdc-ingress-edge/.../application.yml` → `idfc.edge.routing`): a row
  `type: <SVCNAME>, topic: orig.device-financing.v1, downstream-journey: device-financing`.
- **Engine** (`orchestration/.../application-local.yml` → `type-to-journey`): `<SVCNAME>: device-financing`.
- **Capability** (`capabilities/device-financing/.../application.yml` → `brands`): a brand row with its
  `svc-name: <SVCNAME>`, auth scheme, and pass-logic.

Only `Post_Disbursal_Apple` is wired today; siblings are added as rows once IDFC confirms them.

> **Open item (flagged):** the vendor auth + pass-logic in the APPLE row (`OAUTH` / `respCode == "0"`)
> and the vendor request body (currently `brand` + device id) are **placeholders for the mock vendor**.
> Swap to Apple's real vendor host + response contract when IDFC confirms it. Also confirm whether a
> post-disbursal notification should ever *decline* (today it can, via the branch) or is
> acknowledge-only — that's a one-line journey change if the semantics say so.

## The reference envelope (inline)

```xml
<soapenv:Envelope
    xmlns:soapenv="http://schemas.xmlsoap.org/soap/envelope/"
    xmlns="http://soap.sforce.com/2005/09/outbound"
    xmlns:sf1="urn:sobject.enterprise.soap.sforce.com">
  <soapenv:Body>
    <notifications>
      <OrganizationId>00D0w0000008ec7EAA</OrganizationId>
      <Notification>
        <Id>04l7200000Daq5RAbR</Id>
        <sObject>
          <sf1:Id>a2T721100001IS65U9</sf1:Id>
          <sf1:CLIENTID__c>SFDC</sf1:CLIENTID__c>
          <sf1:Request__c><![CDATA[{
  "paymentInfo": { "swipeOrLoanAmount": "23800.00", "loanTenure": "12", "scheme": "2",
    "customerLoanInterestRate": "7.50", "subventionToApple": "", "appleCashBackAmount": "" },
  "imei": "431254356142345678", "apiVersion": "2"
}]]></sf1:Request__c>
          <sf1:SVCNAME__c>Post_Disbursal_Apple</sf1:SVCNAME__c>
          <sf1:VERSION__c>1.0</sf1:VERSION__c>
        </sObject>
      </Notification>
    </notifications>
  </soapenv:Body>
</soapenv:Envelope>
```
