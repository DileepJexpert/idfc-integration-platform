# IDFC Integration Platform — Backend Build Roadmap

| | |
|---|---|
| **Status** | Sequencing decision — governs what gets built, in what order |
| **Rule** | Strangler-fig, one slice at a time, each proven before the next (from the Build Charter + SKILL) |

> Why this doc exists: to stop the failure mode of "build everything at once." The whole consolidation is
> escaping a big-bang. So is its own construction. This locks the order and, crucially, what must be TRUE
> before each piece starts.

---

## The five backend workstreams (dependency order)

```
[1] SFDC ingress edge  ── DONE (Slice 1) ──┐  proves: edge pattern, idempotency PLATFORM primitive,
                                           │         backpressure. Produces the reusable Aerospike store.
                                           ▼
[2] Bureau capability  ◀── NEXT NEW BUILD ─┘  highest duplication payoff (4-5x); establishes the
                                           │  CAPABILITY pattern (core + vendor adapters + harvest +
                                           │  parity + strangler cutover) that every capability reuses.
                                           ▼
[3] KYC capability  ──────────────────────┐  repeat the capability pattern; stateful (owns KYC state).
                                           │
                        ≥2 capabilities now exist ──┐
                                                    ▼
[4] Orchestration engine + journey registry  ◀── only NOW (premature before ≥2 capabilities to compose).
                                           │   The DAG Designer (separate Flutter app) writes to this
                                           │   registry; its config schema MUST co-lock with this engine.
                                           ▼
[5] Remaining capabilities  ──────────────┘  Lending-Origination, Payments, Lending-Servicing, Accounts,
                                              Collections, Compliance, Documents, Communications —
                                              each a repeat of the capability pattern, in slice order.
```

---

## Sequencing rules (non-negotiable)

1. **One slice at a time. Each green before the next starts.** No parallel half-built capabilities.
2. **Don't pre-extract platform.** The idempotency store stays INSIDE sfdc-ingress-edge until a SECOND
   consumer (Bureau) needs it. The extraction to `platform-idempotency` happens AS PART OF the Bureau slice
   (it's the first reuse) — not before, not speculatively.
3. **Don't build the orchestration engine until ≥2 capabilities exist.** Building "the thing that composes
   capabilities" against zero real capabilities = building against imagination. It waits for Bureau + KYC.
4. **The DAG Designer and the orchestration engine share ONE config schema** — neither's schema is final
   until they co-lock it. The Designer can be built earlier against a PROVISIONAL schema (mock backend), but
   the engine's loader and the Designer's serializer must agree byte-for-byte before either goes live.
5. **Every capability slice follows the SAME method** (the capability pattern — see the Bureau doc):
   absorb → harvest real contracts → build core as superset → vendor-as-adapter → parity-run vs the old
   duplicated calls → strangler cutover by org/partner → retire old.
6. **Capability ≠ deployable is decided per slice.** Default 1:1; split for scaling/ownership only when the
   real load says so; merge tiny related ones (Documents+Communications) only if they change together.

---

## Build order with preconditions

| # | Build | Precondition (must be TRUE first) | Produces / proves |
|---|---|---|---|
| 1 | SFDC ingress edge | (none — first slice) | DONE: edge + idempotency primitive + backpressure |
| 2 | **Bureau capability** | Slice 1 green (idempotency pattern proven) + Bureau boundary locked (it is) | the CAPABILITY pattern; collapses 4-5x duplicated bureau fetch into one; platform-idempotency extracted |
| 3 | KYC capability | Bureau green (capability pattern proven) | stateful capability pattern; KYC state + vendor adapters |
| 4 | Orchestration engine + registry | ≥2 capabilities exist (Bureau+KYC) + config schema co-locked with DAG Designer | journeys-as-config runtime; the registry the Designer writes to |
| 5a | Lending-Origination | engine exists (to compose it) + Slice-1 idempotency reused | FinnOne stored-proc booking adapter |
| 5b | Payments | engine + Origination | router over rail adapters (IMPS/BillDesk/Montran/SI→CBS) |
| 5c | Lending-Servicing | Payments (shares mandate/CBS adapters) + A2 confirmed | absorbs dl-lms-handler IN FULL (parity-proven) |
| 5d | Accounts/CASA, Collections, Compliance, Documents, Communications | engine + relevant adapters | remaining capabilities, each a pattern repeat |

---

## What is NOT built yet (and why) — guard against premature work

- **Orchestration engine** — premature until Bureau + KYC exist. Design captured; build deferred to #4.
- **platform-auth / platform-config / platform-messaging** — extracted from real need, not speculatively.
  platform-auth gets real when the first REAL (non-mock) external auth is needed (Bureau's CIBIL call).
- **The remaining 10 capabilities** — speculative until their slice. Boundaries are locked in the context
  file; builds wait their turn.
- **DAG Designer** — separate Flutter repo; can start earlier against a mock backend + provisional schema,
  but is a CONTROL-PLANE tool, not on the critical path of the backend slices.

---

## The through-line
Each slice makes the next cheaper: the edge proved idempotency; Bureau proves the capability pattern; KYC
repeats it; two capabilities justify the engine; the engine makes every later capability a journey-config
exercise. Build in this order and the estate consolidates itself one proven step at a time — which is the
entire thesis, applied to the build as much as to the architecture.
