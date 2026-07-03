# Legacy analysis review — generic-wrapper-service

Review of the `SERVICE_ANALYSIS.md` (“Multi-Org Consumer Analysis”) produced for
the legacy **generic-wrapper-service** in the API-Integration workspace,
2026-07-03. Sibling analyses (brand-wrapper, brand-details, hrapps-integration)
exist but are not yet reviewed — see §7.

> **Sanitization note.** This repository is public. This document deliberately
> contains **no hostnames, no credentials, no SFDC org ids, and no literal
> topic names** from the legacy estate. Where the analysis showed concrete
> values, they are described, not quoted.

---

## 1. What the legacy service is

A stateless Kafka-only executor. An upstream **splitter** service routes each
SFDC-originated request per org/service and publishes it to one shared wrapper
topic; the wrapper consumes, builds the downstream HTTP call **entirely from
the message itself**, executes it (internal APIs and external vendors), shapes
the response (normal / composite / large-via-S3 / error), and publishes it to
the response topic named *in the message*. Large request/response bodies are
offloaded to S3. The service owns no state: no DB, no Aerospike, no cache, no
dedup store.

The analysis verifies the core thesis: **~70 orgs ride ONE parameterized code
path** — no `switch`/`if`-chain/handler-map/strategy-registry on orgId
anywhere. Per-org behavior is data, carried in each message’s
`EndpointConfig` (target host/port/path, auth type + credentials, response
topic, response-shaping flags).

The architectural trade, named precisely: the design **bought statelessness by
putting the config — and the secrets — on the wire.** The splitter is the real
control plane; the wrapper executes whatever arrives.

## 2. Findings, ranked

### F1 — Credentials travel inside every Kafka message *(critical)*

`EndpointConfig` carries the BASIC `authorization` string and the OAuth client
id/secret as literal values, per message. Consequences:

- Secrets sit in Kafka log segments for the retention window, in every DLQ
  copy, in S3 copies of large requests, and on the screen of anyone browsing
  the topic in Kafka UI.
- Topic read access ≈ possession of every org’s downstream credentials.
- Rotation is structurally painful: in-flight and retained messages keep old
  credentials alive; there is no single revocation point besides topic ACLs.
- The analysis document itself reproduced one live-format BASIC authorization
  value in an example block. **Actions: redact it from the doc, rotate that
  credential (UAT or not — Base64 is encoding, not encryption), and add
  secret-scanning pre-commit hooks to that workspace before the analyses are
  committed or shared.**

### F2 — Duplicate-write window: retry × no idempotency *(high)*

Three verified facts compose badly:

1. The service “does not enforce idempotency (no dedup store)” — pure
   pass-through.
2. There is **no retry in the REST client**, but there IS Kafka-level retry
   (global error handler: 200 ms backoff, 2 retries) — i.e., redelivery
   re-runs the *whole* pipeline (S3 fetch, token fetch, HTTP call).
3. Response timeout is 5 s, and the HTTP method is hardcoded POST.

So a downstream create (e.g., mandate creation) that takes 6 s but
**succeeds** times out client-side, gets redelivered, and executes again — up
to three POSTs for one request, against operations that are not naturally
idempotent. **Action: confirm whether each write-shaped downstream dedupes on
the record id. Until confirmed, treat this as an open duplicate-side-effect
risk.** (This platform’s counter-design: engine-owned per-node retry with
`runId:node:attempt` idempotency keys; capabilities dedupe on them.)

### F3 — Unknown/malformed orgId is fail-open *(medium)*

No validation or rejection on orgId; a malformed or missing value flows
through and lands as null/empty in the response and S3 tags. The downstream
call executes anyway, and response routing depends on whatever
`responseTopic` the message carries. A garbage message is executed, not
refused. (Counter-design here: fail closed on unknown enums/ids.)

### F4 — Retry lives at the wrong layer *(medium)*

Whole-message redelivery is the only retry mechanism, so a transient failure
in the *last* step re-runs every step, and there is no per-call policy
(classification, backoff, max attempts) per downstream. Retry semantics belong
next to the call, with idempotency keys — not at the consumer.

### F5 — The auth-type typo is the wire contract *(low, but permanent)*

