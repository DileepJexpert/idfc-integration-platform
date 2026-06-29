# Architecture Index + Cross-Question Armory
*(keep this at repo root: ARCHITECTURE_INDEX.md — the map + the answers to the hard questions)*

## The per-module docs (each lives in its module)
| Module | Doc | One-line |
|---|---|---|
| edges/sfdc-ingress-edge | ARCHITECTURE.md | thin async SFDC entry; dedupe + normalize + route; NO business logic |
| orchestration/origination-journey | ARCHITECTURE.md | config-driven DAG engine; composes capabilities; journey = data not code |
| capabilities/customer-party | ARCHITECTURE.md | resolve/dedup customer via CDP/Posidex (integration, not a built master) |
| capabilities/bureau | ARCHITECTURE.md | fetch+normalize credit-bureau data; ONE place (was duplicated 4-5x) |
| capabilities/scoring | ARCHITECTURE.md | the credit DECISION (APPROVED/REJECTED + reasons) |
| capabilities/lending-origination | ARCHITECTURE.md | book the loan in FinnOne (Oracle stored proc, not REST) |

## The shape (how they fit)
```
SFDC -> [sfdc-ingress-edge] -> [origination-journey ENGINE] -> composes:
   customer-party -> bureau -> scoring -> [branch] -> lending-origination -> decision back to SFDC
```
Edge = channel door. Engine = sequencer (config). Capabilities = the reusable business functions. Vendors
(Posidex/CIBIL/FICO/FinnOne) = adapters behind ports (docker mocks in demo, real in prod by config).

## The five design principles (say these when asked "what's the approach?")
1. Capability = business meaning; deployable = physics. Boundaries by business function, never by
   channel/vendor/transport.
2. One concept, one owner — exactly one capability writes each record; others read.
3. Capabilities never call each other — only the engine composes them (sync journey or async events).
4. New request = endpoint/adapter/config on the owning capability — NEVER a new service. (Anti-fragmentation.)
5. Edge dumb, core rich, platform shared — auth/idempotency/secrets/tracing live once in the platform.

## THE CROSS-QUESTION ARMORY (rehearse these)

**"Isn't this just another rewrite / won't it sprawl again like the 70 services?"**
The 70 came from a lift-and-shift that made one Mule flow = one service, so capabilities got shattered (SFDC
side) or fused into god-services (digital side). We fix the CAUSE: capabilities are extracted ONCE and shared;
a new product/partner/sequence is a CONFIG change (a journey DAG or a binding), enforced by a governance rule
— new request = config/adapter, never a new service. That rule is what stops re-sprawl.

**"Why is this not a monolith if it's fewer services?"**
Each capability is an independently deployable service with its own scaling, deploy cadence, and ownership.
~70 -> ~20-25 deployables, but still many small services — composed, not merged. The engine composes them
async; they never call each other. That's the opposite of a monolith.

**"How does it scale / handle peak onboarding?"**
The load that spikes is origination requests/sec. The edge fast-ACKs and buffers to Kafka (burst = queue depth,
not a crash). Elastic stages (KYC/Bureau/Scoring) scale on lag independently. The one hard limit — FinnOne
(a stored proc on Oracle) — is protected by a bounded pool, so a 10x burst never amplifies onto it; the backlog
drains. We add pods to the elastic stages; we never hammer the hard limit. (Demoable: the 10x burst test.)

**"What stops a double loan if SFDC or the user resends?"**
Atomic idempotency at the edge. Two concurrent identical requests -> exactly one wins (Aerospike CREATE_ONLY).
A user-resend after a perceived failure (new id, same application) is caught by the composite key
(sfdcRecordId+applicationRef). Booking runs once per approved journey. This is tested under real concurrency.

**"Where is the loan actually decided / is it a black box?"**
Bureau fetches the data; Scoring applies the policy and returns APPROVED/REJECTED WITH reason codes. The
decision rules are config-as-data, so risk can change policy without a code release, and every decision is
explainable (score + reasons). Auditable end-to-end via the engine's journey state.

**"You're not rebuilding FinnOne / CDP / the bureaus, right?"**
Correct. FinnOne (loan SoR), CBS/BaNCS (money SoR), CDP/Posidex (customer SoR), the bureaus — all stay. We
build INTEGRATION capabilities and adapters over them. We remove DUPLICATION (e.g. 4-5 copies of the bureau
call become one), we don't rebuild cores.

**"Config not code — show me."**
The journey is a DAG authored in the DAG Designer (versioned, maker-checker approved, audited) and run by the
engine. Adding a fraud step or a new partner = edit the DAG / add a binding -> approve -> publish. No new
service, no code deploy. (Demoable: the Designer renders the live journey; the engine runs that exact config.)

**"How is this safe for a regulated bank?"**
Secrets in Vault (not inlined — we cleaned that anti-pattern). One audited place per capability for bureau
access / KYC / decisions. Maker-checker + audit trail on journey changes. Idempotency prevents double money
movement. Adapters isolate vendor quirks. Data plane (reporting) is off the hot path.

**"Why these specific capability boundaries?"**
Each is evidence-based — derived by reading the actual service code (the SERVICE_ANALYSIS profiles), not
guessed. E.g. Bureau is separate from Scoring because the code showed bureau-fetch is duplicated and distinct
from decisioning; Origination is separate from Servicing because they write different systems-of-record. The
boundaries are documented with the code evidence in the consolidation context file.

**"What's real in the demo vs mocked?"**
Every IDFC service is real and really composed over Kafka. The external VENDORS (Posidex/CIBIL/FICO/FinnOne)
are Dockerized mock servers speaking the real contract — our adapters hit them over the wire. Swapping a mock
for the real vendor is a CONFIG change (the URL/datasource), zero code change. That's the honest boundary.
