# Architecture — Lending-Origination Capability
*(keep this file in: capabilities/lending-origination/ARCHITECTURE.md)*

## 1. What this is (one line)
Books the loan in FinnOne (the lending system-of-record) once a decision is APPROVED — creating the loan
record and returning the loan account number.

## 2. Why it exists (the business problem)
Loan booking was happening via finnone-submit + finnone-onboarding-disbursal. This capability is the clean home
for "create the loan", distinct from servicing a live loan.

## 3. Classification (Origination vs Servicing — a LOCKED boundary)
There are TWO lending capabilities, split by lifecycle:
- **Origination (this one):** CREATES the loan — writes to FinnOne. (finnone-submit + onboarding-disbursal)
- **Servicing (separate):** operates on a LIVE loan (foreclosure, refund, mandates) — reads FinnOne, writes
  CBS/SFDC/Montran. (finnone-integration + dl-lms-handler)
They are separate because they write different systems and change for different reasons. (Evidence: context
section 7C.)

## 4. Design approach (the ODD-ONE-OUT adapter — anticipate this cross-question)
- Hexagonal. domain: LoanBooking + booking logic. port/out: FinnOneBookingPort.
- **CRITICAL:** FinnOne is accessed via an **Oracle STORED PROCEDURE (SP_FINNONE_SUBMISSION), NOT REST.** So
  the adapter is **JDBC + CallableStatement + JSON<->XML**, not an HTTP client. This is the one adapter in the
  estate that is not HTTP — model it correctly.
- adapter/in/kafka: engine contract. adapter/out/finnone: JDBC stored-proc adapter (datasource via config ->
  Oracle-XE docker mock for demo with the proc defined -> real FinnOne in prod).

## 5. Business flow handled
1. Engine sends a booking request (only reached on an APPROVED decision, via the branch).
2. Capability calls SP_FINNONE_SUBMISSION (JDBC) with the application data -> FinnOne creates the loan.
3. Returns { loanId/LAN, status: BOOKED } -> terminal node -> decision pushed back to SFDC.

## 6. Key decisions & rationale
| Decision | Why |
|---|---|
| FinnOne via JDBC stored proc | that is how FinnOne is actually accessed — NOT REST (evidence-confirmed) |
| Origination separate from Servicing | different SoR-writes, different lifecycle — a locked boundary |
| FinnOne = loan SoR (not CBS) | FinnOne owns the loan record; CBS/BaNCS owns the money — distinct SoRs |
| Idempotency on booking | booking is money-adjacent — must not double-book (ties to the edge's dedupe) |

## 7. What it is NOT (scope boundary)
Does not service live loans (that's Servicing). Does not move money to CBS (that's Servicing/Payments). Does not
decide the loan (Scoring did — this only runs on APPROVED). Does not do KYC/bureau.

## 8. External dependencies
FinnOne (Oracle stored proc — Oracle-XE docker mock for demo, real FinnOne in prod via config datasource).
Kafka (engine contract).

## 9. Likely cross-questions & answers
- "Why JDBC and not an API?" -> Because FinnOne exposes booking via an Oracle stored procedure
  (SP_FINNONE_SUBMISSION), not a REST API. The adapter wraps that faithfully; the rest of the system doesn't
  care — it's behind a port.
- "What stops a double-booking?" -> The edge's idempotency (dedupe on the application) ensures one journey per
  application; booking only runs once per approved journey.
- "FinnOne vs CBS/BaNCS?" -> FinnOne is the loan system-of-record (the loan); CBS/BaNCS is the money/accounts
  system-of-record. Origination writes the loan to FinnOne; money movement (servicing) is CBS.
