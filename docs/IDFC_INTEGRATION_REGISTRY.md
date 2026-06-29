# IDFC Integration Registry — external systems, adapters, and how wiring works

> The reference for wiring real adapters to external systems. **Crucial for Slice 1:** it needs NONE of the
> real URLs — everything beyond the edge is mocked. This registry tells you, per external system: which
> capability owns its adapter, the CONFIG KEYS (never the values), the auth scheme, and where the real
> contract/URL is harvested from when that adapter's slice is built.
>
> **Values (actual URLs, keys, secrets) live in environment config + Vault per environment — NEVER in this
> doc, NEVER in code, NEVER in a prompt.** This doc is structure; values are deployment config.

Last updated: 2026-06-28

---

## 0. The wiring principle (how Claude Code "wires everything")

Every external system is reached through an **OUT port** with two implementations:
```
domain → SomePort → MockAdapter   [profile: local/test]   (no URL; canned behavior)
                  → RealAdapter   [profile: prod]         (URL/creds from CONFIG, injected at deploy)
```
- The domain and the rest of the app depend on the **port**, never the adapter.
- The RealAdapter reads its endpoint + auth from **externalized config** (`application-prod.yml` placeholders
  resolved from env/Vault at deploy), e.g. `idfc.adapters.karza.base-url=${KARZA_BASE_URL}`.
- Claude Code wires to the **port + mock** for Slice 1. Real adapters are built in their capability's slice,
  and the real URL is supplied by config at that time — not hardcoded, not in the repo.

So "how does Claude Code wire the real Karza/NSDL/SFDC URLs?" → **it doesn't, ever.** It wires ports; real
endpoints arrive as config values at deploy. This is also why secrets never leak into the codebase.

---

## 1. Slice 1 (SFDC ingress edge) — what it touches (ALL MOCKED locally)

| Port | Slice-1 impl | Real impl (later) | Real endpoint source |
|---|---|---|---|
| `IdempotencyStorePort` | **AerospikeIdempotencyStore (REAL, local Aerospike)** | same, prod Aerospike | Aerospike cluster config |
| `MessagePublisherPort` | **Kafka (REAL, local)** | same, prod Kafka | Kafka bootstrap config |
| `AuthTokenPort` (Hydra+Kong two-token) | MockTwoTokenAuthAdapter | RealTwoTokenAuthAdapter | config: Hydra URL, Kong URL |
| `SfdcResponsePort` (push decision back) | MockSfdcResponseAdapter | RealSfdcResponseAdapter | config: Kong SFDC URL |
| `BlobStorePort` (S3 claim-check) | MockS3BlobStoreAdapter | RealS3Adapter | config: S3 endpoint |
| `OrgConfigPort` (routing/org-as-data) | SeededOrgConfigAdapter (yaml seed) | Aerospike/config store | config store |
| `FinnOneMeterPort` (backpressure harness) | MockFinnOneStoredProc (cap N) | (real FinnOne = Slice 4) | n/a in Slice 1 |

**Slice 1 needs ZERO real external URLs.** Karza/NSDL/CIBIL/etc. are NOT in Slice 1 at all (they're KYC/Bureau
vendors — later capability slices).

---

## 2. The full external-system registry (for when real adapters are built)

> Config-key NAMES only. Values come from env/Vault per environment. "Harvest from" = the existing service
> whose code/OpenAPI gives the real URL + contract at build time.

### Cores / systems-of-record
| System | Owning capability (adapter lives here) | Access shape | Config keys (names) | Harvest real contract from |
|---|---|---|---|---|
| **FinnOne** | Lending-Origination (+ Servicing*) | **Oracle JDBC + STORED PROC** (not REST) | `finnone.jdbc.url`, `finnone.proc.*`, creds→Vault | finnone-submit, finnone-onboarding-disbursal |
| **TCS BaNCS / CBS** | Accounts/CASA; money ops in Servicing/Payments | REST (`generic-paymenttxn-exp/v3/fundTransfer`) | `cbs.base-url`, auth→Vault | finnone-integration (SIDebit→CBS) |
| **LMS** (=FinnOne wrapper per A2, OPEN CONFIRM) | Lending-Servicing | TBD (confirm: stored proc or REST) | `lms.*` | dl-lms-handler |
| **SFDC (Salesforce)** | SFDC edges (ingress/egress) + as backend API | SOAP Outbound Msg (in) / Kong REST (out) | `sfdc.kong.url`, two-token→Vault | sfdc-response, sfdc-composite-response |
| **CDP / Posidex** (customer SoR, A1) | Customer/Party | REST | `cdp.base-url`, `posidex.base-url` | dl-loan-offer (Posidex), customer flows |

