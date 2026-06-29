# Architecture — Scoring / Decisioning Capability
*(keep this file in: capabilities/scoring/ARCHITECTURE.md)*

## 1. What this is (one line)
Takes the applicant + bureau data and produces the actual credit DECISION (APPROVED / REJECTED + reasons) —
this is where the loan decision is genuinely made.

## 2. Why it exists (the business problem)
The real decisioning logic lives in scoring engines (FICO, scorecards, BSA). Today it's reached through a maze
of orchestrators (fico-loan-eligibility, scorecard-analyser) that fan out and merge. This capability is the
clean home for "decide the loan" — invoked by the journey, returning a decision the branch routes on.

## 3. Classification (why it's DECISIONING, distinct from Bureau)
Bureau FETCHES data; Scoring DECIDES. Separating them is deliberate: data retrieval and decisioning change for
different reasons (a new bureau vs a new risk policy) and must be independently ownable. This capability owns
the decision; it consumes bureau data as input.

## 4. Design approach
- Hexagonal. domain: ScoringDecision + the decision rule (pure, unit-tested — the most important tests here).
  port/out: FicoPort (and/or internal scorecard).
- adapter/in/kafka: engine contract. adapter/out/fico: real HTTP to FICO (URL via config -> mock for demo).
- The decision threshold/rules are config-as-data (a policy change = config, not code).

## 5. Business flow handled
1. Engine sends a scoring request with the applicant + the bureau result (from collectedResults).
2. Capability calls FICO (and/or applies the bank's decision rules) -> derives a decision.
3. Returns { decision: APPROVED|REJECTED, score, reasons[] } -> the engine's BRANCH node routes on this.

## 6. Key decisions & rationale
| Decision | Why |
|---|---|
| Separate from Bureau | decisioning vs data-fetch change for different reasons; independent ownership |
| Decision rules as config | a policy change (threshold, rule) = config, not a code deploy |
| FICO behind a port | vendor is an adapter; swappable; real decisioning can be FICO + bank rules |
| Reasons[] in the response | regulators/ops need WHY a loan was rejected — carry the reason codes |

## 7. What it is NOT (scope boundary)
Does not fetch bureau data (that's Bureau — it consumes the result). Does not book the loan (that's Lending-
Origination). Does not own customer data. It decides, and only decides.

## 8. External dependencies
FICO / internal scorecard engines (docker mock for demo, real in prod via config). Kafka (engine contract).

## 9. Likely cross-questions & answers
- "Where is the loan actually decided?" -> Here. Bureau gives the data; Scoring applies the policy and returns
  APPROVED/REJECTED with reasons.
- "Can risk change the policy without a release?" -> Yes — the rules/threshold are config-as-data; a policy
  change is a config change, audited, not a code deploy.
- "Is this a black box?" -> No — the response carries score + reason codes, so every decision is explainable.
