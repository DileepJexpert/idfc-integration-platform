# Slice 1 (SFDC Edge) — Pre-Code Punch List

| | |
|---|---|
| **Status** | Build-readiness gate — clear this before writing code (SUPERSEDES the generic DoD in the FLOW doc) |
| **Date** | 28 June 2026 |
| **Builds against** | `Slice 1 — SFDC Ingress Edge — FINAL FLOW` (LOCKED) |
| **Rule** | Every item is either **LOCKED** (decision taken — build it) or **CONFIRM** (external answer gates correctness) |

---

## A. The one blocking confirm (do not finish Slice 1 without it)

**A1 — id stability on resend.** *Owner: SFDC/Apex (not Mule dev).*
Two precise questions:
- On resend, is **`notificationId` reused / stable?** → this is the dedup key.
- On resend, is **`correlationId` new per request?** → confirms it is NOT a dedup input.

**Decision regardless of answer (build defensively):** implement a **composite dedup key** from
day one — primary `notificationId`, fallback `sfdcRecordId + applicationRef`. If A1 confirms
`notificationId` is stable, the fallback stays dormant; if not, it is already in place. **No rewrite
either way.** This removes A1 from the critical path for *starting*, while keeping it blocking for
*sign-off*.

**A2 — screen behaviour.** *Owner: SFDC/Apex.* Confirm the showroom screen is **record-driven**
(reacts to the SFDC record update), not holding a sync call. Evidence says async/push-back already,
so the edge changes nothing for the user — **default: record-driven**; confirm to be certain.

---

## B. The two id fields — roles locked (so dedupe is never wired to the wrong one)

| Field | Role | Stable across resend? | Used for dedupe? | Propagated where |
|---|---|---|---|---|
| **`notificationId`** | **Identity** — "same business event?" | Must be (A1) | **YES — the dedup key** | dedup store key |
| **`correlationId`** | **Trace** — "trace this one HTTP call" | No — new per request | **NEVER** | logs, traces, **Kafka headers**, the SFDC push-back at [9] — threaded end-to-end |

**LOCKED rule:** `correlationId` ≠ dedup key. Store the **original** `correlationId` on the dedup
record; on a resend, **log the resend's `correlationId` as `resendOf=<original>`** so a
duplicate-looking trace is explainable. Cheap to add, painful to lack.

---

## C. Tightenings to the FINAL FLOW (ambiguous → locked)

**C1 — DECIDED resend (§3).** Spec says "re-push the decision *or rely on the record already being
updated*." → **LOCKED: always re-push the decision, idempotently** (SFDC record update is the
idempotent sink). Never assume the prior push landed — assuming success is the anti-pattern §5 warns
against.
> **Ownership guard (refinement):** ONLY the CAS transition INTO `DECIDED` triggers the push-back.
> Resend reads NEVER push — a resend that reads `DECIDED` returns/re-pushes idempotently against the
> already-updated record sink; a resend that reads `IN_FLIGHT` re-attaches and does NOT push. This
> removes the in-flight→decided race ambiguity about who is responsible for the push (so it can't fire
> twice or zero times).

**C2 — ACK boundary on errors (§5).** Misclassifying drops loans. → **LOCKED rule:**
- **ACK + DLQ** only on **provably permanent** errors: schema-invalid, unknown-org *after a config
  refresh*, malformed envelope.
- **Do NOT ACK** (let SFDC redeliver) on **possibly-transient**: Kafka publish failure, store
  unavailable, config-not-yet-loaded, timeout. Default for *unclassified* errors = **transient**
  (fail safe toward redelivery, not toward silent loss).
> **Unknown-org disambiguation (refinement):** "unknown org" and "stale config" look identical at
> runtime. On unknown-org: **trigger a config refresh, re-check ONCE, then classify** — found-after-
> refresh = transient/proceed; still-absent-after-refresh = permanent/ACK+DLQ. Do not classify
> unknown-org as permanent without the refresh-and-recheck step.

**C3 — FAILED/DLQ resend (§3).** "Re-enqueue once if transient" is not a spec. → **LOCKED:** max
**1** automatic transient re-enqueue, then DLQ-for-ops. A **booking/money-adjacent** failure
**never** auto-reprocesses without re-checking the dedup guarantee. "Transient" = the C2 transient
set, nothing else.

