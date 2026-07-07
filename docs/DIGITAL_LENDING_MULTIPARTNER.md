# Digital Lending — multi-partner use case (Groww · CRED · Flipkart)

Three embedded-finance partners originate personal loans through IDFC's platform. Each has its **own auth
token**; the platform **derives the partner from the token** (never from the body, so it can't be spoofed),
runs the **same `loan-origination` journey** the SFDC channel uses, and each partner can see **only their own**
applications. Onboarding a partner is a **config row** — not a new service, not a code change.

> This is the "**one platform, many doors**" principle made concrete: the digital partner door is the twin of
> the SFDC SOAP door. Same canonical envelope → same engine → same capabilities → same decision contract.

All of this is **already in the code** (`edges/digital-partner-edge`); this doc is the runnable scenario.

---

## The partners (config-as-data — `application-local.yml`)

| Partner | `X-Partner-Token` (local dev) | Decision callback (prod push) |
|---|---|---|
| **CRED** | `cred-dev-token` | `http://localhost:9201/cred/callback` |
| **FLIPKART** | `flipkart-dev-token` | `http://localhost:9202/flipkart/callback` |
| **GROWW** | `groww-dev-token` | `http://localhost:9203/groww/callback` |

Onboarding a 4th partner (say **PhonePe**) = one row `{ code, token, callback-url }` + its secret. No rebuild.
**Fail-closed:** a configured partner with a missing token refuses to start (`DigitalEdgeProperties`).

## The door

- **Originate:** `POST http://localhost:8081/api/v1/digital/origination`
  - header `X-Partner-Token: <partner token>` (partner is derived from this; **not** in the body)
  - header `X-Correlation-Id: <optional>` (edge mints one if absent)
  - body `{ requestId, applicationRef, type, orgId, payload }` — all four ids required (else `400 INVALID`)
  - returns `{ applicationId, status, detail }` — `status = ACK_PROCESSED` (routed) · `ACK_DUPLICATE_REQUEST`/
    `ACK_DUPLICATE_APPLICATION` (idempotent replay) · `UNROUTABLE` (422, unknown type) · `401` (bad token)
- **Poll status (tenant-scoped):** `GET http://localhost:8081/api/v1/digital/applications/{applicationId}`
  header `X-Partner-Token` — a partner sees only its own apps; another partner's id reads **404**.

**Prereqs:** `digital-partner-edge` up on `:8081` (in the launcher) + the loan mocks (posidex 9101 · cibil 9102 ·
fico 9103 · nsdl 9104 · FinnOne 1521) up. The flow reuses the exact `loan-origination` journey proven in
`AS_TESTED_LOG.md §3`.

---

## Scenario A — Groww originates a personal loan (→ approved)

```
curl --location 'http://localhost:8081/api/v1/digital/origination' \
--header 'X-Partner-Token: groww-dev-token' \
--header 'Content-Type: application/json' \
--data '{"requestId":"GROWW-REQ-001","applicationRef":"APP-GROWW-001","type":"PERSONAL_LOAN","orgId":"IDFC_RETAIL","payload":{"pan":"ABCDE1234F","name":"ASHA RAO","amount":500000,"tenureMonths":36,"negativeFlags":[]}}'
```
→ `200 { "applicationId":"<id-G>", "status":"ACK_PROCESSED", ... }` — the edge derived partner **GROWW** from
the token, published a canonical envelope to `orig.sfdc.pl.v1`, and the `loan-origination` journey runs
(`customer → kyc → bureau → scoring → decide → book`). Grab `applicationId` from the ACK.

## Scenario B — CRED originates a personal loan (→ approved)

```
curl --location 'http://localhost:8081/api/v1/digital/origination' \
--header 'X-Partner-Token: cred-dev-token' \
--header 'Content-Type: application/json' \
--data '{"requestId":"CRED-REQ-001","applicationRef":"APP-CRED-001","type":"PERSONAL_LOAN","orgId":"IDFC_RETAIL","payload":{"pan":"ABCDE1234F","name":"RAHUL VERMA","amount":300000,"tenureMonths":24,"negativeFlags":[]}}'
```
→ `200 { "applicationId":"<id-C>", "status":"ACK_PROCESSED", ... }` — same journey, partner **CRED**.

## Scenario C — Flipkart originates, and it's declined (business "no")

Two ways to decline (both real):
- **fraud flag** (payload lever, works on any door): `negativeFlags:["FRAUD"]`
- **low credit score** (score lever, works here because the **digital door carries `applicationRef`** — the SFDC
  door nulls it): put `LOW` in `applicationRef` → cibil mock returns 540 (< 700 threshold).

```
curl --location 'http://localhost:8081/api/v1/digital/origination' \
--header 'X-Partner-Token: flipkart-dev-token' \
--header 'Content-Type: application/json' \
--data '{"requestId":"FLIPKART-REQ-001","applicationRef":"APP-FLIPKART-LOW-001","type":"PERSONAL_LOAN","orgId":"IDFC_RETAIL","payload":{"pan":"ABCDE1234F","name":"MEENA IYER","amount":800000,"tenureMonths":48,"negativeFlags":[]}}'
```
→ `200 ACK_PROCESSED` (the ACK only means "accepted for processing"), then the journey runs and reaches
`n_reject` → decision **`LoanRejected`** / `COMPLETED_DECLINED`. A business decline, not a failure.

---

## Verify — poll each partner's status (tenant-scoped)

```
curl --location 'http://localhost:8081/api/v1/digital/applications/<id-G>' --header 'X-Partner-Token: groww-dev-token'
```
→ Groww sees its application's status (`APPROVED` once the journey books it). The engine's `orig.decision.v1`
message updates the digital edge's status store (and, in prod, POSTs the decision to Groww's callback URL).

