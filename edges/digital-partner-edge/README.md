# digital-partner-edge

The **digital twin of the SFDC edge**: fintech partners (CRED / Flipkart / GROWW)
originate loans over **synchronous REST**, and this edge normalizes each into the
**SAME shared canonical envelope** on the **SAME origination topic** the engine
consumes. The engine and capabilities are **untouched** — that is the thesis.

```
partner ──POST /api/v1/digital/origination──▶ digital-partner-edge
   (X-Partner-Token)                              │ auth → validate → dedupe → normalize
                                                  ▼
                                  SAME CanonicalEnvelope (source=DIGITAL)
                                  SAME topic orig.sfdc.<type>.v1
                                                  ▼
                                   origination-journey ENGINE (unchanged)
                                   customer → kyc → bureau → scoring → [branch] → book
                                                  ▼
                          orig.decision.v1 ─▶ DecisionConsumer ─▶ partner callback / status
```

## Why it only differs by the door

| | SFDC edge (assisted) | this edge (digital) |
|---|---|---|
| Transport | async SOAP outbound | **sync REST** |
| Trigger | SFDC pushes | partner calls us |
| Partner | n/a | **config row** (token + callback) |
| Canonical envelope | — | **IDENTICAL shared type** |
| Origination topic | — | **SAME** |
| Idempotency store | — | **SAME** Aerospike sets |
| Engine + capabilities | — | **UNCHANGED** |

The envelope has **no partner field** — partner is derived from auth, tracked
edge-side (status store) and sent as a Kafka header, never in the shared body. So
the engine genuinely cannot tell which channel sent the request.

## Endpoints

- `POST /api/v1/digital/origination` — header `X-Partner-Token`; body
  `{requestId, applicationRef, type, orgId, payload}`. Fast-ACK
  `{applicationId, status, detail}`. `200` ack · `422` unroutable · `401` unknown
  partner · `503` transient (retry).
- `GET /api/v1/digital/applications/{applicationId}` — partner status/poll.
- Decision returns to the partner via the logged callback (mock) when
  `orig.decision.v1` arrives.

## Dedupe (partner resends are at-least-once too)

Two atomic CREATE_ONLY gates on the **same platform store**: the request id
(exact resend), then `partner + applicationRef` (a new id for the same
application). Neither double-publishes. The richer transient-replay state-machine
is the SFDC edge's C2/C3, a future extraction to `platform-idempotency`.

## Tests

- `DigitalDedupeTest` — composite-key dedupe (exact resend, new-id-same-app,
  distinct apps, unroutable, deterministic applicationId).
- `EnvelopeShapeIdentityTest` — **the thesis**: the digital envelope's JSON field
  set is identical to the SFDC edge's; only `source` differs.

## Run (config-as-data)

Partners and routing live in `application.yml` (`idfc.digital-edge.*`). Onboarding
a partner is a config row — no new service. In compose the edge listens on `:8081`
and points at `kafka:9092` + `aerospike`. See the root `demo.sh digital`.
