# Testing the SFDC user-management capability (org-targeted egress)

How to exercise the `sfdc-user-management` sync capability end-to-end: a per-request SFDC
user/role/profile API where **`svcName` selects the operation** and **`orgName` selects
which SFDC org instance is called**.

> **Branch-only.** The endpoint (`POST /api/v1/sfdcUserManagement` on the digital edge)
> and the two SFDC-org WireMocks live on `claude/code-review-bug-design-u568d3`, not
> `main`. Everything below assumes you built/ran that branch.

Artifacts:
- **Postman/Insomnia collection:** `docs/testing/sfdc-user-management.postman_collection.json`
  (11 requests, each with `pm.test` assertions — runnable with Newman).
- Capability internals: `capabilities/sfdc-user-management/README.md`.

## Prerequisites

1. **Infra + the two SFDC org mocks** (they were added to the compose file on this branch):
   ```
   docker compose -f docker-compose.infra.yml up -d
   ```
   Confirm both are up: `mock-sfdc-org-a` on `:19112`, `mock-sfdc-org-b` on `:19113`
   (each returns responses carrying an `org` field so routing is provable).
2. **The digital edge**, built from this branch, running on `:8081` (e.g.
   `.\dev-service.ps1 restart digital-partner-edge`, or `run-services.ps1`).
   Its `application.yml` `sfdc-user-mgmt.orgs` already points ORG_A→`:19112`, ORG_B→`:19113`.
3. Auth: `Authorization: Bearer dev-sync-token` (the same fail-closed sync-edge token as
   impsFT/callLmsUtilities).

## Run it

**Postman/Insomnia:** import the collection, set the collection variables if needed
(`edgeBase=http://localhost:8081`, `syncToken=dev-sync-token`), and send. The three
folders are Reads (org-routing proof), Writes (idempotency + business/technical), and
Fail-closed.

**Newman (CI-style, asserts every response):**
```
newman run docs/testing/sfdc-user-management.postman_collection.json
```

## What each request proves

| # | Request | Proves |
|---|---------|--------|
| 1 | Fetch user — ORG_A | read routes to org-a → `org:ORG_A` |
| 2 | Fetch user — ORG_B | **same svcName, `orgName=ORG_B` → org-b** (`org:ORG_B`) — the org, not the svcName, chose the target |
| 3 | Create user — ORG_A | write success → `200 success:true` + `id` |
| 4 | Create user — idempotency **replay** (fixed key) | send **twice** → identical body, SFDC called **once** (dedup) |
| 5 | Create user — **DUPLICATE** (`DUPE_USER`) | business "no": `200 success:false` — a business outcome, **not** a technical error |
| 6 | Update user / Assign role — ORG_A | the other write svcNames (config rows) |
| 7 | Write **without** idempotencyKey | `422 MISSING_IDEMPOTENCY_KEY` — refused before SFDC is touched |
| 8 | Unknown org | `422 UNKNOWN_ORG` — curated allow-list, no default org |
| 9 | Unknown svcName | `422 NO_ROUTE` |
| 10 | Missing bearer | `401 UNAUTHENTICATED` |

## curl equivalents (code-accurate)

```bash
BASE=http://localhost:8081
AUTH='Authorization: Bearer dev-sync-token'

# READ — org routing proof: same svcName, different org -> different response `org`
curl -s -X POST "$BASE/api/v1/sfdcUserManagement" -H "$AUTH" -H 'Content-Type: application/json' \
  -d '{"svcName":"SFDC_USER_FETCH","orgName":"ORG_A","payload":{"Username":"asha@orga"}}'
# -> {"org":"ORG_A","totalSize":1,"records":[...]}
curl -s -X POST "$BASE/api/v1/sfdcUserManagement" -H "$AUTH" -H 'Content-Type: application/json' \
  -d '{"svcName":"SFDC_USER_FETCH","orgName":"ORG_B","payload":{"Username":"vikram@orgb"}}'
# -> {"org":"ORG_B",...}   (hit a DIFFERENT container)

# WRITE — idempotency: run the SAME command twice; SFDC is called once, both return the same id
curl -s -X POST "$BASE/api/v1/sfdcUserManagement" -H "$AUTH" -H 'Content-Type: application/json' \
  -d '{"svcName":"SFDC_USER_CREATE","orgName":"ORG_A","idempotencyKey":"idem-001","payload":{"Username":"new@orga"}}'
# -> {"success":true,"id":"005A...","org":"ORG_A"}   (2nd send = cached replay)

# WRITE — business "no" (duplicate username): 200, success:false (NOT a 4xx/5xx)
curl -s -X POST "$BASE/api/v1/sfdcUserManagement" -H "$AUTH" -H 'Content-Type: application/json' \
  -d '{"svcName":"SFDC_USER_CREATE","orgName":"ORG_A","idempotencyKey":"idem-002","payload":{"Username":"DUPE_USER"}}'
# -> {"success":false,"errors":[{"statusCode":"DUPLICATE_USERNAME",...}]}

# FAIL CLOSED
curl -si -X POST "$BASE/api/v1/sfdcUserManagement" -H "$AUTH" -H 'Content-Type: application/json' \
  -d '{"svcName":"SFDC_USER_CREATE","orgName":"ORG_A","payload":{"Username":"x@x"}}' | head -1
# -> HTTP/1.1 422   {"code":"MISSING_IDEMPOTENCY_KEY",...}
curl -si -X POST "$BASE/api/v1/sfdcUserManagement" -H "$AUTH" -H 'Content-Type: application/json' \
  -d '{"svcName":"SFDC_USER_FETCH","orgName":"ORG_ZZ","payload":{}}' | head -1
# -> HTTP/1.1 422   {"code":"UNKNOWN_ORG",...}
curl -si -X POST "$BASE/api/v1/sfdcUserManagement" -H 'Content-Type: application/json' \
  -d '{"svcName":"SFDC_USER_FETCH","orgName":"ORG_A","payload":{}}' | head -1
# -> HTTP/1.1 401   (no bearer)
```
> PowerShell: `curl` is an `Invoke-WebRequest` alias — use `curl.exe`, and note
> `Invoke-RestMethod` throws on non-2xx (so a 422/401 looks like an error, not a body).

## Observe the audit

Every call writes one PII-safe (ids-only) sync-invocation row, exactly like imps/lms —
visible on the edge ops surface:
```
curl -s "$BASE/ops/sync-invocations?capability=sfdc-user-management&size=20" \
  -H 'X-Ops-Token: dev-ops-token' -H 'X-User-Id: you@bank'
```
Success reads/writes → `outcome:SUCCESS`; a duplicate username → `BUSINESS_FAILURE`; a
downstream 5xx/timeout → `TECHNICAL_ERROR` with the class/code.

## Notes

- **Real vs mock:** only the org DATA is mocked. Real SFDC orgs later = host + token swaps
  in `sfdc-user-mgmt.orgs`, no code.
- **Ingress contract** (path/verb/headers) is pending JMI confirmation; if it differs, only
  the edge controller changes.
- **Out of scope:** no master-data / daily-delta sync — see the capability README.