The literal value `"OATH"` (sic) appears in real message examples. It is not a
doc typo; it is the contract. Any successor must accept it forever or
translate at the boundary — “fixing” it in place silently breaks every
producer.

### F6 — HTTP method hardcoded to POST *(low)*

GET/PUT/DELETE downstreams cannot be modeled without tunneling semantics
through POST bodies.

### F7 — Partial-batch failure semantics unverified *(info)*

Batch listener + manual ack + virtual-thread-per-task execution: what happens
when message 7 of 20 fails mid-batch needs pinning down (the
swallow-and-commit family tends to live exactly there).

### F8 — Payload persistence in S3 *(info)*

Large requests are fetched from and large responses written to S3
(`{channel}/{path}/{uuid}.json`), so customer payloads persist beyond Kafka
retention. Bucket retention + masking policy belongs in the same review as F1.
The tagging discipline (correlationId, sfdcRecordId, orgId on every object) is
good and worth keeping.

### F9 — Response correlation by sfdcRecordId *(info)*

`sfdcRecordId` is the Kafka message key for responses; with two in-flight
requests on one record, disambiguation rests on correlation headers alone, and
the wrapper stores nothing. (This platform keys framework responses by
`journeyInstanceId` for exactly this reason; the record id stays a search key,
not a correlation key.)

## 3. What is genuinely good — keep it in any successor

- **One parameterized flow, zero org branches.** The “70 orgs = config”
  thesis is proven by absence of org-conditional code. Keep that property.
- **Statelessness.** No runtime config-store dependency, no cache
  invalidation, trivially scalable horizontally.
- **Clean response routing**: four well-named services (normal / composite /
  large / error) behind one router.
- Per-host timeout overrides; tight defaults (connect 1 s, response 5 s).
- Header propagation (correlationId, transactionId, source) and S3 object
  tagging.

## 4. One nuance to the “everything from the message” verdict

Request/response **mappers are per-service code**, keyed by `svcName` in YAML
and resolved via a factory, with raw-JSON pass-through as the default. So the
real split is: *execution* config from the message, *transformation* code from
service-side config + classes. That mapper YAML is, in effect, a
proto-capability registry — which is the migration seam (§5).

## 5. Migration mapping onto this platform

| legacy (generic-wrapper) | this platform |
|---|---|
| splitter owns per-org routing + config | journey selection + registry (A1/A2), version pinned per run |
| `EndpointConfig` embedded per message | registry-stored per-org/per-operation config; messages carry ids + context only |
| credentials in the message | service-side secrets, fail-closed at startup (A0); vault refs, never values on the wire |
| per-`svcName` mappers in YAML | capability operations with adapters; pass-through svcNames collapse into one generic-http capability |
| response topic named per message | fixed `cap.<key>.response.v1` convention |
| correlation by sfdcRecordId | keyed by `journeyInstanceId` (P2.8); record id remains an ops search key |
| Kafka-redelivery as retry | engine-owned per-node RetrySpec + `:a<n>` idempotency suffix + circuit breakers; DLQ terminal/audit-only |
| no ops surface (Kafka-UI triage) | audited `/ops` read window + ops console (id-shaped, no payloads) |
| S3 offload for large payloads | explicit decision needed: inline limits (P3.11 parity) vs. an offload capability with retention policy |

The successor shape in one sentence: **keep the parameterized executor, ship
references instead of values** — the message carries org/service references
and a secret reference; the executor resolves config from the registry and
credentials from a vault at call time.

## 6. Immediate actions on the legacy side (independent of any rebuild)

1. Redact the credential from the analysis doc; rotate it; add secret-scan
   hooks (F1).
2. Confirm downstream dedup behavior for every write-shaped `svcName`; list
   the ones with none (F2).
3. Decide and document the intended behavior for unknown/malformed orgId (F3).
4. Record `"OATH"` as a frozen wire value in the contract notes (F5).
5. Verify partial-batch failure handling in the consumer (F7).
6. Review S3 bucket retention/masking for offloaded payloads (F8).

## 7. Pending: sibling service analyses

brand-wrapper-service, brand-details-service, hrapps-integration-service —
review with the same lenses: credentials on the wire? idempotency on writes?
fail-open unknowns? state owned vs. pass-through? retry layer? Findings to be
appended here per service.
