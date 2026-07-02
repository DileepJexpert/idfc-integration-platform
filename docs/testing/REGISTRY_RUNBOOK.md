# Journey Registry runbook — the designer→engine seam, operated

The control plane loop (workstream A): a maker authors a journey in the **DAG
Designer**, a checker publishes it in the **journey-registry**, and the
**engine** runs exactly that config — version-pinned for in-flight runs. This
runbook operates and verifies that loop on the composed stack.

Postman: `docs/testing/idfc-platform.postman_collection.json`, folder
**"Journey Registry (control plane)"** (vars: `baseRegistry`, `registryToken`,
`makerId`, `checkerId`, `journeyKey`).

## 1. Bring the stack up

```bash
docker compose -f docker-compose.infra.yml up -d      # kafka + aerospike + UIs
./gradlew bootBuildImage                              # builds idfc/* images incl. journey-registry
docker compose -f docker-compose.services.yml up -d   # services; engine runs journey-source=registry
docker compose -f docker-compose.services.yml ps      # registry on :8104; engine should be Up
```

The engine **fails closed at bootstrap** when it cannot load journeys from the
registry (deliberate — an engine with no journeys must not consume). Compose
gives it `depends_on` + `restart: on-failure:5` to absorb start ordering; if
the registry is genuinely down the engine container ends **Exited** with
`refusing to start` in `docker logs idfc-origination-journey`.

> First boot note: a fresh registry has NO published journeys, so the engine
> will retry-exit until you publish one (section 2) or flip it to the classpath
> fallback (`IDFC_ENGINE_JOURNEY_SOURCE: classpath`). That is the fail-closed
> contract working as intended, not a bug.

## 2. Publish a journey (maker → checker)

Run the Postman folder in order (1 → 6b), or the curl equivalents:

```bash
R=http://localhost:8104; T="X-Registry-Token: dev-registry-token"
curl -sX POST $R/api/v1/journeys -H "$T" -H "X-User-Id: maker-asha" \
  -H 'Content-Type: application/json' \
  -d '{"key":"pl-express","name":"PL Express","businessLine":"PL"}'
# draft (server stamps journeyKey/version — anti-spoof), then:
curl -sX POST $R/api/v1/journeys/pl-express/versions/1/submit  -H "$T" -H "X-User-Id: maker-asha"
curl -sX POST $R/api/v1/journeys/pl-express/versions/1/approve -H "$T" -H "X-User-Id: maker-asha"
#   -> 403 FORBIDDEN: maker-checker is enforced SERVER-SIDE (the author may never approve)
curl -sX POST $R/api/v1/journeys/pl-express/versions/1/approve -H "$T" -H "X-User-Id: checker-vikram"
#   -> 200 published; the engine's scheduled refresh (30s) picks it up — watch:
docker logs idfc-origination-journey | grep journey.catalog
#   journey.catalog.bootstrapped / journey.catalog.refreshed source=registry [pl-express@v1, ...]
```

To route real traffic at it, add the engine routing row (config, not code) —
map keys with underscores need JSON-style binding in compose:

```yaml
SPRING_APPLICATION_JSON: '{"idfc":{"engine":{"type-to-journey":{"NEW_TYPE":"pl-express"}}}}'
```

Unmapped types go to the DLQ as poison (fail-closed routing; no default journey).

## 3. Run the designer against the live registry

```bash
cd ../journey-dag-designer
flutter run -d chrome --dart-define=USE_MOCK_BACKEND=false \
  --dart-define=API_BASE_URL=http://localhost:8104 \
  --dart-define=REGISTRY_TOKEN=dev-registry-token
```

Log in as any username — it becomes the actor id on every call (`X-User-Id`).
Log in as `maker-*` to author/submit and `checker-*` to approve/reject; the
403/409/422 you see in the UI are the SERVER's answers, not client gating.
(Identity is asserted, not authenticated — SSO is a tracked production gate.)

## 4. VERIFY: version pinning across a mid-run publish

The A2 guarantee, observed on the live stack. In-flight runs must complete on
the version they started on; only NEW runs pick up a publish.

1. Publish `v1` of a journey whose first task uses a capability that will NOT
   answer immediately (or pause that capability container:
   `docker stop idfc-kyc`) — the run parks awaiting the response.
2. Start run 1 (SFDC/digital Postman request, or `kafka-console-producer` onto
   the origination topic). Confirm in Kafka UI: a `cap.<key>.request.v1`
   message with a `journeyInstanceId`; the engine log shows
   `journey.start ... version=1`.
3. In the designer: draft v2 (add a node), submit, approve as checker. Wait one
   refresh tick — engine log: `journey.catalog.refreshed ... @v2`.
4. Release the parked capability (`docker start idfc-kyc`, or answer manually).
   **Run 1 completes on v1**: its decision appears WITHOUT any v2-only node
   request; `journey.resolve` never logs a v2 fetch for it.
5. Start run 2. **It starts on v2**: `journey.start ... version=2`, and the
   v2-only node's request topic gets traffic.

Automated twin: `full-flow-it/src/test/java/.../RegistryEngineSeamIT.java`
(`designerPublishedJourneyRunsAndPinsAcrossAMidRunPublish`) runs this exact
loop — real registry over HTTP, real engine app, embedded Kafka — and
self-skips when the registry isn't up:

```bash
REGISTRY_AUTH_TOKEN=dev-registry-token ./gradlew :platform:journey-registry:bootRun &
./gradlew :full-flow-it:test --tests RegistryEngineSeamIT
```

## 5. VERIFY: bootstrap-down is deliberate

```bash
docker stop idfc-journey-registry
docker restart idfc-origination-journey
docker logs -f idfc-origination-journey     # -> "journey bootstrap from registry[...] failed —
                                            #     refusing to start: ... start the registry first"
docker compose -f docker-compose.services.yml ps   # engine restarts x5 then stays Exited
docker start idfc-journey-registry
docker restart idfc-origination-journey     # -> journey.catalog.bootstrapped, engine Up
```

While a RUNNING engine only loses the registry (no restart), it keeps serving
the last-known snapshot and logs `journey.catalog.refresh ... failed — keeping
the last-known snapshot` — a registry blip never takes the engine down.
(Automated twin: `engineRefusesToStartWhenTheRegistryIsUnreachable` in the
same IT, plus `RegistryDownBehaviorTest` for all four phase policies.)

## 6. Error semantics quick reference (registry API)

| Status | Meaning | Typical trigger |
|--------|---------|-----------------|
| 401 | missing/invalid `X-Registry-Token`, or a mutation without `X-User-Id` | forgot a header |
| 403 | maker-checker: the author may not approve/reject their own version | self-approve |
| 404 | no such journey/version, or never published (pinned engine read) | wrong key/version |
| 409 | lifecycle conflict: second editable draft, wrong status, lost checker race | double draft |
| 422 | §7 validation failed — body carries `issues[{code,severity,message,nodeId}]` | dangling edge |
