# L1 — System Context

**Zoom:** the whole platform as ONE box. **Audience:** business, product, new joiners.
**Question answered:** *Who uses it, and what external systems does it touch?*

The IDFC Integration Platform is the **middle layer** between IDFC's channels/systems-of-record and the
outside vendors that verify, score, book, disburse, and notify. Channels hand it work; it orchestrates the
right sequence of vendor calls and business decisions; it hands results back.

```mermaid
flowchart TB
  %% actors / channels (left)
  SFDC[["Salesforce (SFDC)<br/>assisted / branch origination,<br/>OTP, post-disbursal"]]:::act
  PARTNERS[["Digital partners<br/>INDMONEY · SAVEIN · CRED · Flipkart · Groww"]]:::act
  FILES[["Batch drops<br/>HR CSV (last-working-day)"]]:::act
  OPS[["Ops / support users"]]:::act
  DESIGNER[["Journey Designer<br/>(Flutter app, separate repo)"]]:::act

  PLATFORM{{"IDFC Integration Platform<br/><br/>normalise every door to one envelope ·<br/>walk the product journey (DAG) ·<br/>call vendors behind ports ·<br/>decide · disburse · notify · audit"}}:::plat

  %% systems of record / vendors (right)
  KARZA[("Karza<br/>KYC, domain-check, negative-area, VAHAN-RC")]:::ext
  BUREAU[("Credit bureau")]:::ext
  FINNONE[("FinnOne<br/>loan system of record")]:::ext
  IMPS[("IMPS / FT backend<br/>fund transfer")]:::ext
  LMS[("LMS<br/>loan offers / utilities")]:::ext
  FUSION[("Oracle Fusion HCM")]:::ext
  BRANDS[("Device-brand vendors<br/>Apple · Samsung · Godrej · Bosch")]:::ext
  MANDATE[("Mandate rails<br/>Digio · NPCI · Ingenico")]:::ext
  COMMS[("Comms hub<br/>SMS / OTP")]:::ext

  SFDC -->|SOAP Outbound Msg| PLATFORM
  PARTNERS -->|REST: async origination + SYNC impsFT / callLmsUtilities| PLATFORM
  FILES -->|CSV in a watched folder| PLATFORM
  DESIGNER -->|publishes versioned journey DAGs| PLATFORM
  OPS -->|read-only queries| PLATFORM
  PLATFORM -->|decisions / callbacks| SFDC
  PLATFORM -->|decisions / callbacks| PARTNERS

  PLATFORM --> KARZA & BUREAU & FINNONE & IMPS & LMS & FUSION & BRANDS & MANDATE & COMMS

  classDef act fill:#e8f0fe,stroke:#4a76d4;
  classDef plat fill:#e6f4ea,stroke:#34a853,stroke-width:2px;
  classDef ext fill:#eee,stroke:#999;
```

## What crosses the boundary

| Actor / system | Direction | Protocol | What flows |
|---|---|---|---|
| Salesforce (SFDC) | in | SOAP Outbound Message (HTTP) | origination (`Inbound_Wrapper`), OTP (`SENDSMS`), device-validation post-disbursal (`Post_Disbursal_*`) |
| Digital partners | in | REST/JSON, Bearer (Ory/Hydra) | async loan origination (CRED/Flipkart/Groww); **sync** `impsFT` (INDMONEY) and `callLmsUtilities` (SAVEIN) |
| Batch drops (HR) | in | CSV file in a watched folder | one row per employee last-working-day update |
| Journey Designer | in | REST to the journey registry | versioned journey DAGs (maker-checker) |
| Ops / support | in | REST (read-only, token-gated) | run status, node position, failure class, DLQ refs |
| Karza | out | REST/JSON (OAuth) | KYC, domain-check, negative-area, VAHAN vehicle-RC |
| Credit bureau | out | REST | bureau pull |
| FinnOne | out | REST | loan booking (system of record) — with compensation (reverse) |
| IMPS / FT backend | out | REST/JSON | real-time fund transfer (disbursal) |
| LMS | out | REST/JSON (house envelope) | loan offers / utilities (`OFFER_CHECK`, …) |
| Oracle Fusion HCM | out | REST | employee record update/read |
| Device-brand vendors | out | REST (per-brand auth) | validate / block / unblock a financed device |
| Mandate rails (Digio/NPCI/Ingenico) | out | REST + async callback | e-mandate setup / cancel |
| Comms hub | out | internal | SMS / OTP send |

## The one-paragraph version

Channels don't talk to vendors directly and vendors don't know about channels — **the platform sits in
the middle**. A door (edge) turns whatever the channel sent into one standard message; the engine runs the
product's journey, calling the right vendors in the right order and making the branch decisions; results
go back to the channel and everything is recorded for ops. New channel? New door. New product? New
journey (a diagram). New vendor? New adapter behind a port. The centre stays stable.

→ Next: **[L2 — Container](02-container.md)** (what actually runs, and how the pieces talk).
