# Legacy-Patterns Demo — "in the old system this was a microservice; here it's config"

**`demo/` is now just the runnable demo HARNESS** — scripts (`run-demo1.sh`,
`run-demo2.sh`) and the sample CSV. The pieces it exercises are **REAL modules**,
not demo code: the `device-validation` and `fusion-hcm` capabilities live in
`capabilities/`, and the local-folder ingress is `edges/file-batch-edge` — each
sits with its peers, on the capability framework, exactly like `kyc` or the SFDC
edge. They make **real outbound HTTP** (real client, timeouts, per-brand auth,
HTTP-status → failure-class) to a **WireMock** vendor (`mock-devicevalidation` /
`mock-fusion` in `docker-compose.infra.yml`); the ONLY thing mocked is the
vendor's response DATA (mappings under
`infra/mock-vendors/{device-validation,fusion}/mappings/`) — just as every
capability talks to a mock vendor in dev. None of it is the census-gated
migration target (`docs/legacy-analysis-review.md` §6/§8 — the generic-http
capability, production SFTP edge, `foreach` execution and sync lane stay unbuilt
until the pattern census sizes them). "Real flow, only data mocked" — the mock
sits at the vendor boundary, not inside the flow.

**The one-line thesis, repeated at every step:** *in the old estate this was a
separate Spring Boot microservice; in the new platform it's a config row (or a
drawn DAG). Same behaviour, no new deployable.*

## What runs

| piece | what it proves |
|---|---|
| `capabilities/device-validation` | **Brand-as-config**: ONE journey, THREE config-gated activities (validate / block / unblock). Each brand is a ROW in `application.yml` carrying its `validate`/`block`/`unblock` flags, `validate-by` (imei\|serial), auth scheme and pass path: SAMSUNG (OAUTH, imei, full lifecycle), GODREJ (NA, serial, block-only), BOSCH (BAUTH, serial, nested pass path, full lifecycle). The request's `status` (`1`=validate+block, `2`=unblock) intersects with the brand flags to decide what runs. Each row's auth is **real** (Samsung fetches an OAuth token then Bearer; Bosch sends Basic); the vendor call is **real HTTP** to `mock-devicevalidation`. Outcomes are **valid/invalid**; unknown brands FAIL CLOSED. |
| `capabilities/fusion-hcm` + `edges/file-batch-edge` | **File-batch scaffold**: the edge polls a CSV dropped in `demo/batch-inbox/` and starts one engine run per record; the `fusion-hcm` capability makes a **real HTTP** POST to Fusion (`mock-fusion`) per record — a malformed date is a real 400 → PERMANENT. Grouped by ONE batch search key. (Capability and ingress edge are separate deployables.) |
| engine `local` profile | The two demo doors as CONFIG (in `application-local.yml` — no separate `demo` profile): topics `orig.device-validation.v1` / `orig.employee-lwd-update.v1`, `type-to-journey` rows, the two journey JSONs. Loaded via classpath (`run-services.sh`, or `--idfc.engine.journey-source=classpath`). |
| ops view (existing) | Every run above is watchable: status, node position, per-record outcomes, failure CLASS, DLQ ref. Nothing demo-specific was added to it. |
| Designer seeds (designer repo) | The runnable demo journeys drawn, plus the two REFERENCE drafts — production file-batch (SFTP → `foreach` → email) and the sync-read lane — **drawn, not built** (the honesty slide). |

Proven end-to-end by `full-flow-it`'s `LegacyPatternsDemoIT` (embedded Kafka,
real engine, both capabilities + the file-batch edge): per-brand paths from rows,
fail-closed → live HISENSE add, batch of 5 → 4 succeeded + 1 failed with a class,
re-drop refused by ledger AND engine dedup, empty file skipped.

## Running it live

