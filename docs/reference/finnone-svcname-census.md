# FinnOne/LMS svcName census — task #38 (sizing artifact, NOT a build)

**Purpose:** turn the 30-svcName route table (`docs/reference/finnone-svcname-endpoints.md`) into a
**costed classification** — how many *capabilities*, *config rows*, *mappers*, and *journeys* the migration
actually is. No capabilities are built here; this sizes what to build, gated as task #38.

**Method:** every classification below was checked against the **real code** (the existing dispatch
patterns, mappers, envelopes, and the transcribed route hosts), not against the svcName strings. Where the
hypothesis fed in with this task disagreed with the code, **the code wins and the discrepancy is flagged.**
Where the code can't decide it (needs the real request/response payload), it's marked **UNKNOWN** with what's
needed — never guessed.

---

## 0. Headline finding — the clone-trap, resolved by code

**Do NOT clone `lms-utilities` per FinnOne family.** The code shows two structurally *different* dispatch shapes,
and the real catalog matches the wrong one for a clone:

| | `lms-utilities` (existing) | `verification` (existing) | the 30-svcName catalog |
|---|---|---|---|
| Endpoint model | **single facade** — one `vendorBaseUrl` + one fixed path `/api/v1/callLmsUtilities`; the operation (`requestCode`) rides in the **body** | **per-svcName route** — `ConfigRouteResolver`: each svcName → its **own** `base-url` + auth, allow-listed | **per-svcName endpoint** — each svcName has a **distinct host/path** (e.g. `LMSVIEWBYCRN → /finnone-payment-details-sys/api/v1/lms/crn-details`, `GETIFSCDETAILS → /finnone-loan-utilities-sys-nd/…`) |

The catalog is **per-endpoint**, so the correct template is **`verification`'s `ConfigRouteResolver`**, not
`lms-utilities`'s facade. Cloning `lms-utilities` per downstream service would rebuild exactly the
per-service-wrapper sprawl the platform exists to remove.

**Recommendation:** build **one generic sync-utilities capability** on the `verification` route-resolver
pattern — `svcName → {route (config), auth, mapper-or-passthrough}` over the shared `HouseEnvelopeMapper` —
and **fold `lms-utilities` into it** (or re-model `lms-utilities` onto routes). LMS and several FinnOne reads
already share the **same downstream host** (`finnone-payment-details-sys`), which is the tell that LMS is not a
separate family — it's config rows on one generic capability.

> **Pivotal UNKNOWN (blocks the above from being final):** is the real LMS backend actually a `callLmsUtilities`
> **facade** (→ `lms-utilities` model holds and FinnOne may be facade-able too), or genuinely **per-endpoint**
> (→ the route model, as the catalog shows)? The catalog strongly implies per-endpoint, but confirm with IDFC /
> the real API before committing the capability shape. This one answer changes the whole build.

---

## 1. What's already reusable (the templates — verified in code)

- **`verification` = the read/route archetype.** `ConfigRouteResolver` (svcName→route+auth, anti-SSRF allow-list),
  `AdapterRegistry` (svcName→adapter, generic HTTP reused), `MapperRegistry` that **defaults to raw-JSON
  passthrough** for any unregistered svcName, universal `{ISSUCCESS, ERROR, DATA}` envelope, classified retry
  (TRANSIENT/AMBIGUOUS retry, PERMANENT→DLQ+notify). **The passthrough default is why most reads are T1** —
  a read only needs a mapper if its response shape must be transformed.
- **`HouseEnvelopeMapper` (shared-sync) = the shared response idiom** — `{metadata{status,…}, resource_data[]}`;
  its own doc says "LMS utilities, Karza, and future sync services all speak this shape." A read that returns a
  house envelope reuses this mapper → **T1, zero new code**.
- **`imps-disbursal` = the action archetype.** Idempotency FIRST (`idempotencyKeyOf → idempotentId`, dedup
  replay), `businessOutcome`, `downstreamRefOf`. This is the template every **action** svcName needs.
