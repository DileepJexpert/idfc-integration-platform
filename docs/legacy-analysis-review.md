# Legacy Service Analysis — Consolidated Review & Migration Findings

**Status: REFERENCE + TRACKING ONLY. This document authorises NO code changes.**
The migration-target build (file edge, `foreach`, generic-http capability, sync lane) is
**gated on the pattern census** (§6). Do not build any of it from this document.

> Sanitization note: this repository is public. One legacy UAT hostname fragment was
> redacted from §5.1; no credentials, hostnames, or SFDC org ids appear in this file.

---

## 1. Services profiled so far

Four services from the API-Integration-workspace, read from their `SERVICE_ANALYSIS.md`:
generic-wrapper, brand-wrapper (partial), brand-details (partial), **hrapps-integration-service** (full).

## 2. The load-bearing confirmed verdict: org = config, not code (×4)

Every service profiled independently confirms it: **orgId is pass-through / traceability only** —
never a routing, config, or branching axis. generic-wrapper and hrapps both state it explicitly
("ONE service, ONE code path, N orgIds passed through as payload data"). The "~70 orgs" is SFDC-side
business divisions, not 70 of anything in the integration layer. **This is the single most important
architectural confirmation** and it is now four-for-four. The platform's org-as-config design is correct.

## 3. Interaction-pattern taxonomy (discovered, not assumed)

hrapps alone contains **five** patterns in one deployable — the richest service found:

| Pattern | Estate example | Platform status |
|---|---|---|
| Async request/response (Kafka in → downstream → Kafka out) | hrapps employee-details; generic-wrapper | ✅ **built** |
| Sync read (call downstream, wait, return) | hrapps Fusion GET worker | ⚠️ **sync lane — designed, not built** |
| Fire-and-forget job (HTTP trigger → return → background work) | hrapps LWD `/hrms-lwd/trigger` | ⚠️ **job lane — sweeper seam exists, not built** |
| **File/batch (SFTP → CSV → foreach → downstream → per-record status)** | hrapps LWD `LwdSchedulerService` | ❌ **UNBUILT — no file edge, no `foreach` execution** |
| Notification output (email results / blank-file alert) | hrapps LWD email report | ⚠️ mail adapter — not built |
| Stateful callback (owns lifecycle state) | emandate callback (earlier) | ✅ capability pattern exists |
| Pure transport (config-driven proxy) | generic-wrapper pass-through svcNames | ⚠️ generic-http capability — not built |

**Every pattern maps to an existing architecture concept** (capability, adapter, `foreach`, file edge as
a new sibling edge). None requires a redesign. The file-batch lane is the largest unbuilt piece.

## 4. hrapps-integration-service — full profile

