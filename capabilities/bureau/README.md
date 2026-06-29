# bureau capability

The single place the whole bank pulls credit-bureau data — **the headline
consolidation** (bureau-fetch was duplicated 4-5x across the old services). It
**fans out** across the requested bureaus, normalizes each to one canonical
shape, and merges. Bureau **only fetches data**; it does NOT make a credit
decision (that is the `scoring` capability).

## Contract

Implements THE CAPABILITY CONTRACT (`shared:shared-domain`):

- consumes `cap.bureau.request.v1` (`CapabilityRequest`)
- produces `cap.bureau.response.v1` (`CapabilityResponse`)

**Input** (`request.payload()`): applicant identity (`pan`, `name`, …), plus
optional `bureauTypes` (e.g. `["CIBIL","MULTI_BUREAU","COMMERCIAL"]`), `purpose`,
`consentRef`. When `bureauTypes` is absent, the configured default
(`idfc.bureau.default-bureau-types`) is used.

**Result:**
```json
{
  "bureauResults": [
    {"type":"CIBIL","score":780,"grade":"A","reportId":"...","source":"cibil","fetchedAt":"..."}
  ],
  "bureauScore": 780, "bureauGrade": "A", "reportId": "CIBIL-..."
}
```
`bureauResults[]` is the full canonical set (one per bureau). The flat
`bureauScore`/`bureauGrade`/`reportId` are the **primary** (the CIBIL result, or
the most conservative — lowest — score across bureaus), read by name by `scoring`
and the engine's branch, so the fan-out never silently inflates the score.

## Architecture (multi-vendor fan-out)

Hexagonal; the domain is framework-free. One OUT port **per vendor**
(`CibilBureauPort`, `MultiBureauPort`, `CommercialBureauPort`,
`ScorecardInfraPort`, all `BureauVendorPort`), each with a mock and (for the
external ones) a real HTTP adapter, chosen by config. `BureauFetchService` pulls
the requested bureaus **in parallel** and merges. Adding a bureau = one adapter +
config, never a new service. Callers never see vendor shapes.

Config (`idfc.bureau.*`): `default-bureau-types`, and per vendor `{mode, url}`
(`cibil`, `multi-bureau`, `commercial`). In compose the same bureau mock serves
`/cibil/report`, `/multibureau/report`, `/commercial/report` (high by default,
low when `applicationRef` matches `/LOW/i`). The internal `scorecard-infra`
backing is mock-only.

> // TODO: optional Aerospike cache (short TTL) — bureau pulls are rate-limited /
> charged; and a parity harness for the real-vendor cutover (post-demo).
