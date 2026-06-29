# Architecture — Origination Journey (Orchestration Engine)
*(keep this file in: orchestration/origination-journey/ARCHITECTURE.md)*

## 1. What this is (one line)
The config-driven engine that runs a loan-origination journey by composing the shared capabilities in order,
tracking state, and branching on the credit decision — the journey is DATA (a DAG), not code.

## 2. Why it exists (the business problem)
Today every digital journey (CRED, Flipkart, GROWW, EBC, Asirvad, assisted) re-implements the SAME sequence
— customer -> KYC -> bureau -> scoring -> lending — privately, inside god-services. The sequence is hardcoded
and duplicated. This engine extracts the SEQUENCING into one place where a journey is a config-defined DAG, so
a new product/partner/sequence is a config change, not new code in a new service.

## 3. Classification (why it's ORCHESTRATION, not a capability)
It composes capabilities; it owns no business capability itself. It holds JOURNEY state (what ran, current
status) and is the audit source-of-truth for a journey instance. Capabilities never call each other — only the
engine composes them.

## 4. Design approach
- **Config-driven DAG:** reads a journey definition (nodes: task | branch | terminal; edges) as JSON — the SAME
  schema the DAG Designer authors. The engine is the runtime; the Designer is the authoring tool.
- **Async ping-pong over Kafka:** for each task node it publishes a request to that capability's topic and
  consumes the response; this is the pattern the org already runs (dl-loan-offer underwriting).
- **State machine:** a journey INSTANCE advances node by node; state persisted (Aerospike); branch nodes pick
  the next node from a response field (e.g. decision == APPROVED).
- **Hexagonal:** domain (the pure state-machine: advance, branch eval, terminal) is framework-free; Kafka/
  Aerospike are adapters.

## 5. Business flow handled
1. Receives the edge's origination message -> starts a journey instance from the DAG's startNode.
2. For each task node: send request to the capability (customer-party -> bureau -> scoring), await response,
   record result, advance.
3. At the branch node: evaluate the decision (APPROVED/REJECTED) -> route to booking or rejection.
4. On APPROVED: run lending-origination (book the loan).
5. At the terminal node: emit the final decision to the result topic -> the edge pushes it back to SFDC.

## 6. The capability contract (anticipate this cross-question)
Every capability implements one contract over Kafka:
- request: { journeyInstanceId, correlationId, capabilityKey, nodeId, payload, collectedResults }
- response: { journeyInstanceId, correlationId, nodeId, capabilityKey, status, result }
- topics: cap.<capabilityKey>.request.v1 / cap.<capabilityKey>.response.v1
This uniformity is WHY adding a capability to a journey is trivial — same contract, new node in the DAG.

## 7. Why config-not-code matters (the headline business value)
- A new partner (e.g. a new fintech) = a new binding/journey config, NOT a new microservice.
- A changed sequence (add a fraud step) = edit the DAG, push through maker-checker, publish. No deploy of new
  code, no new service.
- The DAG Designer makes this visible and auditable (versioning + maker-checker + audit trail).

## 8. Key decisions & rationale
| Decision | Why |
|---|---|
| Journey = config (DAG), not code | new product/partner/sequence becomes config; stops re-fragmentation |
| Async Kafka ping-pong | matches existing org pattern; decouples capabilities; absorbs bursts |
| Engine holds journey state | single audit source-of-truth for "what ran / current status" |
| Schema shared with DAG Designer | one contract, tested on both sides — the authoring tool and the runtime agree |
| Minimal (demo): happy path + 1 branch | retries/sagas/compensation are config-marked but not exercised live yet |

## 9. What it is NOT (scope boundary)
Not a capability (no KYC/Bureau/etc. logic). Not the authoring tool (that's the DAG Designer). For the demo it
does not execute compensation/sagas (marked in config, added later).

## 10. External dependencies
Kafka (composition + state events), Aerospike (journey state), the capabilities (over the contract), the DAG
Designer (shares the config schema). No external vendors directly — it composes capabilities that talk to vendors.
