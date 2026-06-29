# Demo — Digital Partner Edge Prompt (the "same core, different channel" proof)

> THE most important demo piece for the thesis: a SECOND edge that proves a digital partner loan flows through
> the SAME engine + SAME capabilities as the assisted/SFDC loan — only the edge differs. No separate repo, no
> separate stack. Build in the existing monorepo, module edges/digital-partner-edge (currently a stub).
> Mirror edges/sfdc-ingress-edge exactly in quality + structure.

---

## What this proves
The SFDC edge (assisted) and this digital edge enter through DIFFERENT doors (async SOAP vs sync partner REST)
but publish the SAME canonical origination envelope to the SAME engine, which runs the SAME journey over the
SAME capabilities (customer-party -> bureau -> scoring -> lending-origination). Swap the edge; the entire middle
is identical. That is the "one platform, no separate repo for digital" proof.

## The contrast that makes the demo land
| | Assisted (SFDC edge) | Digital (this edge) |
|---|---|---|
| Channel | Salesforce | Fintech partner (CRED/Flipkart/GROWW) |
| Transport | async SOAP Outbound Message | synchronous REST |
| Trigger | SFDC pushes | partner calls our API |
| Partner | n/a (assisted) | partner = config (CRED/FLIPKART/...) |
| **Engine + capabilities** | **IDENTICAL** | **IDENTICAL** |
| **Canonical envelope** | **IDENTICAL** | **IDENTICAL** |

---

## THE PROMPT (paste to Claude Code)

You are building the digital-partner-edge in the existing monorepo idfc-integration-platform, module
edges/digital-partner-edge (currently a stub). STUDY edges/sfdc-ingress-edge first and mirror its hexagonal
structure, conventions, and quality bar EXACTLY. This edge is the digital twin of the SFDC edge — it must
produce the SAME canonical origination envelope so the SAME engine and capabilities handle it unchanged.

WHAT IT DOES: receive a loan-origination request from a fintech PARTNER (CRED/Flipkart/GROWW) over
SYNCHRONOUS REST, authenticate the partner, validate, dedupe, normalize to the SAME canonical envelope the
SFDC edge produces, route by (source=DIGITAL, partner, type=business-line), and publish to the SAME origination
Kafka topic the engine consumes. The partner is CONFIG (not a service) — adding a partner = a config row.

KEY DIFFERENCES FROM THE SFDC EDGE (only these; everything else identical):
- Inbound is synchronous REST (POST /api/v1/digital/origination), not async SOAP. The partner gets an
  immediate ACK with an applicationId; the decision comes back via callback/polling (for the demo: the edge
  fast-ACKs with applicationId, and the decision is pushed to a partner-callback mock OR exposed via a status
  endpoint — mirror the SFDC push-back pattern, partner-side).
- source = DIGITAL; the envelope carries partner (CRED/FLIPKART/GROWW) as a config-driven field.
- Partner auth (per-partner credentials/keys via config — for the demo a simple token per partner; real is
  Kong/per-partner). Secrets via config, never inlined.

WHAT MUST BE IDENTICAL (this is the thesis — do NOT diverge):
- The CANONICAL ENVELOPE published to Kafka = the SAME shape the SFDC edge publishes (correlationId,
  transactionId, orgId/partnerId, type, applicationRef, payload/s3Ref). Reuse the shared envelope type.
- The origination Kafka TOPIC = the same one the engine consumes (the engine must not care which edge sent it).
- IDEMPOTENCY = the SAME platform store + the SAME composite-key dedupe (partner retries are at-least-once too;
  a partner resend with a new request id but same application must not double-book — composite key applies).
- The engine + capabilities are UNTOUCHED.

ARCHITECTURE: hexagonal, like the SFDC edge. domain (framework-free): reuse the canonical envelope + dedupe
domain (share via shared/shared-domain if the SFDC edge already factored it there; if not, mirror it and note
the future extraction). adapter/in/rest: the partner REST controller. adapter/out/kafka: publish canonical
envelope to the origination topic. adapter/out/idempotency: reuse platform-idempotency (or the same Aerospike
store/port the SFDC edge uses). adapter/out/partnercallback: push decision back to a partner callback (mock) /
status endpoint. config: partner registry as config-as-data (CRED/FLIPKART/GROWW -> auth + callback URL).

STACK/CONVENTIONS: same as the SFDC edge (Java 21, Spring Boot 3.4.5, Gradle Kotlin DSL + buildSrc, Aerospike,
Kafka, Testcontainers, Resilience4j, Micrometer+OTel; group com.idfcfirstbank,
package ...integration.digitaledge.*).

TESTS (same bar as the SFDC edge): dedupe/composite-key unit tests; an integration test (Testcontainers Kafka
+ Aerospike): a partner REST request -> canonical envelope on the SAME origination topic -> fast-ACK with
applicationId; a resend (new request id, same application) -> no double-publish (composite key). Crucially,
assert the published envelope is SHAPE-IDENTICAL to what the SFDC edge produces for the same logical loan
(this is the thesis test — same envelope, different edge).

SCOPE FENCE: implement ONLY edges/digital-partner-edge. Do NOT touch the engine or capabilities — the whole
point is they DON'T change. Do NOT build a new journey — it reuses the same origination journey. If a task
requires changing the engine or a capability to make digital work, STOP and flag (it shouldn't — if it does,
the canonical envelope isn't truly shared, which is the bug to fix).

BUILD ORDER:
1. Fill the module (build.gradle conventions, hexagonal structure, Spring Boot app).
2. Reuse/mirror the canonical envelope + dedupe domain + idempotency port. STOP for my OK.
3. Partner REST inbound + auth + validate + normalize + route + fast-ACK.
4. Kafka publish (same envelope, same topic) + partner-callback/status for the decision.
5. Tests incl. the "envelope identical to SFDC edge" thesis test. README: how a partner loan flows through the
   SAME engine + capabilities, and how to run it.

Start with step 1, then step 2; STOP after the envelope/dedupe reuse is in place for my review.