- **`lending-servicing` already reads FinnOne foreclosure** (`FinnOneForeclosurePort`, "READ ONLY — servicing
  never books"). Precedent that **foreclosure is a READ (quote/amount)**, not an execute — see the override below.
- **None of the 30 svcNames is implemented today.** (`OFFER_CHECK`, `KARZA_*` exist as *patterns*, but no route,
  mapper, or handler exists for any of the 30.) So `already-exists? = no` for all — but the *patterns* to build on
  are all present.

---

## 2. The census (30 rows)

Legend — **kind:** R=read (idempotent) · A=action (state-changing) · CB=inbound callback.
**tier:** T1=config route row (passthrough/house mapper, zero code) · T2=row + new mapper · T3=new journey ·
T4=new capability/transport. **idem:** idempotency required before 5xx can be retried.

| svcName | family (from host) | kind | sync/async | journey/call | tier | idem | mapper | target capability | notes / open-Q |
|---|---|---|---|---|---|---|---|---|---|
| ASSET_DE_DUPE_1.0 | finnone-vh-dedupe | R | sync | call | T1? | no | house? | finnone-utilities | dedupe = read/check; confirm response shape |
| BANK_DTLS_1.0 | finnone-payment-details | R | sync | call | T1? | no | house? | finnone-utilities | 1.0/2.0 **same URL** — see Q-versions |
| BANK_DTLS_2.0 | finnone-payment-details | R | sync | call | T1? | no | house? | finnone-utilities | same URL as 1.0 |
| BRAND_PORTAL_ROLLOVER_AMT_1.0 | finnone-loan-autotranche | **A?** | async? | call | T2 | **yes?** | UNKNOWN | finnone-actions | path says "creations" → likely write; "AMT" hints a read → **UNKNOWN**, needs verb/payload |
| COLL_GOLDAUCTION_DUESREFRESH_1.0 | finnone-collections | R? | sync | call | T1? | no? | house? | finnone-utilities | "refresh" ambiguous (read dues vs recompute) — **UNKNOWN** |
| COLL_GOLDAUCTION_DUESREFRESH_CHECK_1.0 | finnone-collections | R | sync | call | T1? | no | house? | finnone-utilities | "check" = read |
| COLL_LOAN_FORECLOSURE_1.0 | finnone-loan-foreclosures | R? | sync | call | T1? | no? | house? | finnone-utilities | **override hypothesis:** `lending-servicing` treats foreclosure as a READ (amount/quote). Could still be execute → **UNKNOWN**, lean read |
| COLL_LOAN_FORECLOSURE_2.0 | finnone-loan-foreclosures | R? | sync | call | T1? | no? | house? | finnone-utilities | `-sys` vs 1.0's `-sys-nd` — see Q-hosts |
| COLL_LOAN_PAYMENT_POSTING_1.0 | finnone-loan-payments | **A** | sync/async | call | T2 | **yes** | UNKNOWN | finnone-actions | "posting" = write a payment → idempotency required |
| COLL_LOAN_PAYMENT_POSTING_2.0 | finnone-loan-payments | **A** | sync/async | call | T2 | **yes** | UNKNOWN | finnone-actions | `-sys` vs 1.0's `-sys-nd` |
| DEALER_PORTAL_DUE_FETCH_1.0 | finnone-loan-dealers | R | sync | call | T1? | no | house? | finnone-utilities | "fetch" = read |
| EMORPHIS_FILENET_UPLOAD_3.0 | **filenet** (api-uat-internal-2:8084) | **A** | async | call | **T4** | **yes** | binary/multipart | finnone-documents | different host+port+**transport** (doc upload) — own adapter |
| ENT_LMS_FORECLOSURE_1.0 | finnone-loan-foreclosures | R? | sync | call | T1? | no? | house? | finnone-utilities | same foreclosure ambiguity as above |
| FETCH_LOAN_DISB_DTLS_1.0 | finnone-loan-disbursaldetails | R | sync | call | T1? | no | house? | finnone-utilities | "fetch" = read |
| FINNONE_DEDUPE_1.0 | finnone-loan-dedupedetails | R | sync | call | T1? | no | house? | finnone-utilities | dedupe query = read |
| FINNONE_EMAIL_VERIFICATION_CALLBACK_1.0 | finnone-email-verification (`/status/callback`) | **CB** | async | **ingress** | **T4** | n/a (dedupe on notif id) | inbound | callback edge | **inbound** — vendor calls US. Different direction; model like the digital-edge decision/callback consumer, NOT an outbound call |
| FINNONE_LOAN_ASSETSUMMARY_1.0 | finnone-loan-assetsummary | R | sync | call | T1? | no | house? | finnone-utilities | summary = read |
| FinnOne_Payment_1.0 | finnone-payment-details (`/push-payment`) | **A** | sync/async | call | T2 | **yes** | UNKNOWN | finnone-actions | "push-payment" = money movement → idempotency (imps pattern) |
| FINNONESUBMIT_1.0 | finnone-submit | **A** | async | call | T2 | **yes** | UNKNOWN | finnone-actions | "submit" = write |
| FTSINSERT_1.0 | finnone-fts-details | **A** | async | call | T2 | **yes** | UNKNOWN | finnone-actions | "insert" = write; path `CflFTSIntegration` (OCR: Cfl/CfI) |
| GETACHOLDERNAME_1.0 | finnone-user-account-details | R | sync | call | T1? | no | house? | finnone-utilities | get account-holder name = read |
| GETDOCUMENTSSERVICE_1.0 | **filenet** (filenet-wrapper…bpm_getDocuments) | R | sync | call | T2/T4 | no | doc/bpm shape | finnone-documents | FileNet read; different downstream shape than finnone-* |
| GETDOCUMENTSSERVICE_ATTACH_1.0 | **filenet** (same bpm_getDocuments path) | **A** | sync | call | T2/T4 | **yes** | doc shape | finnone-documents | "attach" = write doc; **same URL as GETDOCUMENTSSERVICE** — see Q-versions |
| GETIFSCDETAILS_1.0 | finnone-loan-utilities | R | sync | call | T1? | no | house? | finnone-utilities | IFSC lookup = read |
| GETMULTIIFSCDETAILS_1.0 | finnone-loan-utilities | R | sync | call | T1? | no | house? | finnone-utilities | **same URL as GETIFSCDETAILS** — variant, see Q-versions |
| IMDCHEQUESTATUS_1.0 | finnone-loan-imd-status | R | sync | call | T1? | no | house? | finnone-utilities | IMD cheque status = read |
| IMDSUBMIT_1.0 | finnone-loan-push-imd | **A** | async | call | T2 | **yes** | UNKNOWN | finnone-actions | "push-imd" = write |
| IMPS_TO_VALIDATE_CUSTOMER_1.0 | finnone-user-account-details (`/P2A`) | R? | sync | call | T1? | no? | UNKNOWN | finnone-utilities / imps | penny-drop-style *validate* is usually a read/lookup, but P2A may move ₹1 → **UNKNOWN**, confirm (if it moves money it's an ACTION+idem) |
| LMSVIEWBYCRN_1.0 | finnone-payment-details (`/lms/crn-details`) | R | sync | call | **T1** | no | house? | finnone-utilities | **same host as BANK_DTLS/FinnOne_Payment** → LMS is not a separate family |
| LMSVIEWBYLOAN_1.0 | finnone-payment-details (`/lms/loan-details`) | R | sync | call | **T1** | no | house? | finnone-utilities | ditto |

`T1?` / `house?` = **contingent on the response shape** — T1 if it returns the house envelope (reuse the shared
mapper / passthrough), T2 if bespoke. **This is the single biggest UNKNOWN** and needs the real response payloads
to resolve; see §4.

---

## 3. The payoff — 30 svcNames collapse to ~3–4 capabilities

- **~19 reads → ONE generic FinnOne/LMS sync-utilities capability** (the `verification` route pattern), each read a
  **config route row**; a mapper **only** where the response isn't the house envelope. LMS folds in here (not its
  own capability).
- **~7 actions → ONE idempotent-action capability** (the `imps` pattern), each an idempotent route; the FIRST is the
  capability build, the rest are config + a request mapper.
- **~3 FileNet document ops → ONE document capability** (different transport: `bpm_getDocuments` + a binary upload on
  `:8084`) — a genuinely new adapter (T4).
- **1 inbound callback → a callback ingress** (edge/consumer, like the digital-edge decision consumer), not an
  outbound call.
- **New journeys from this list: 0.** Every row is a single request→response **call**. Journeys (loan-origination,
  servicing, …) already exist and would **compose** these as nodes; none of the 30 is itself a DAG.

**Costed total (with the UNKNOWNs stated):**

> **30 svcNames ≈ 3–4 new capabilities + ~26 config route rows + M mappers + 0 new journeys**,
> where **M is UNKNOWN** until the response payloads are seen (M is small — likely single digits — *if* the FinnOne
> reads speak the house envelope; larger if each has a bespoke shape).

Contrast the legacy shape (≈30 hand-built wrappers/procs — visible in the paths: `filenet-wrapper-aj-getdocs-proc`,
a separate `finnone-*-sys[-nd]` service per call). **The census is the argument for the platform:** dozens of
wrappers become a handful of capabilities + a config table.

---

## 4. Open questions (resolve before building — these change the numbers)

1. **Facade vs per-endpoint (pivotal).** Is LMS a `callLmsUtilities` facade or per-endpoint (as the catalog shows)?
   Decides whether we generalize `lms-utilities` onto routes or keep the facade. → IDFC / real API.
2. **Response payloads for every read.** Needed to turn each `T1?` into T1 (house envelope → reuse shared mapper) or
   T2 (bespoke mapper). This is what sizes **M**. → sample responses per svcName.
3. **read-vs-action verbs** for the ambiguous rows: `*_FORECLOSURE` (amount-quote READ per `lending-servicing`, or
   execute?), `DUESREFRESH` (read or recompute?), `BRAND_PORTAL_ROLLOVER_AMT` (read amount or create?),
   `IMPS_TO_VALIDATE_CUSTOMER` (lookup, or a ₹1 penny-drop that MOVES money?). Getting this wrong is
   **safety-critical** (see §5). → the request contract + HTTP verb.
4. **`-sys-nd` vs `-sys` host split** (foreclosure, payment-posting appear as both) and the **same-URL version pairs**
   (`BANK_DTLS_1.0/2.0`, `GETIFSCDETAILS`/`GETMULTIIFSCDETAILS`, `GETDOCUMENTSSERVICE`/`_ATTACH`): failover? env?
   migration variant? client-contract marker? → FinnOne team. Don't encode until known.
5. **Action idempotency contracts.** Do the FinnOne action APIs (`SUBMIT`/`PAYMENT`/`POSTING`/`INSERT`/`UPLOAD`)
   accept a caller idempotency key (like IMPS `idempotentId`)? If not, the action capability must **fail-fast on 5xx**
   (classify AMBIGUOUS, never blind-retry). → the action request contracts.
6. **FileNet transport** — `bpm_getDocuments` + `emorphis/filenet/upload` (multipart/binary, port 8084): confirm
   content type + auth; this is the one genuinely new adapter.

---

## 5. Retry-safety line (ties to the customer-party/posidex fix)

The **read-vs-action axis is a safety axis, not just a cost axis.** A read can safely retry a 5xx (idempotent —
re-reading is free). An **action cannot retry a 5xx without an idempotency key** — a retried foreclosure, payment,
posting, or submit can **double-execute**. So for every row marked **kind=A**, the capability MUST implement
idempotency (the `imps` `idempotentId` dedup) **before** its retry policy is allowed to classify 5xx as retryable;
absent a downstream idempotency contract (Q-5), those actions must treat a post-send failure as **AMBIGUOUS** and
fail closed rather than blind-retry. This is the same discipline as the customer-party `TRANSIENT`-vs-`PERMANENT`
fix — mis-classify an action as freely-retryable and you get double money movement.

---

*Reference only — no capabilities built, no `main` changes. Building is task #38, gated on resolving §4.*