### KYC vendors (KYC capability — Slice 3; adapters here)
| Vendor | Purpose | Config keys | Harvest from |
|---|---|---|---|
| **NSDL** | PAN verify, eKYC OTP gen/validate | `kyc.nsdl.url` | dl-kyc-verification |
| **HyperVerge** | PAN profile enrichment | `kyc.hyperverge.url` | dl-kyc-verification, dl-loan-offer |
| **CERSAI** | CKYC search/download (Central KYC) | `kyc.cersai.searchurl`, `.downloadurl` | dl-kyc-verification |
| **Karza** | KYC/verification | `kyc.karza.url` | (KYC services) |
| **Digilocker** | KYC callback | `kyc.digilocker.url` | dl-kyc-verification |
| **Digitap** | banking analytics / PAN profile | `kyc.digitap.url` | customer-ckyc |
| (internal) PhotoMatch, Validation microservices | liveness, extra validation | `kyc.photomatch.url`, `kyc.validation.url` | dl-kyc-verification |

### Bureau / scoring (Bureau + Scoring capabilities — Slices 2 & later)
| System | Purpose | Config keys | Harvest from |
|---|---|---|---|
| **CIBIL / Multi-Bureau** | credit bureau (via internal scorecard infra) | `bureau.cibil.url`, `bureau.multibureau.url` | scorecard-analyser, cibil-management, dl-eligibility |
| **Internal scorecard infra** | fronts bureaus (`scorecard.dev-infinity...`) | `scorecard.base-url` | fico-loan-eligibility |
| **FICO** (Bureau Proxy, Perfios) | decisioning | `fico.bureau-proxy.url`, `fico.perfios.url` | fico-loan-eligibility |
| **BSA, PL/LAP/Commercial scorecards** | scoring engines | `scorecard.*` | scorecard-analyser, dl-eligibility |
| **Datalake** | fraud inference | `fraud.datalake.url` | scorecard-analyser |
| **Hunter / MHA** | fraud | `fraud.hunter.url` | finnone-integration, scorecard-analyser |
| **Udyam** | MSME registration | `udyam.*` | dl-eligibility |

### Payments rails / execution systems (Payments capability — adapters; we own NO money SoR here)
| System | Purpose | Config keys | Harvest from |
|---|---|---|---|
| **IMPS Payment Mgmt System** | IMPS fund transfer execution | `payments.imps.url` | imps-fund-transfer |
| **BillDesk** | bill-pay (utility/DTH/FASTag) execution+settlement | `payments.billdesk.url`, JWE/JWS keys→Vault | billdesk-integration |
| **Montran** | UPI mandate PDN/DBT execution | `payments.montran.pdn.url`, `.dbt.url`, `.status.url` | montran-upimandate |
| **eMandate / digio / ingenico** | mandate registration (SoR likely BRNet) | `mandate.*` | emandate-management, digio-mandate |
| **PayU** | payment/credit verification | `payments.payu.url` | scorecard-analyser, cibil-management |

### Offers / data / docs / comms
| System | Purpose | Config keys | Harvest from |
|---|---|---|---|
| **Offermart / Base-Refer** | customer offer ecosystem | `offermart.base-url` | dl-ebc-onboarding, dl-lms-handler |
| **Commshub** | SMS/notification dispatch | `comms.commshub.url` | finnone-commshub-penal-charges, dl-notification |
| **Email API (Mule)** | status emails | `comms.email.url` | penal-charges service |
| **FileNet** | document management | `docs.filenet.url` | finnone-onboarding-disbursal |
| **IDP / DataMart** | data/reporting plane (observe-only) | `data.idp.*` | idp-integration |
| **BR.NET / Smart Collect** | collections; drives PDN/DBT; mandate record? | `collections.brnet.*` | montran-upimandate, collections services |

### Platform
| System | Purpose | Config keys |
|---|---|---|
| **Hydra** | enterprise OAuth (client_credentials) | `platform.auth.hydra.url` |
| **Kong** | API gateway (egress + SFDC token) | `platform.kong.url`, `platform.kong.sfdc.token.url` |
| **Vault** | secrets | `platform.vault.*` |
| **Aadhaar tokenization vault** | Aadhaar PII tokenization | `platform.aadhaar-vault.url` |

---

## 3. Rules for real-adapter wiring (apply at each adapter's slice)
1. **Never hardcode a URL or secret.** Endpoint = config key (`${ENV_VAR}` from env/Vault). Secret = Vault only.
2. **Harvest the real contract at build time** from the named existing service (OpenAPI/DTOs/code), not from memory.
3. **One adapter per external system, inside the owning capability** — not a service per vendor.
4. **Mock + real behind the same port**, profile-switched. The mock must mirror the real contract (same shapes).
5. **Auth schemes vary** (two-token Hydra+Kong; BillDesk JWE/JWS; Basic; Bearer; ent-auth) → adapter-level config,
   never leaked into the domain.
6. **FinnOne is JDBC + stored proc**, not REST — its adapter is the odd one out; don't model it as an HTTP client.

---

## 4. Bottom line for Claude Code (Slice 1)
- Wire to **ports + mocks** for all externals except Aerospike + Kafka (which are real, local).
- Use **NO real URLs** — none are needed; none should appear in the repo.
- This registry is **context** so Claude Code understands the eventual shape, NOT a list of things to integrate
  now. The scope fence stands: only the SFDC ingress edge is built; everything else is a stub or a mock.
