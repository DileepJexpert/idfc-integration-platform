# Capabilities — L3 reference index

A **capability** is a reusable business skill: it owns **one** decision, exposes named **operations**, and
hides its outside vendor behind an **out-port** (swapped by config). This folder has **one page per
capability** — a reference for the team that owns it. For the shape they share, see
**[L3 — Component](../03-component.md)**; for how they're wired into flows, see **[L4 — Journeys](../04-journeys.md)**.

Three invocation styles appear (each page's header says which):

- **Async shell** — invoked over `cap.<key>.request.v1` / `.response.v1` on the shared-capability
  framework (most capabilities). A business "no" is an `OK` result; a technical failure is an `ERROR` class.
- **Own consumer** — `verification` and `communications` run their own Kafka consumer rather than the
  generic shell (verification is svcName-routed; communications consumes the SFDC `SENDSMS` topic).
- **Sync (in-thread)** — `imps-disbursal` and `lms-utilities` are **libraries** the digital edge invokes
  in-thread; the caller blocks (see [the sync lane in L3 §3.3](../03-component.md)).

## The catalogue

| Capability | Does | Lane / style | Status |
|---|---|---|---|
| **[customer-party](customer-party.md)** | resolve the customer (Posidex) | async shell | full, simple |
| **[kyc](kyc.md)** | verify identity (NSDL) | async shell | full, simple |
| **[bureau](bureau.md)** | pull credit bureaus (CIBIL / Multi / Commercial), in parallel | async shell | full, substantive |
| **[scoring](scoring.md)** | in-house decision (rule + FICO enrichment); fails **closed to REJECTED** | async shell | full, substantive |
| **[lending-origination](lending-origination.md)** | book the loan in FinnOne (+ BRD §5 `validateDeviceFinancing`) | async shell | full |
| **[lending-servicing](lending-servicing.md)** | servicing ops (matured / closed / excess / batch) | async shell | logic real, edges all-mock, **not yet wired to a journey** |
| **[verification](verification.md)** | Karza checks, svcName-routed (VAHAN-RC · domain · negative-area) | own consumer + dispatcher | full, substantive |
| **[device-validation](device-validation.md)** | validate / block / unblock a financed device, per brand | async shell | full, substantive |
| **[mandate](mandate.md)** | e-mandate lifecycle (setup-link · cancel · register · callback) | async shell | full (some ops not yet in a journey) |
| **[communications](communications.md)** | send SMS / OTP, exactly once | own consumer (`comm.sms.send.v1`) | full, substantive |
| **[fusion-hcm](fusion-hcm.md)** | per-record Oracle Fusion HCM update | async shell | full, real HTTP |
| **[imps-disbursal](imps-disbursal.md)** | real-time IMPS fund transfer, no double-pay | **sync** (library) | full, substantive |
| **[lms-utilities](lms-utilities.md)** | real-time LMS query (offer check), requestCode-dispatched | **sync** (library) | full, substantive |
| **[echo](echo.md)** | echo the payload — proves the framework's exactly-once dispatch | async shell | reference |
| **[payments](payments.md)** | — | — | thin stub (health only, Slice 1 placeholder) |

## Notes worth knowing

- **`lending-origination.validateDeviceFinancing`** is an **unrelated** lending EMI brand-validation
  operation (BRD §5) — it is **not** the standalone `device-validation` capability. Same words, different
  concern.
- **`reverseBooking` / `finnone_pool`** on loan-origination's `n_book` are **journey-level** policy
  (compensation + a concurrency meter), not capability operations — see
  [loan-origination in L4](../04-journeys.md).
- **Error classification varies by design intent:** device-validation, verification, fusion-hcm and the
  sync capabilities classify the full `ErrorClass` set (PERMANENT / TRANSIENT / AMBIGUOUS); the
  origination-decisioning capabilities (kyc/bureau/scoring/customer-party) collapse technical failures to
  `PERMANENT` (no retry → DLQ). A business "no" is always a normal outcome, never an error.

---
← [architecture home](../README.md) · [L3 component](../03-component.md) · [L4 journeys](../04-journeys.md)
