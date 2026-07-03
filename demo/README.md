# Legacy-Patterns Demo — "in the old system this was a microservice; here it's config"

**Everything under `demo/` is DEMO SCAFFOLDING.** Vendors are mocked in-process,
the file edge reads a LOCAL FOLDER (not SFTP), and none of it is the
census-gated migration target (`docs/legacy-analysis-review.md` §6/§8 — the
generic-http capability, production SFTP edge, `foreach` execution and sync
lane stay unbuilt until the pattern census sizes them). If a demo piece and the
real thing would diverge, the demo is deliberately the obviously-demo version.

**The one-line thesis, repeated at every step:** *in the old estate this was a
separate Spring Boot microservice; in the new platform it's a config row (or a
drawn DAG). Same behaviour, no new deployable.*

## What runs

| piece | what it proves |
|---|---|
| `device-financing-demo` app | **Brand-as-config**: ONE journey; SAMSUNG (OAUTH, validate+block), GODREJ (NA, block-only), BOSCH (BAUTH, nested pass path) are ROWS in its `application.yml`. Unknown brands FAIL CLOSED. |
| `fusion-hcm-demo` app | **File-batch scaffold**: a CSV dropped in `demo/batch-inbox/` becomes one engine run per record (mock Fusion), grouped by ONE batch search key; plus the mocked per-record capability. |
| engine `demo` profile | The two demo doors as CONFIG: topics `orig.demo.device.v1` / `orig.demo.hr.v1`, `type-to-journey` rows, the two journey JSONs. |
| ops view (existing) | Every run above is watchable: status, node position, per-record outcomes, failure CLASS, DLQ ref. Nothing demo-specific was added to it. |
| Designer seeds (designer repo) | The runnable demo journeys drawn, plus the two REFERENCE drafts — production file-batch (SFTP → `foreach` → email) and the sync-read lane — **drawn, not built** (the honesty slide). |

Proven end-to-end by `full-flow-it`'s `LegacyPatternsDemoIT` (embedded Kafka,
real engine, both demo apps): per-brand paths from rows, fail-closed → live
HISENSE add, batch of 5 → 4 succeeded + 1 failed with a class, re-drop refused
by ledger AND engine dedup, empty file skipped.

## Running it live

```bash
docker compose -f docker-compose.infra.yml up -d        # Kafka + Aerospike

# 1. the engine with the demo rows (classpath journeys; flip to registry to
#    show the designer→registry→engine seam instead)
./gradlew :orchestration:origination-journey:bootRun \
  --args='--spring.profiles.active=local,demo --idfc.engine.journey-source=classpath --idfc.engine.state-store=in-memory'

# 2. the two demo apps
./gradlew :demo:device-financing-demo:bootRun --args='--spring.profiles.active=local' &
./gradlew :demo:fusion-hcm-demo:bootRun --args='--spring.profiles.active=local --demo.batch.enabled=true' &

# 3. the ops view (designer repo) against the engine
#    (apps/journey_ops_view: flutter run -d chrome --dart-define=USE_MOCK_OPS_API=false ...)
```

### Demo 1 — brand-as-config (~2 min)

```bash
demo/run-demo1.sh                 # fires SAMSUNG / GODREJ / BOSCH-decline / SAMSUNG-fail
```

Open the ops view: four runs — SAMSUNG approved THROUGH `n_validate`, GODREJ
approved WITHOUT it (its row says block-only), the BOSCH device declined
(teal completion, never red), the failed device red with `PERMANENT`.

**Add HISENSE live** (the payoff): restart `device-financing-demo` with five
extra CLI rows — no rebuild, no new service:

```bash
./gradlew :demo:device-financing-demo:bootRun --args='--spring.profiles.active=local \
  --demo.device-financing.brands.HISENSE.auth-type=OAUTH \
  --demo.device-financing.brands.HISENSE.validation-required=false \
  --demo.device-financing.brands.HISENSE.pass-path=responseStatus \
  --demo.device-financing.brands.HISENSE.pass-value=-4 \
  --demo.device-financing.brands.HISENSE.stub-response.responseStatus=-4'
demo/run-demo1.sh HISENSE         # now approves (before the row: FAILED, fail-closed)
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

Open the Designer (mock mode): two REFERENCE drafts — `reference-file-batch`
(SFTP edge → empty-check → bounded-parallel `foreach` → per-record adapter →
email report) and `reference-sync-read`. *"Three patterns run live today; these
two are designed, not built — here is exactly where they slot. No architectural
gap; remaining build, sized by the pattern census."*

## What is deliberately NOT here

- No production SFTP edge, no `foreach` execution, no generic-http capability,
  no AMQ routing, no real vendor calls, no email adapter — all census-gated or
  explicitly out of the demo.
- No PII anywhere: employee IDS and dates in the sample CSV, brand names and
  device ids on the wire — never names or account data.
- The `FILE_DEMO` source marker is a demo string on purpose: `SourceSystem`
  gains a real FILE member only when the production file edge exists.
