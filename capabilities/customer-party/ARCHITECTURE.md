# Architecture — Customer/Party Capability
*(keep this file in: capabilities/customer-party/ARCHITECTURE.md)*

## 1. What this is (one line)
Resolves and deduplicates the customer for an application against the bank's customer source-of-truth
(CDP/Posidex) — it does NOT own the customer master; it integrates with it.

## 2. Why it exists (the business problem)
Customer resolution (find/dedup the customer, fetch CRN/customerId) was happening inside multiple services,
each calling Posidex/CDP its own way. This capability centralizes "resolve the customer" once.

## 3. Classification (why it's an INTEGRATION capability, not a built master)
**Confirmed (A1): CDP/Posidex IS the customer source-of-truth.** So this capability does not BUILD a customer
master — it INTEGRATES with the existing one. It owns the "resolve/dedup" function, not the customer data.
This is an important distinction for cross-questions: we are not rebuilding CDP.

## 4. Design approach
- Hexagonal. domain: CustomerProfile + resolve logic (framework-free). port/out: PosidexPort.
- adapter/in/kafka: implements the engine's capability contract (consume request -> resolve -> respond).
- adapter/out/posidex: real HTTP to Posidex (URL via config -> docker mock for demo -> real Posidex in prod).
- Stateless: it resolves and returns; the customer record lives in CDP/Posidex.

## 5. Business flow handled
1. Engine sends a customer-party request (applicant identity: name, dob, pan...).
2. Capability calls Posidex (dedup + lookup) -> resolves the customer.
3. Returns { customerId/crn, profile, isExisting } in the response result -> the journey proceeds to bureau.

## 6. Key decisions & rationale
| Decision | Why |
|---|---|
| Integration, not a built master | A1: CDP/Posidex is the SoR — we don't duplicate it |
| Stateless resolve | the capability owns the function, not the data |
| Posidex behind a port | vendor is an adapter; swappable; URL via config |

## 7. What it is NOT (scope boundary)
Does not own/store the customer master (CDP does). No KYC (that's the KYC capability — identity verification is
a different function from customer resolution). No scoring. No journey logic.

## 8. External dependencies
Posidex/CDP (customer SoR — docker mock for demo, real in prod), Kafka (engine contract).

## 9. Likely cross-questions & answers
- "Aren't you rebuilding the customer master?" -> No. CDP/Posidex stays the SoR; we integrate, we resolve.
- "Why a separate capability then?" -> Because customer-resolution is a distinct, reusable function every
  journey needs; centralizing it removes the duplicated Posidex calls scattered across services.
