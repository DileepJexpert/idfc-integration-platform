# sfdc-user-management

A **sync-lane capability** (a library the digital edge invokes in-thread, like
`imps-disbursal` / `lms-utilities` — *not* the async journey engine) for the JMI
migration's **SFDC user / role / profile** operations. The caller blocks for the
answer; there is no `journeyInstanceId`, no Kafka, no engine state.

## Two config tables that compose

| table | key | value | chosen by |
|-------|-----|-------|-----------|
| `routes` | `svcName` | `path` (+ read/write flag) | the operation |
| `orgs`   | `orgName` | `baseUrl` (+ auth + enabled) | **which SFDC instance** |

**final target = `orgs[org].baseUrl` + `routes[svcName].path`.** The `svcName` route
mirrors the `verification` capability's `ConfigRouteResolver`; the **org table is the
one new architectural element**. Adding a svcName or an org is a config **row**, not code.

## org-as-egress-target — the ONE scoped exception (read this before "fixing" it)

Everywhere else on this platform, `org` / `partner` / `brand` are config **attributes**
and are **never** journey-routing keys (lending targets one SFDC; the org never forks the
path). **This capability is the deliberate, scoped exception:** its whole job is to fan a
request out to one of *several* SFDC org instances, so here the **org name selects the
egress target**. Two contexts, two rules, both intentional — the lending principle is not
abandoned. The rationale lives in `SfdcOrgRouteResolver`'s Javadoc.

## Fail closed / anti-SSRF

Endpoints come from **our** `orgs` table (a curated allow-list). The inbound message
supplies only the org **name** (a key into the table), never a URL — so a caller can't
point us at an arbitrary host. Unknown svcName (`NO_ROUTE`), unknown org (`UNKNOWN_ORG`),
disabled org (`ORG_DISABLED`), and a route path that tries to smuggle a host
(`BAD_ROUTE_PATH`) all fail closed as **PERMANENT**. There is **no default org** — the
legacy fail-open orgId is not reproduced.

## Reads vs writes (the posidex/imps safety line)

- **Reads** run on the sync lane with **no idempotency key**. Classification: 4xx →
  PERMANENT, 5xx / connect / IO → TRANSIENT, timeout → **TRANSIENT** (a read is safe to
  repeat).
- **Writes** (user create / update, role assign) require a **caller-supplied
  `idempotencyKey`** (the `imps-disbursal` store pattern: a duplicate key returns the
  prior result, never re-executes; the key is scoped by svcName+org). A write with no key
  is refused (`MISSING_IDEMPOTENCY_KEY`). Only **definitive** outcomes are cached — a 2xx
  success OR a business rejection; a **technical** failure (5xx / timeout / connect) is
  NOT cached, so a retry re-executes (safe only under the key). A write **timeout** is
  **AMBIGUOUS** (the mutation may have applied). Business vs technical: an SFDC 2xx with
  `success:false` (e.g. duplicate username) is a clean **BUSINESS** "no" (returned + audited
  as `BUSINESS_FAILURE`), never a technical error.

## Request shape

```
POST /api/v1/sfdcUserManagement        (digital edge; Authorization: Bearer <token>)
read:   { "svcName": "SFDC_USER_FETCH",  "orgName": "ORG_A", "payload": { ... } }
write:  { "svcName": "SFDC_USER_CREATE", "orgName": "ORG_A", "idempotencyKey": "...", "payload": { ... } }
```
`svcName`, `orgName` and (for writes) `idempotencyKey` are control fields — they select
route/target/dedup and are **not** forwarded to SFDC; `payload` is the operation body.
Every call writes one PII-safe sync-invocation audit row (ids only), exactly like imps/lms.

> **Ingress contract pending JMI confirmation.** Exposed here as JSON + Bearer on the
> assumption the consumers are internal apps (like the sync lane's other callers). If the
> real transport differs, only the edge controller changes — the capability is untouched.

## OUT OF SCOPE — the boundary this capability does NOT cross

**Bidirectional master-data / daily-delta synchronization** between consumer systems and
SFDC orgs is **not** built here and does not belong here. It is data-replication / CDC
territory (schedulers, watermarks, bulk diff) — it matches none of this platform's
request/journey patterns and stays with the requesting team's service. This capability
serves the **per-request API operations only**. If a scheduler or sync-state store starts
appearing, that is the boundary being crossed.

## Slice status

- **Slice 1:** org route table + one **read** (`SFDC_USER_FETCH`) end-to-end; org-routing
  proven (ORG_A vs ORG_B hit different hosts), fail-closed, audit-recorded.
- **Slice 2 (this):** **write** svcNames (`SFDC_USER_CREATE` / `SFDC_USER_UPDATE` /
  `SFDC_ROLE_ASSIGN`) with mandatory caller-supplied idempotency, definitive-only caching,
  and the read-vs-action retry-safety line (business `success:false` vs technical 5xx /
  AMBIGUOUS timeout). `SFDC_USER_UPDATE` / `SFDC_ROLE_ASSIGN` are config rows sharing the
  same path (mappers stay passthrough) pending confirmed JMI payloads.

## Dev vendors

Two WireMock org instances so routing is **provable**: `mock-sfdc-org-a` (`:19112`) and
`mock-sfdc-org-b` (`:19113`) in `docker-compose.infra.yml`; each response carries an
`org` field identifying itself (fetch + create, incl. a `DUPE_USER` → `success:false`
business-rejection case). Only the DATA is mocked — real SFDC orgs later = host + token
swaps in config, no code.
