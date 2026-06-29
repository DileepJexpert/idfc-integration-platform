# origination-journey — the orchestration engine

Kafka-driven engine that executes journeys authored by the DAG Designer. It loads
the **locked journey contract**, starts a run per inbound origination envelope,
dispatches one capability request per task node, advances on each capability
response, routes branches, and pushes the final decision back.

Hexagonal: the DAG-walk logic (`domain/service/JourneyEngine`) is framework-free
and fully unit-tested; Kafka and instance state sit behind OUT ports.

## THE CAPABILITY CONTRACT — every capability implements this

Defined once in `shared:shared-domain`
(`com.idfcfirstbank.integration.shared.domain.capability`). Capabilities depend on
that module and use these types — do NOT fork a near-identical copy.

```java
record CapabilityRequest(
    String journeyInstanceId,   // engine-assigned, correlates one run
    String correlationId,       // end-to-end trace id (from the inbound edge)
    String capabilityKey,       // e.g. "scoring"
    String nodeId,              // the DAG node this invocation is for
    Map<String,Object> payload, // run input (applicant identity, request fields)
    Map<String,Object> collectedResults) // upstream results so far, keyed by capabilityKey

record CapabilityResponse(
    String journeyInstanceId,   // echo
    String correlationId,       // echo
    String nodeId,              // echo (how the engine routes the reply)
    String capabilityKey,       // echo
    CapabilityStatus status,    // OK | ERROR
    Map<String,Object> result)  // capability output, e.g. {"decision":"APPROVED"}
```

**Topics** (`CapabilityTopics`):

| direction | topic |
|---|---|
| engine → capability | `cap.<capabilityKey>.request.v1` |
| capability → engine | `cap.<capabilityKey>.response.v1` |

Messages are JSON (String serde). The engine consumes responses via the pattern
`cap\..*\.response\.v1`, so adding a capability needs no engine change.

### How a capability reads upstream data

`collectedResults` is keyed by `capabilityKey`. E.g. scoring reads the bureau
score with `request.collectedResults().get("bureau")` → `{"bureauScore": 780}`.

### Decision push-back

On reaching a terminal node the engine emits a `JourneyDecision`
(`outcome` = APPROVED|REJECTED|ERROR, plus `loanId` when a booking ran) to the
decision topic (`idfc.engine.decision-topic`, default `orig.decision.v1`).

## Config-as-data (`application.yml`, prefix `idfc.engine`)

- `journey-resources` — classpath journey contracts to load
- `origination-topics` — inbound envelope topics (the edge's origination topic)
- `decision-topic` — where the final decision is published
- `type-to-journey` — businessLine(`type`) → journey key (empty ⇒ single loaded journey)

## Tests

- `./gradlew :orchestration:origination-journey:test` — fast, Docker-free unit
  suite: pure engine (approved/rejected/error), expression evaluator, contract
  loader (parses the locked fixture into the domain graph), and a full
  edge→engine→fleet→decision flow with fake ports (both branch outcomes).
- `:integrationTest` — Testcontainers Kafka (tag `integration`), added in the
  wiring step.

## Known cross-repo note

The locked journey includes a **`kyc`** task node (customer → kyc → bureau →
scoring → decide → book). The minimal demo flow in the build prompts omitted KYC,
but since the journey is the authoritative contract, the demo needs a **kyc**
responder (capability or mock) for the flow not to stall at that node.