**C4 — state-transition atomicity (gap).** The atomic *insert* prevents double-journey; the
**status transitions** must be equally atomic (CAS), or a resend can read `RECEIVED`, re-attach, and
race a concurrent `RECEIVED→DECIDED` flip into a second decision push. → **LOCKED:** every status
change is **compare-and-set on expected prior state**; the resend read-and-act path uses the same
CAS, not a plain read.

**C5 — edge-level poison / redelivery-loop breaker (NEW — refinement).** A message that is
well-formed + known-org but **fails repeatedly in a way that always looks transient** (never reaches a
clean Kafka publish) creates an infinite SFDC↔edge redelivery loop that never DLQs — because each
individual failure looks transient and C3 only governs the *journey* re-enqueue, not the *edge-level*
redelivery loop. → **LOCKED:** track redelivery count per `notificationId` at the edge; after **N**
redeliveries that never reach a clean publish, stop treating it as transient → **DLQ-as-poison + ACK +
ops alert**, even though each attempt looked transient. This bounds the one loop C1–C4 don't close.

---

## D. Idempotency store — schema, semantics, TTL, tech (the §6 "atomic store", made concrete)

**Record:**
| Field | Type | Notes |
|---|---|---|
| `notificationId` | string (PK) | dedup key |
| `sfdcRecordId` | string | part of composite fallback (A1) |
| `applicationRef` | string | part of composite fallback (A1) |
| `status` | enum | `RECEIVED` → `IN_FLIGHT` → `DECIDED` \| `FAILED` |
| `decision` | blob/json (nullable) | approved/rejected + terms + `applicationId`, set at DECIDED |
| `originalCorrelationId` | string | the first request's trace id |
| `orgId` | string | for ops/triage |
| `receivedAt` / `updatedAt` | timestamp | lifecycle |
| `retryCount` | int | enforces C3 ceiling |
| `redeliveryCount` | int | enforces C5 edge-loop ceiling |

**Semantics (non-negotiable):**
- **Insert = insert-if-absent (atomic).** Two concurrent identical keys → exactly one winner starts
  the journey. *This is THE Slice-1 correctness test.*
- **Every update = CAS on expected status** (C4).

**TTL — LOCKED rule:** retention **must exceed SFDC's maximum retry/redelivery window** (get the
number from A1's owner). A `notificationId` that expires *before* SFDC can still resend = a silent
double-book. **Default until confirmed: 30 days**, then revisit against the real SFDC window. Do not
ship a TTL shorter than the confirmed window.

**Tech — DECISION (LOCKED): AEROSPIKE** — it is the only datastore the org has, and it is a strong fit
for an idempotency store (key-value, atomic single-record ops, native TTL):
- **atomic insert-if-absent** = `RecordExistsAction.CREATE_ONLY` (write fails if key exists → exactly one
  winner under concurrency).
- **CAS on status** = `GenerationPolicy.EXPECT_GEN_EQUAL` (read generation, write only if unchanged).
- **TTL** = native Aerospike record TTL (no scheduled purge job; set TTL ≥ SFDC retry window, default 30d).
- **Ops confirm (note in README):** verify the namespace behaves atomically for CREATE_ONLY +
  generation-check under cluster config (strong-consistency namespace recommended).
- **The concurrency test runs against a REAL Aerospike (Testcontainers)** — never an in-memory fake, since
  CREATE_ONLY atomicity is the thing under test. (Postgres is NOT used — Aerospike only.)

---

## E. Routing — seed `type → topic/downstream` map (org-config-as-data)

Routing is `(source=SFDC, type=business-line)`. Seed set for Slice 1 (extend via config, never code):
| `type` (business line) | Origination topic | Downstream journey |
|---|---|---|
| `PERSONAL_LOAN` | `orig.sfdc.pl.v1` | origination-journey |
| `LAP` (loan-against-property) | `orig.sfdc.lap.v1` | origination-journey |
| `BUSINESS_LOAN` | `orig.sfdc.bl.v1` | origination-journey |
| `COMMERCIAL` | `orig.sfdc.commercial.v1` | origination-journey |
| `<unknown>` | — | **DLQ** `ConfigNotFoundException` + ACK (C2 permanent, AFTER refresh-recheck) + ops alert |

