# Architecture — Digital Partner Edge
*(keep this file in: edges/digital-partner-edge/ARCHITECTURE.md)*

## 1. What this is (one line)
The thin synchronous entry point that receives loan-origination requests from fintech PARTNERS (CRED, Flipkart,
GROWW) over REST — the digital twin of the SFDC edge — feeding the SAME engine and capabilities.

## 2. Why it exists (the business problem — the THESIS)
Today digital lending is built as god-services SEPARATE from the assisted/SFDC stack, each rebuilding the same
KYC/bureau/scoring/lending. This edge proves digital needs only a thin DOOR onto the SAME shared core — no
separate repo, no separate platform. It is the proof that "one platform serves all channels."

## 3. Classification (why it's an EDGE, like the SFDC edge)
Differs from the SFDC edge ONLY by channel (partner) and transport (sync REST vs async SOAP). Same job:
authenticate, validate, dedupe, normalize to the SAME canonical envelope, route, ACK. No business logic.

## 4. Design approach
- Hexagonal, mirroring the SFDC edge. Reuses the SAME canonical envelope, the SAME idempotency store/port, the
  SAME origination topic. The engine cannot tell which edge sent the request — that is the design goal.
- Synchronous REST inbound; partner auth (per-partner config); fast-ACK with an applicationId; decision returns
  via partner callback/status (the partner-side push-back, mirroring SFDC's record-update push-back).
- Partner = CONFIG (CRED/FLIPKART/GROWW are config rows, not services). Adding a partner = config.

## 5. Business flow handled
1. A partner (e.g. CRED) calls POST /api/v1/digital/origination with a loan application.
2. Edge authenticates the partner, validates, dedupes (composite key — partner retries are at-least-once too),
   normalizes to the canonical envelope (source=DIGITAL, partner=CRED), publishes to the SAME origination topic.
3. Fast-ACK with applicationId. The SAME engine runs the SAME journey over the SAME capabilities.
4. Decision returns to the partner via callback/status.

## 6. The thesis property (anticipate this cross-question)
The canonical envelope this edge publishes is SHAPE-IDENTICAL to what the SFDC edge publishes (there's a test
asserting this). So the engine + capabilities are UNCHANGED to serve digital. "I didn't change a line of the
core to add digital — I added a thin edge." That is the no-separate-repo proof.

## 7. Key decisions & rationale
| Decision | Why |
|---|---|
| Same canonical envelope + topic + engine | proves the core is channel-agnostic; no separate digital stack |
| Sync REST (vs SFDC async) | partners call us and wait for an ACK; decision via callback — partner-appropriate |
| Partner = config | a new partner is a config row, not a new service (anti-fragmentation) |
| Same idempotency store | partner resends must not double-book either — composite key applies |

## 8. What it is NOT (scope boundary)
Not a new journey (reuses origination-journey). Not a new capability set (reuses the shared core). If making
digital work required changing the engine/capabilities, that would be a BUG (the envelope isn't truly shared).

## 9. External dependencies
Fintech partners (inbound REST + callback), Kafka (same origination topic), the same idempotency store. Engine
+ capabilities reused unchanged.

## 10. Likely cross-questions & answers
- "Doesn't digital need its own platform?" -> No. It needs this one thin edge. The engine and all capabilities
  are untouched — proven by the envelope-identical test.
- "How is a partner onboarded?" -> A config row (auth + callback) + optionally a journey/binding. No new service.
- "Assisted and digital share the same bureau/scoring?" -> Yes — that's the whole point. One bureau capability,
  one scoring capability, serving every channel. No duplication.
