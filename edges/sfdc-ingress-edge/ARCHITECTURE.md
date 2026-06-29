# Architecture — SFDC Ingress Edge
*(keep this file in: edges/sfdc-ingress-edge/ARCHITECTURE.md)*

## 1. What this is (one line)
The thin async entry point that receives loan-origination requests from Salesforce, dedupes them, normalizes
them to a canonical envelope, and hands them to the orchestration engine — without doing any business logic.

## 2. Why it exists (the business problem)
Today **6 separate sfdc-* services** handle the Salesforce inbound path (request, splitter, response,
composite-response, non-composite, etc.). They were created by a lift-and-shift that turned each Mule flow into
its own microservice. Result: one logical job (receive from SFDC) is shattered across 6 deployables with
duplicated plumbing. This edge consolidates all 6 into ONE thin edge. **6 services -> 1.**

## 3. Classification (why it's an EDGE, not a capability)
It is an EDGE because it differs from other entry paths only by CHANNEL (Salesforce) and TRANSPORT (async SOAP
Outbound Message), not by business function. Channels are edges; business functions are capabilities. The edge
does NO decisioning — it authenticates, validates, dedupes, normalizes, routes, ACKs.

## 4. Design approach
- **Hexagonal (ports & adapters):** the domain (dedupe logic, canonical envelope, error taxonomy) is
  framework-free; all externals (Salesforce, auth, Kafka, store) sit behind ports.
- **Async fast-ACK:** receives the request, dedupes, drops to Kafka, ACKs "received" immediately. The decision
  comes back later via the push-back path. This is the existing SFDC pattern (at-least-once, ack/retry).
- **Idempotency-first:** the load-bearing piece. Built and tested before anything else.

## 5. Business flow handled
1. Showroom submits a loan in Salesforce -> SFDC fires an async Outbound Message to the edge.
2. Edge authenticates (two-token: Hydra enterprise + Kong SFDC), validates, extracts keys.
3. **Dedupe** on notificationId (+ composite fallback) -> new request proceeds; duplicate re-attaches.
4. Normalize to canonical envelope; large payloads via S3 claim-check.
5. Route by (source=SFDC, type=business-line) to the origination Kafka topic.
6. Fast-ACK to SFDC. Journey runs async; decision pushed back to SFDC later; the showroom record updates.

## 6. The hard problem it solves: idempotency (anticipate this cross-question)
SFDC is at-least-once: it redelivers (even after ACK) and the salesperson can resend. Without dedupe, the same
application books TWICE -> a real double-loan incident.
- **Atomic insert-if-absent** (Aerospike CREATE_ONLY): two concurrent identical requests -> exactly ONE wins.
- **Composite key:** primary notificationId; fallback sfdcRecordId+applicationRef. The fallback is LOAD-BEARING
  because a user-resend after a *perceived* failure arrives with a NEW notificationId but the SAME application
  — only the stable application key prevents a double-book of a loan that actually succeeded.
- **Four dedupe paths:** new (proceed) / in-flight (re-attach, no second journey) / decided (return outcome) /
  failed (controlled retry). Only the CAS transition INTO DECIDED pushes the decision back — so it fires once.

## 7. Scale story (anticipate this cross-question)
The load that spikes is origination requests/sec. Mechanisms:
- Async fast-ACK -> a burst becomes Kafka **queue depth**, not blocked threads/crash.
- **FinnOne backpressure:** FinnOne is a stored proc on Oracle (hard concurrency limit). The path toward it is a
  BOUNDED pool (max N) — a 10x burst NEVER amplifies onto FinnOne; the backlog drains when the burst ends.
- Idempotency is itself a load reducer (duplicates do no work).
We scale by adding pods to the elastic stages, never by hammering the one hard limit (FinnOne).

## 8. Key decisions & rationale
| Decision | Why |
|---|---|
| Aerospike for the store | only datastore the org has; CREATE_ONLY=atomic insert, generation-check=CAS, native TTL — ideal fit |
| TTL >= SFDC retry window | a key expiring before SFDC can still resend = silent double-book |
| Edge is thin (no business logic) | channel concerns only; keeps the capability core channel-agnostic |
| correlationId = trace only | dedupe must key on identity (notificationId), never on a per-request trace id |

## 9. What it is NOT (scope boundary)
Not a capability. No KYC/Bureau/Scoring/Lending logic. No journey logic (that's the engine). Just the SFDC door.

## 10. External dependencies
Salesforce (inbound + push-back), Hydra+Kong (auth), Kafka (buffer), Aerospike (idempotency store), S3 (claim-
check). For the demo, all mocked behind ports except Aerospike+Kafka (real, local).