**LOCKED:** the type set and topic mapping live in **org-config-as-data** (the config store), seeded
with the above; adding a business line = config row, not code (governance rule). Confirm the actual
business-line codes with the SFDC payload owner; the names above are placeholders pending that.

---

## F. Parity oracle — the allowlist (empty allowlist = undefined parity)

Parallel-run vs live Mule asserts: **same input → same canonical envelope + same routing decision +
same downstream call.** Differences allowed (LOCKED allowlist):
- timestamps (`receivedAt`, `updatedAt`) — generated, will differ
- `correlationId` — per-request, will differ
- field **ordering** in the envelope — non-semantic
- added platform fields absent in Mule (e.g. `transactionId`, `originalCorrelationId`)

**Payload equivalence (refinement — define it, don't leave it a judgment call):** parity on payload =
the **RESOLVED payload is byte-equal** to Mule's payload. If our side uses an S3 claim-check (`s3Ref`)
and Mule inlines the body, fetch the `s3Ref` first and compare the resolved bytes. The claim-check
indirection is NOT itself a parity diff; the resolved content must match.

**Everything else is a parity bug**, specifically: dedup verdict, `notificationId`, `orgId`, `type`,
routing target/topic, `sfdcRecordId`, resolved-payload equivalence. Diffs outside the allowlist block
cutover.

---

## G. Slice-1 scope fence (hold this line)
- The **FinnOne backpressure harness IS in Slice 1**, but **only as a metering harness against a mock
  stored proc that enforces the concurrency cap N** (§6). It proves: FinnOne concurrency ≤ N under a
  10× burst, edge stays healthy, backlog drains to zero.
- It **must not** grow into real FinnOne integration, real journey logic, or any real capability
  (KYC/Bureau/Scoring/Lending). Those are later slices. If a task touches a real capability, it is
  out of Slice 1.

---

## H. Definition of Done — additions to the spec's DoD
The FINAL FLOW §7 DoD stands, plus:
- [ ] Composite dedup key implemented (primary + fallback), even if A1 confirms primary is stable.
- [ ] `correlationId` propagated through Kafka headers + push-back; `resendOf` logged; never a dedup input.
- [ ] All five C-tightenings encoded (C1 re-push + ownership guard, C2 ACK boundary + unknown-org refresh-recheck,
      C3 retry ceiling, C4 CAS transitions, C5 edge poison/redelivery-loop breaker).
- [ ] Store TTL ≥ confirmed SFDC retry window (or 30d default with a TODO to confirm).
- [ ] Parity allowlist (§F) implemented incl. resolved-payload (s3Ref-fetched) equivalence; out-of-allowlist diff fails the run.
- [ ] Seed routing map (§E) loaded from config; unknown-type → refresh-recheck → DLQ+ACK path tested.
- [ ] **THE correctness test:** concurrent identical `notificationId` → exactly one winner starts the journey
      (atomic insert) AND no double decision-push under concurrent status transitions (CAS). This is the sign-off gate.

---

## Build-readiness verdict
**Start condition met when:** A1/A2 are *asked* (answers can arrive during build because the composite
key + record-driven default de-risk them), and items B–H are encoded in the build spec. The only thing
that can *fail sign-off* rather than *delay start* is A1 (id stability) and the store's
atomic-CAS-under-concurrency test (DoD final item).

**You can begin building the edge now** with the decisions above locked; keep A1 open as a tracked
confirm, not a blocker, because the defensive composite key makes either answer non-breaking.

---

## Architect sign-off (2026-06-28)
Signed off to build. Four refinements folded in: C1 ownership guard (only the DECIDED transition pushes),
C2 unknown-org refresh-recheck, C5 edge poison/redelivery-loop breaker, F resolved-payload parity definition.
No new scope, no new structure — error-handling and parity edges closed. Build order: idempotency store first
(atomic insert-if-absent → CAS transitions → hammer with concurrent identical keys until exactly-one-winner is
proven), then the thin edge around it, then fast-ACK→Kafka + metered FinnOne harness + 10× burst test.
