# Mock vendor servers (demo)

Standalone Dockerized vendor mocks the capability adapters call **over the wire**
— real protocol/contract, fixture responses. They are NOT in-app mocks; each
capability's real adapter (URL/datasource from config) points at one of these.

| Service | Image | Port | Called by | Endpoint / contract |
|---|---|---|---|---|
| mock-posidex | wiremock 3.9 | 19101 | customer-party | `POST /posidex/resolve` → `{crn, customerId, name, status}` |
| mock-cibil | wiremock 3.9 | 19102 | bureau | `POST /cibil/report` → `{score, grade, reportId}` |
| mock-fico | wiremock 3.9 | 19103 | scoring | `POST /fico/score` → `{score, model}` |
| mock-nsdl | wiremock 3.9 | 19104 | kyc | `POST /nsdl/verify` → `{status, kycRefId}` |
| mock-finnone | gvenzl/oracle-xe 21 | 1521 | lending-origination | Oracle `SP_FINNONE_SUBMISSION(applicationRef IN, loanNumber OUT)` via JDBC |

## Demoable both ways (branch)

`mock-cibil` returns **score 780 (grade A)** by default → drives **APPROVED**.
When the request body's `pan` matches `/.*LOW.*/i` it returns **score 540
(grade C)** → drives **REJECTED**. So:

- High-score applicant: any pan, e.g. `ABCDE1234F` → 780 → APPROVED → loan booked.
- Low-score applicant: a pan containing `LOW`, e.g. `LOWAB0000X` → 540 → REJECTED.

## Run

The mock vendors live in the **infra** compose layer (alongside Aerospike +
Kafka) — they are pull-only images, no build needed:

```bash
docker compose -f docker-compose.infra.yml up -d   # or: ./demo.sh infra
# smoke (note: bureau low/high keys on applicationRef, not pan):
curl -s localhost:19102/cibil/report -H 'Content-Type: application/json' -d '{"applicationRef":"APP-HIGH"}' # -> 780
curl -s localhost:19102/cibil/report -H 'Content-Type: application/json' -d '{"applicationRef":"APP-LOW"}'  # -> 540
```

Oracle-XE takes ~1–2 min to become healthy on first boot (it runs
`finnone/init/*.sql` to define the stored procedure). The capability connects as
user `finnone`/`finnone` to `XEPDB1`.

These services are also included from the root `docker-compose.yml` for the
full-flow demo, where each capability points at them via env
(`POSIDEX_URL`, `CIBIL_URL`, `FICO_URL`, `NSDL_URL`, and the FinnOne datasource).
