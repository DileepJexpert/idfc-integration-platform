# bureau capability

Fetches a canonical credit bureau report + score from CIBIL (the vendor). Bureau
**only fetches data** — it does not make a credit decision (that is the `scoring`
capability).

## Contract

Implements THE CAPABILITY CONTRACT (`shared:shared-domain`):

- consumes `cap.bureau.request.v1` (`CapabilityRequest`)
- produces `cap.bureau.response.v1` (`CapabilityResponse`)

Topics derive from `CapabilityTopics.request("bureau")` / `.response("bureau")`.
JSON String serde, matching the engine.

Input: applicant identity from `request.payload()` (`pan`, `name`).
Result: `{ "bureauScore": <int>, "bureauGrade": "<A|B|C>", "reportId": "<id>" }`.
The `bureauScore` key is read by name downstream (scoring + the engine).

## Architecture

Hexagonal; the domain is framework-free. CIBIL sits behind the `CibilPort` OUT
port with two adapters, chosen by `idfc.bureau.cibil.mode`:

- `mock` (default) — `MockCibilAdapter`, deterministic; PAN containing `LOW`
  yields 540/C, otherwise 780/A. No docker vendor needed.
- `real` — `CibilHttpAdapter`, HTTP `POST /cibil/report` to `idfc.bureau.cibil.url`
  (docker mock in compose, real CIBIL in prod, no code change).
