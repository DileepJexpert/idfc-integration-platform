# Architecture — Bureau Capability
*(keep this file in: capabilities/bureau/ARCHITECTURE.md)*

## 1. What this is (one line)
Fetches and normalizes credit-bureau data (CIBIL / Multi-Bureau / Commercial Bureau) into one canonical
response — the single place the whole bank pulls bureau data, replacing 4-5x duplicated bureau code.

## 2. Why it exists (the business problem — the HEADLINE consolidation)
**This is the highest-value consolidation in the whole programme.** Today, fetching credit-bureau data is
DUPLICATED 4-5 times: scorecard-analyser, cibil-management, dl-eligibility, and the fico-* services EACH have
their own CIBIL/Multi-Bureau HTTP client, own URLs, own auth. There is NO shared bureau service today. This
capability extracts that into ONE. (Evidence: context file section 7B — each of those services makes its own
direct CIBIL call.)

## 3. Classification (why it's a DATA-FETCH capability, NOT scoring)
Bureau is pure data retrieval. It fetches the bureau report and normalizes it. It does NOT score, decide
eligibility, or apply rules — that is the Scoring/Decisioning capability (a separate capability). This boundary
matters: confusing "fetch bureau data" with "decide on the loan" is exactly the mistake the old services made.

## 4. Design approach
- Hexagonal. domain: canonical Bureau request/response + the fan-out/normalize logic. ports/out: one per
  vendor (CibilBureauPort, MultiBureauPort, CommercialBureauPort, ScorecardInfraPort).
- Adapters translate each vendor's specific shape to/from the canonical shape. Callers NEVER see vendor shapes.
- adapter/in/kafka: the engine contract. Vendors via config URLs (docker mocks for demo, real in prod).
- Optional cache (Aerospike, short TTL) since bureau pulls are rate-limited/charged.

## 5. Business flow handled
1. Engine sends a bureau request (applicant + which bureauTypes + purpose + consentRef).
2. Capability fans out to the requested bureaus in parallel, normalizes each response to canonical, merges.
3. Returns { bureauResults: [{ type, score, normalized report, source, fetchedAt }] } -> journey proceeds to
   scoring.

## 6. The canonical contract (anticipate this cross-question)
ONE clean API (the superset of what all the absorbed services needed). Adding a new bureau vendor = a new
adapter + config, NOT a new service. This is the anti-fragmentation rule made concrete.

## 7. Key decisions & rationale
| Decision | Why |
|---|---|
| Extract into ONE capability | bureau-fetch is duplicated 4-5x today — the biggest dedup win |
| Pure data-fetch (no scoring) | decisioning is a different capability; keep the boundary clean |
| Canonical in/out; vendors as adapters | callers don't see vendor shapes; new bureau = adapter + config |
| Internal scorecard infra as backing | scorecard.dev-infinity already partially fronts bureaus — formalize it |
| Secrets via Vault/config | the absorbed services inlined CIBIL keys/Basic-auth — cleaned on the way in |

## 8. What it is NOT (scope boundary)
Not scoring/decisioning (separate capability). Not KYC (CKYC/CERSAI is Central KYC, NOT credit bureau — a
common confusion; they are different capabilities). Does not decide the loan.

## 9. External dependencies
CIBIL / Multi-Bureau / Commercial Bureau (via internal scorecard infra + direct) — docker mocks for demo, real
vendors in prod via config. Kafka (engine contract). Optional Aerospike (cache).

## 10. Likely cross-questions & answers
- "Why one Bureau service — isn't that a bottleneck?" -> It scales horizontally (stateless fetch); and it
  REMOVES 4-5 duplicated integrations, each separately maintained, separately failing. One place to secure,
  rate-limit, cache, and audit bureau access — which a regulator prefers.
- "What about different bureaus (Experian/Equifax)?" -> Each is an adapter behind a port. Adding one = config +
  adapter, not a new service.
- "Is this where the loan decision is made?" -> No. Bureau fetches data. Scoring decides.