## Proof 1 — partner cannot be spoofed
- Wrong/absent token → **`401 UNAUTHENTICATED`**:
  ```
  curl -i --location 'http://localhost:8081/api/v1/digital/origination' \
  --header 'X-Partner-Token: not-a-real-token' --header 'Content-Type: application/json' \
  --data '{"requestId":"X-1","applicationRef":"APP-X-1","type":"PERSONAL_LOAN","orgId":"IDFC_RETAIL","payload":{}}'
  ```
- There is **no `partner` field in the body** to forge — `DigitalOriginationRequest` deliberately omits it;
  partner comes only from the token (`DigitalOriginationRequest.java` javadoc). CRED cannot post "I'm GROWW".

## Proof 2 — tenant isolation (a partner sees only its own apps)
After Scenario A returns `<id-G>` (a GROWW app), have **CRED** try to read it:
```
curl -i --location 'http://localhost:8081/api/v1/digital/applications/<id-G>' --header 'X-Partner-Token: cred-dev-token'
```
→ **`404`** — CRED gets "not found" for GROWW's application, even though the id exists. Groww reading the same id
with `groww-dev-token` → `200`. (Enforced at `DigitalStatusController`: `partner.code == status.partner`.)

## Proof 3 — idempotency (no double origination)
Re-send **Scenario A verbatim** (same `requestId: GROWW-REQ-001`):
→ `status: "ACK_DUPLICATE_REQUEST"`, **same `applicationId`**, and **no second journey run** — `requestId` is the
partner's dedup key against the shared platform idempotency store (same store the SFDC edge uses).

---

## What this use case proves (for a review)

| Principle | Shown by |
|---|---|
| **Partner = config, not code** | 3 partners are 3 rows; a 4th is a row + secret, no rebuild |
| **One platform, many doors** | digital door → the *same* `loan-origination` journey as the SFDC SOAP door |
| **Un-spoofable identity** | partner derived from token; no `partner` field exists in the request body |
| **Tenant isolation** | cross-partner `applicationId` read → 404 |
| **Idempotent intake** | duplicate `requestId` → `ACK_DUPLICATE_REQUEST`, one origination |
| **Fail closed** | unknown token → 401; a partner row with no token won't start |
| **Business "no" ≠ failure** | Flipkart decline → `COMPLETED_DECLINED`/`LoanRejected`, a green run |

### Not-yet / honest caveats
- **Decision callbacks** (`:9201/9202/9203`) need a partner-side receiver to actually land; locally, verify via
  the **status GET** (poll) instead. The callback push is the prod mechanism.
- **`applicationId` format** and the exact `Status` DTO fields are whatever the edge returns — read them off the
  live ACK / status response; they are not restated here to avoid drift.
- Partner tokens here are **dev placeholders**; prod swaps the static allow-list for real Kong/Hydra token
  introspection (noted in `application.yml`).
