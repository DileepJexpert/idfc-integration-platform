# scoring capability

The **decisioning** capability. Consumes `cap.scoring.request.v1`, reads the
upstream bureau result from `collectedResults["bureau"].bureauScore`, enriches via
FICO (the vendor), applies a pure decision rule, and replies on
`cap.scoring.response.v1` per THE CAPABILITY CONTRACT (`shared:shared-domain`).

## Decision rule

`APPROVED` when `bureauScore >= threshold` **and** there are no negative flags;
otherwise `REJECTED`. The `decision` field is what the engine's branch node routes
on (`decision == 'APPROVED'` / `decision == 'REJECTED'`).

Result map: `{ "decision": "APPROVED"|"REJECTED", "score": <int>, "reasons": [..] }`.

## Hexagonal structure

- `domain/model/ScoringDecision` — the decision value object.
- `domain/service/DecisionRule` — the PURE, framework-free, unit-tested rule.
- `domain/port/FicoPort` — OUT port to the FICO vendor.
- `domain/port/CapabilityResponsePort` — OUT port to publish the response.
- `application/ScoringService` — framework-free handler.
- `adapter/in/kafka` — consumes the request topic.
- `adapter/out/kafka` — publishes the response topic.
- `adapter/out/fico` — `MockFicoAdapter` (local/test) + `FicoHttpAdapter` (real).
- `config` — `ScoringProperties` + `ScoringConfiguration` wiring.

## Config (`idfc.scoring`)

| key         | default                 | meaning                          |
|-------------|-------------------------|----------------------------------|
| `threshold` | `700`                   | bureau-score cutoff for APPROVED |
| `fico-mode` | `mock`                  | `mock` or `real` FICO adapter    |
| `fico-url`  | `http://localhost:19103` | FICO base URL (real mode)        |