HR integration hub. Downstreams: Oracle Fusion HCM (REST, OAuth+JWT), Cygnet (PDF signing, POST),
internal OAuth token service, SFTP server, SMTP/email. **All four downstreams org-agnostic** ("URL/auth
vary by orgId? NO" on every one). Stateless pass-through, owns no DB/cache.

- **Cygnet document-signing = a plain outbound adapter call** (POST PDF, config-based auth). NOT a stateful
  signing lifecycle — no callback consumer, no pending/signed state machine. (Earlier TBD: resolved → adapter.)
- **LWD job = file-batch pipeline:** pull employee CSV off SFTP → parse → (empty → email alert) →
  **per-record** loop updating Fusion with individual SUCCESS/FAILURE status → email results CSV.
  Per-record error handling is correct (one bad record doesn't sink the batch); **but** the overall job
  runs fire-and-forget with a swallow-and-log catch (the P1 anti-pattern) and has no idempotency on re-run.

## 5. Legacy-system findings (independent of migration — escalate against legacy)

1. **[SECURITY / CLOCK] Live credential pasted in a SERVICE_ANALYSIS.md** (`Authorization: Basic`,
   a UAT host — name withheld: public repo). Scrub before any commit, rotate the credential, add a
   secret-scan pre-commit hook. **Do not commit the analysis files until scrubbed.**
2. **[SECURITY] Credentials travel inside every Kafka message** (generic-wrapper `EndpointConfig` carries
   BASIC auth + client secret). Live secrets sit in Kafka retention, DLQs, S3 offload copies, and Kafka UI.
   Standalone legacy exposure. Fix (if legacy survives >~2 quarters): ship a `secretRef`, resolve at call time.
3. **[P1-INVESTIGATE] Duplicate-write window** (generic-wrapper `UPI_CreateMandate`): no dedup store +
   Kafka retry + 5s timeout → a slow-but-successful create gets redelivered and **creates a second mandate**.
   Verify FSS server-side dedup on the record id. If absent, this is a **live production double-mandate risk.**
4. **[SECURITY] Customer payloads persist in S3** (`requestLarge=Y` offload) beyond Kafka retention —
   bucket retention/masking policy needed (same review as #1–2). S3 object tagging discipline is good, keep it.
5. **Fail-open on unknown orgId** (both generic-wrapper and hrapps: unknown orgId accepted and processed,
   no validation/allowlist). The platform's fail-closed-on-unknown-enum is the direct counter-design.
6. **"OATH" is a frozen wire contract**, not a typo to fix — both real examples carry `"authType":"OATH"`.
   Any successor accepts it forever OR normalises at the boundary; **never "fix" it in place** (breaks ~70 orgs).

## 6. Platform gaps for the migration target — DO NOT BUILD until census (§8)

1. **Generic-http capability** — one config-driven caller (host/path, **method incl. GET/PUT not just POST**,
   auth type, timeouts) in the registry, executed through the existing RetryExecutor/idempotency/breaker.
   Pass-through svcNames collapse into it; custom-mapper svcNames become real capability operations.
2. **secretRef seam** — registry configs hold references, never credential values; resolved at call time
   (env now, vault later). Current fail-closed startup tokens cover service-to-service, not per-downstream creds.
3. **Sync lane** — a blessed "call-and-return, no engine" path for sync reads (hrapps Fusion GET). Must be
   documented so reads are NOT forced through async journeys.
4. **File-batch lane** — file/SFTP edge (sibling to SFDC/digital edges) → `foreach` execution (in §7 schema,
   unbuilt) → per-record status → email-report output → empty/partial-file handling. **The largest new build.**

## 7. Decisions to make NOW (cheap; expensive to retrofit) — notes, not code

- **Org-scoping note:** orgId rides in run context, selects per-org config in the registry, never forks a
  journey. Decide where the config keys live (shapes the registry schema). One page.
  → stub: `docs/decisions/org-scoping.md`
- **Payload-size budget:** platform chose inline payloads (P3.11); legacy has real >1MB payloads (why S3
  offload exists). Minimum: a documented size limit + explicit rejection behaviour. Offload seam only if
  migrated traffic needs it.
  → stub: `docs/decisions/payload-size-budget.md`

## 8. THE GATE: pattern census before any migration-target build

hrapps proved one service hides five patterns. ~60 services remain unprofiled. **Before designing or
building the generic-http capability, the sync lane, or the file-batch lane, run the pattern census**
(classify every remaining service by interaction pattern + downstream type, get counts-per-pattern). The
census determines whether file-batch is 3 services or 25 — i.e. whether the file edge is a small slice or a
workstream. Building the target before the census risks discovering an entire pattern mid-migration with
nowhere to land — exactly what hrapps would have caused.

---

## Record of permitted actions (executed 2026-07-03)

Per the advisor's instruction accompanying this document, the ONLY changes made to this repo from it:

1. This file, as the reference/decision record (supersedes the earlier single-service review).
2. §7's two decision stubs under `docs/decisions/` (org-scoping, payload-size-budget) — decision pending.
3. §5 items added to the README's legacy-escalations tracking; one follow-ups line:
   generic-http capability + secretRef + sync lane + file-batch lane — **GATED on pattern census,
   do not build speculatively.**

No capability, no file edge, no `foreach` execution was built. The credential scrub (§5.1) applies to
the legacy workspace's analysis files, which live outside this repository — it cannot be verified from
here and remains **unconfirmed** until done there.
