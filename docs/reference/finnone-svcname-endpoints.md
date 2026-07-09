# FinnOne / LMS SVCNAME → endpoint catalog (UAT)

**Provenance:** transcribed from two screenshots of the IDFC note *"Finnone LMS API li…"* (1 table),
provided 2026-07-09. The table is alphabetical; these shots cover **A → L (30 rows)** and appear to
continue past `LMSVIEWBYLOAN`, so **rows M → Z may be missing** — re-capture the rest when available.

**What this is for:**
- Seed for the **pattern census** (task #38): classify every SVCNAME by family / read-vs-action /
  sync-vs-async / onboarding tier, so the migration is costed as "N config rows + M mappers + K journeys".
- Wiring reference for **task #50**: when a capability is switched from its WireMock mock to the real
  FinnOne backend (`*_MODE=real` + `*_URL`), the endpoint comes from here.

> ⚠️ **Two data qualities in this doc.** The `HOST / PORT / PATH` columns are *transcribed from a photo
> of a screen* — treat them as draft and verify against the source note / API spec (OCR flags below).
> The `family / kind / lane / tier / landing` columns are **inferred from the SVCNAME** — a proposed
> starting point for the census, **not** confirmed contracts. Confirm with IDFC + the real API specs.

Two host bases appear (both TLS `:443`), plus one internal host for the EMORPHIS upload (`:8084`):
- `api.aws-uat.idfcfirstbank.com`
- `api.uat.int-core.idfcfirstbank.com`
- `api-uat-internal-2.idfcfirstbank.com` (EMORPHIS_FILENET_UPLOAD only, `:8084`)

---

## 1. Raw catalog (as transcribed)

```
SVCNAME_FULL | ENDPOINTHOST | PORT | FULL_ENDPOINT_PATH
ASSET_DE_DUPE_1.0 | api.aws-uat.idfcfirstbank.com | 443 | /finnone-vh-dedupe-sys/api/v1/asset-dedupe
BANK_DTLS_1.0 | api.aws-uat.idfcfirstbank.com | 443 | /finnone-payment-details-sys/api/v1/bank/fetch-details
BANK_DTLS_2.0 | api.aws-uat.idfcfirstbank.com | 443 | /finnone-payment-details-sys/api/v1/bank/fetch-details
BRAND_PORTAL_ROLLOVER_AMT_1.0 | api.uat.int-core.idfcfirstbank.com | 443 | /finnone-loan-autotranche-creations-sys-nd/api/loan/v1/autotranche-creations
COLL_GOLDAUCTION_DUESREFRESH_1.0 | api.aws-uat.idfcfirstbank.com | 443 | /finnone-collections-service-sys/api/collection/v1/loan/dues
COLL_GOLDAUCTION_DUESREFRESH_CHECK_1.0 | api.aws-uat.idfcfirstbank.com | 443 | /finnone-collections-service-sys/api/collection/v1/loan/dues
COLL_LOAN_FORECLOSURE_1.0 | api.uat.int-core.idfcfirstbank.com | 443 | /finnone-loan-foreclosures-sys-nd/api/loan/v1/foreclosures
COLL_LOAN_FORECLOSURE_2.0 | api.uat.int-core.idfcfirstbank.com | 443 | /finnone-loan-foreclosures-sys/api/loan/v1/foreclosures
COLL_LOAN_PAYMENT_POSTING_1.0 | api.uat.int-core.idfcfirstbank.com | 443 | /finnone-loan-payments-sys-nd/api/loan/v1/payments
COLL_LOAN_PAYMENT_POSTING_2.0 | api.uat.int-core.idfcfirstbank.com | 443 | /finnone-loan-payments-sys/api/loan/v1/payments
DEALER_PORTAL_DUE_FETCH_1.0 | api.uat.int-core.idfcfirstbank.com | 443 | /finnone-loan-dealers-sys-nd/api/loan/v1/due-details-queries
EMORPHIS_FILENET_UPLOAD_3.0 | api-uat-internal-2.idfcfirstbank.com | 8084 | SFDCFINNONE/api/1.0/emorphis/filenet/upload
ENT_LMS_FORECLOSURE_1.0 | api.uat.int-core.idfcfirstbank.com | 443 | /finnone-loan-foreclosures-sys-nd/api/loan/v1/foreclosures
FETCH_LOAN_DISB_DTLS_1.0 | api.uat.int-core.idfcfirstbank.com | 443 | /finnone-loan-disbursaldetails-sys-nd/api/loan/v1/disbursal-detail-queries
FINNONE_DEDUPE_1.0 | api.uat.int-core.idfcfirstbank.com | 443 | /finnone-loan-dedupedetails-sys-nd/api/loan/v1/dedupe-queries
FINNONE_EMAIL_VERIFICATION_CALLBACK_1.0 | api.aws-uat.idfcfirstbank.com | 443 | /finnone-email-verification-sys/api/v1/status/callback
FINNONE_LOAN_ASSETSUMMARY_1.0 | api.uat.int-core.idfcfirstbank.com | 443 | /finnone-loan-assetsummary-sys-nd/api/loan/v1/asset-summary-queries
FinnOne_Payment_1.0 | api.aws-uat.idfcfirstbank.com | 443 | /finnone-payment-details-sys/api/v1/push-payment
FINNONESUBMIT_1.0 | api.aws-uat.idfcfirstbank.com | 443 | /finnone-submit-sys/api/v1/finnonesubmit
FTSINSERT_1.0 | api.aws-uat.idfcfirstbank.com | 443 | /finnone-fts-details-sys/api/v1/CflFTSIntegration
GETACHOLDERNAME_1.0 | api.aws-uat.idfcfirstbank.com | 443 | /finnone-user-account-details-sys/api/get-acc-holder-name
GETDOCUMENTSSERVICE_1.0 | api.uat.int-core.idfcfirstbank.com | 443 | /filenet-wrapper-aj-getdocs-proc/SFDCFINNONE/bpm_getDocuments
GETDOCUMENTSSERVICE_ATTACH_1.0 | api.uat.int-core.idfcfirstbank.com | 443 | /filenet-wrapper-aj-getdocs-proc/SFDCFINNONE/bpm_getDocuments
GETIFSCDETAILS_1.0 | api.uat.int-core.idfcfirstbank.com | 443 | /finnone-loan-utilities-sys-nd/api/loan/v1/utilities
GETMULTIIFSCDETAILS_1.0 | api.uat.int-core.idfcfirstbank.com | 443 | /finnone-loan-utilities-sys-nd/api/loan/v1/utilities
IMDCHEQUESTATUS_1.0 | api.uat.int-core.idfcfirstbank.com | 443 | /finnone-loan-imd-status-sys-nd/api/loan/v1/imd-status-queries
IMDSUBMIT_1.0 | api.uat.int-core.idfcfirstbank.com | 443 | /finnone-loan-push-imd-sys-nd/api/loan/v1/push-imd
IMPS_TO_VALIDATE_CUSTOMER_1.0 | api.aws-uat.idfcfirstbank.com | 443 | /finnone-user-account-details-sys/api/P2A
LMSVIEWBYCRN_1.0 | api.aws-uat.idfcfirstbank.com | 443 | /finnone-payment-details-sys/api/v1/lms/crn-details
LMSVIEWBYLOAN_1.0 | api.aws-uat.idfcfirstbank.com | 443 | /finnone-payment-details-sys/api/v1/lms/loan-details
```

### Full URLs (host+port+path — ready for `*_URL` config)

```
ASSET_DE_DUPE_1.0=https://api.aws-uat.idfcfirstbank.com/finnone-vh-dedupe-sys/api/v1/asset-dedupe
BANK_DTLS_1.0=https://api.aws-uat.idfcfirstbank.com/finnone-payment-details-sys/api/v1/bank/fetch-details
BANK_DTLS_2.0=https://api.aws-uat.idfcfirstbank.com/finnone-payment-details-sys/api/v1/bank/fetch-details
BRAND_PORTAL_ROLLOVER_AMT_1.0=https://api.uat.int-core.idfcfirstbank.com/finnone-loan-autotranche-creations-sys-nd/api/loan/v1/autotranche-creations
COLL_GOLDAUCTION_DUESREFRESH_1.0=https://api.aws-uat.idfcfirstbank.com/finnone-collections-service-sys/api/collection/v1/loan/dues
COLL_GOLDAUCTION_DUESREFRESH_CHECK_1.0=https://api.aws-uat.idfcfirstbank.com/finnone-collections-service-sys/api/collection/v1/loan/dues
COLL_LOAN_FORECLOSURE_1.0=https://api.uat.int-core.idfcfirstbank.com/finnone-loan-foreclosures-sys-nd/api/loan/v1/foreclosures
COLL_LOAN_FORECLOSURE_2.0=https://api.uat.int-core.idfcfirstbank.com/finnone-loan-foreclosures-sys/api/loan/v1/foreclosures
COLL_LOAN_PAYMENT_POSTING_1.0=https://api.uat.int-core.idfcfirstbank.com/finnone-loan-payments-sys-nd/api/loan/v1/payments
COLL_LOAN_PAYMENT_POSTING_2.0=https://api.uat.int-core.idfcfirstbank.com/finnone-loan-payments-sys/api/loan/v1/payments
DEALER_PORTAL_DUE_FETCH_1.0=https://api.uat.int-core.idfcfirstbank.com/finnone-loan-dealers-sys-nd/api/loan/v1/due-details-queries
EMORPHIS_FILENET_UPLOAD_3.0=https://api-uat-internal-2.idfcfirstbank.com:8084/SFDCFINNONE/api/1.0/emorphis/filenet/upload
ENT_LMS_FORECLOSURE_1.0=https://api.uat.int-core.idfcfirstbank.com/finnone-loan-foreclosures-sys-nd/api/loan/v1/foreclosures
FETCH_LOAN_DISB_DTLS_1.0=https://api.uat.int-core.idfcfirstbank.com/finnone-loan-disbursaldetails-sys-nd/api/loan/v1/disbursal-detail-queries
FINNONE_DEDUPE_1.0=https://api.uat.int-core.idfcfirstbank.com/finnone-loan-dedupedetails-sys-nd/api/loan/v1/dedupe-queries
FINNONE_EMAIL_VERIFICATION_CALLBACK_1.0=https://api.aws-uat.idfcfirstbank.com/finnone-email-verification-sys/api/v1/status/callback
FINNONE_LOAN_ASSETSUMMARY_1.0=https://api.uat.int-core.idfcfirstbank.com/finnone-loan-assetsummary-sys-nd/api/loan/v1/asset-summary-queries
FinnOne_Payment_1.0=https://api.aws-uat.idfcfirstbank.com/finnone-payment-details-sys/api/v1/push-payment
FINNONESUBMIT_1.0=https://api.aws-uat.idfcfirstbank.com/finnone-submit-sys/api/v1/finnonesubmit
FTSINSERT_1.0=https://api.aws-uat.idfcfirstbank.com/finnone-fts-details-sys/api/v1/CflFTSIntegration
GETACHOLDERNAME_1.0=https://api.aws-uat.idfcfirstbank.com/finnone-user-account-details-sys/api/get-acc-holder-name
GETDOCUMENTSSERVICE_1.0=https://api.uat.int-core.idfcfirstbank.com/filenet-wrapper-aj-getdocs-proc/SFDCFINNONE/bpm_getDocuments
GETDOCUMENTSSERVICE_ATTACH_1.0=https://api.uat.int-core.idfcfirstbank.com/filenet-wrapper-aj-getdocs-proc/SFDCFINNONE/bpm_getDocuments
GETIFSCDETAILS_1.0=https://api.uat.int-core.idfcfirstbank.com/finnone-loan-utilities-sys-nd/api/loan/v1/utilities
GETMULTIIFSCDETAILS_1.0=https://api.uat.int-core.idfcfirstbank.com/finnone-loan-utilities-sys-nd/api/loan/v1/utilities
IMDCHEQUESTATUS_1.0=https://api.uat.int-core.idfcfirstbank.com/finnone-loan-imd-status-sys-nd/api/loan/v1/imd-status-queries
IMDSUBMIT_1.0=https://api.uat.int-core.idfcfirstbank.com/finnone-loan-push-imd-sys-nd/api/loan/v1/push-imd
IMPS_TO_VALIDATE_CUSTOMER_1.0=https://api.aws-uat.idfcfirstbank.com/finnone-user-account-details-sys/api/P2A
LMSVIEWBYCRN_1.0=https://api.aws-uat.idfcfirstbank.com/finnone-payment-details-sys/api/v1/lms/crn-details
LMSVIEWBYLOAN_1.0=https://api.aws-uat.idfcfirstbank.com/finnone-payment-details-sys/api/v1/lms/loan-details
```

---

## 2. Census seed (PROPOSED — inferred from the SVCNAME, confirm with the API spec)

`kind`: **read** = idempotent query (no dedup key; `lms-utilities` pattern) · **action** = write/side-effect
(needs idempotency; `imps-disbursal` pattern) · **callback** = inbound notification.
`tier`: **T1** = config route row only · **T2** = row + request/response mapper · **T3** = new journey (DAG) ·
**T4** = new capability (new transport/auth). `landing` = the existing/target capability family.

| SVCNAME | family | kind | lane | tier | landing (proposed) |
|---|---|---|---|---|---|
| ASSET_DE_DUPE_1.0 | dedupe | read | sync | T2 | dedupe / finnone-utilities |
| BANK_DTLS_1.0 / 2.0 | account | read | sync | T1 | finnone-utilities |
| BRAND_PORTAL_ROLLOVER_AMT_1.0 | loan/autotranche | action | async | T2/T3 | loan-servicing |
| COLL_GOLDAUCTION_DUESREFRESH_1.0 | collections | read | sync | T2 | collections |
| COLL_GOLDAUCTION_DUESREFRESH_CHECK_1.0 | collections | read | sync | T1 | collections |
| COLL_LOAN_FORECLOSURE_1.0 / 2.0 | loan | read (query) | sync | T2 | loan-servicing |
| COLL_LOAN_PAYMENT_POSTING_1.0 / 2.0 | collections | **action** | sync/async | T2 (idempotent) | payments |
| DEALER_PORTAL_DUE_FETCH_1.0 | dealer | read | sync | T1 | finnone-utilities |
| EMORPHIS_FILENET_UPLOAD_3.0 | documents | **action** (upload) | async | T2/T4 (own host:8084) | documents |
| ENT_LMS_FORECLOSURE_1.0 | loan | read (query) | sync | T2 | loan-servicing |
| FETCH_LOAN_DISB_DTLS_1.0 | loan | read | sync | T1 | finnone-utilities |
| FINNONE_DEDUPE_1.0 | dedupe | read | sync | T2 | dedupe |
| FINNONE_EMAIL_VERIFICATION_CALLBACK_1.0 | verification | **callback** (inbound) | async | special | verification / comms |
| FINNONE_LOAN_ASSETSUMMARY_1.0 | loan | read | sync | T1 | finnone-utilities |
| FinnOne_Payment_1.0 | payment | **action** (push) | sync/async | T2 (idempotent) | imps / payments |
| FINNONESUBMIT_1.0 | submit | **action** | async | T2 (idempotent) | origination / submit |
| FTSINSERT_1.0 | fts | **action** (insert) | async | T2 (idempotent) | fts |
| GETACHOLDERNAME_1.0 | account | read | sync | T1 | finnone-utilities |
| GETDOCUMENTSSERVICE_1.0 | documents | read | sync | T2 | documents |
| GETDOCUMENTSSERVICE_ATTACH_1.0 | documents | **action** (attach) | sync | T2 | documents |
| GETIFSCDETAILS_1.0 | utilities | read | sync | T1 | finnone-utilities |
| GETMULTIIFSCDETAILS_1.0 | utilities | read | sync | T1 | finnone-utilities |
| IMDCHEQUESTATUS_1.0 | imd | read (status) | sync | T1 | finnone-utilities |
| IMDSUBMIT_1.0 | imd | **action** (push) | async | T2 (idempotent) | submit |
| IMPS_TO_VALIDATE_CUSTOMER_1.0 | account | read (validate) | sync | T1/T2 | imps / account |
| LMSVIEWBYCRN_1.0 | lms | read | sync | **T1** | **lms-utilities (exists)** |
| LMSVIEWBYLOAN_1.0 | lms | read | sync | **T1** | **lms-utilities (exists)** |

**Reading the seed:** the majority are **reads** → the `lms-utilities` sync pattern (config route + mapper),
so most are **T1/T2 config-driven**, not new services. The **actions** (`*SUBMIT`, `*PAYMENT`, `POSTING`,
`FTSINSERT`, `FILENET_UPLOAD`) need the `imps-disbursal` idempotency treatment. `EMORPHIS_FILENET_UPLOAD`
is the outlier (own host + `:8084` + a document-upload transport) — likely its own adapter (T4).

---

## 3. OCR flags (verify against the source note)

- `FTSINSERT_1.0` path ends `CflFTSIntegration` — `Cfl` vs `CfI` (lowercase-L vs capital-I) is ambiguous.
- `EMORPHIS_FILENET_UPLOAD_3.0` — distinct host `api-uat-internal-2` on port **8084**, and its path shows
  **no leading `/`** (`SFDCFINNONE/…`).
- `FETCH_LOAN_DISB_DTLS_1.0` — leading `/` was hard to read; added to match siblings.
- The `-sys-nd` vs `-sys` split between `_1.0` and `_2.0` variants (foreclosure, payment-posting) is real
  in the source, not a typo.
- Rows **M → Z** were not in the two screenshots — this catalog is A → L only.

---

## 4. How the platform absorbs these (design note)

Each row is a **route**, not a service. The dispatch is config-as-data, keyed by svcName — the working
archetypes are `capabilities/verification` (`idfc.verification.routes`: svcName → url + auth, allow-listed)
and `capabilities/lms-utilities` (requestCode → endpoint, fail-closed on unknown). Onboarding a svcName is:
a **route config row** (always) + a **mapper pair** only if the request/response shape is new + (rarely) a
new adapter for a new transport/auth. See the SFDC-edge routing config comment: *"New SVCNAME = a config
ROW, not code."* This file is the input that turns the SVCNAME zoo into a costed `N rows + M mappers + K journeys`.