```bash
docker compose -f docker-compose.infra.yml up -d        # Kafka + Aerospike + mock-devicevalidation (9106) + mock-fusion (9107)

# 1. the engine with the demo rows (classpath journeys; flip to registry to
#    show the designer→registry→engine seam instead)
./gradlew :orchestration:origination-journey:bootRun \
  --args='--spring.profiles.active=local --idfc.engine.journey-source=classpath --idfc.engine.state-store=in-memory'

# 2. the capabilities + the file-batch ingress edge
./gradlew :capabilities:device-validation:bootRun --args='--spring.profiles.active=local' &
./gradlew :capabilities:fusion-hcm:bootRun --args='--spring.profiles.active=local' &
#    (bootRun's working dir is the MODULE dir, so pass the inbox absolutely —
#     otherwise the poller watches edges/file-batch-edge/demo/batch-inbox and
#     silently never sees the dropped CSV; run this from the repo root)
./gradlew :edges:file-batch-edge:bootRun --args="--spring.profiles.active=local --file-batch.enabled=true --file-batch.inbox-dir=$PWD/demo/batch-inbox" &

# 3. the ops view (designer repo) against the engine — LIVE by default now
#    (apps/journey_ops_view: flutter run -d chrome --dart-define=OPS_API_BASE_URL=http://localhost:8082 ...)
```

### Demo 1 — brand-as-config (~2 min)

```bash
demo/run-demo1.sh                 # fires SAMSUNG / GODREJ / BOSCH-decline / SAMSUNG-fail
```

Open the ops view: four runs — SAMSUNG approved THROUGH `n_validate`, GODREJ
approved WITHOUT it (its row says block-only), the BOSCH device declined
(teal completion, never red), the failed device red with `PERMANENT`.

**Add HISENSE live** (the payoff): restart `device-validation` with a few
extra CLI rows — no rebuild, no new service:

```bash
./gradlew :capabilities:device-validation:bootRun --args='--spring.profiles.active=local \
  --device-validation.brands.HISENSE.validate=false \
  --device-validation.brands.HISENSE.block=true \
  --device-validation.brands.HISENSE.unblock=false \
  --device-validation.brands.HISENSE.validate-by=imei \
  --device-validation.brands.HISENSE.auth-type=OAUTH \
  --device-validation.brands.HISENSE.pass-path=responseStatus \
  --device-validation.brands.HISENSE.pass-value=-4'   # WireMock hisense-pass returns responseStatus:-4
demo/run-demo1.sh HISENSE         # now valid (before the row: FAILED, fail-closed)
```

> Say: *"26 brands in legacy = 26 config files + a dedicated service. Here:
> rows against one journey. Adding a brand is this row, not a deployment."*

### Demo 2 — file-batch (~2 min)

```bash
demo/run-demo2.sh                 # drops the sample CSV into demo/batch-inbox/
```

Five records → five runs sharing ONE batch id (`notificationId` — paste it
into the ops search): 4 `COMPLETED—APPROVED`, `EMP-004` (crafted bad date)
`FAILED` with class `PERMANENT`. Drop the same file again: nothing happens —
the content ledger refuses it, and even with the ledger wiped the engine's
insertIfAbsent dedup refuses the re-run (the legacy LWD job re-runs blind).

> Say: *"A file job — the pattern people assume won't fit — becomes a file
> edge plus per-record runs. Demo scaffold today; the production SFTP edge is
> census-gated roadmap; the SHAPE runs and you can watch each record."*

### Demo 3 — the honesty slide

Open the Designer in **mock mode** (`flutter run -d chrome
--dart-define=USE_MOCK_BACKEND=true`): two REFERENCE drafts — `reference-file-batch`
(SFTP edge → empty-check → bounded-parallel `foreach` → per-record adapter →
email report) and `reference-sync-read`. These live in the designer's seed set,
NOT the real registry, precisely because they are *drawn, not built* (the engine
has no `foreach`/SFTP yet) — so they never seed into a registry the engine loads.
*"Three patterns run live today; these two are designed, not built — here is
exactly where they slot. No architectural gap; remaining build, sized by the
pattern census."*

## What is deliberately NOT here

- No production SFTP edge, no `foreach` execution, no generic-http capability,
  no AMQ routing, no email adapter — all census-gated or out of the demo. (The
  vendor CALLS are real HTTP; only the vendor's response DATA is a WireMock stub.)
- No PII anywhere: employee IDS and dates in the sample CSV, brand names and
  device ids on the wire — never names or account data.
- The `FILE_DEMO` source marker is a demo string on purpose: `SourceSystem`
  gains a real FILE member only when the production file edge exists.
