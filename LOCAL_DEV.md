# Running services one-by-one (IntelliJ) — local dev for independent teams

> **You do NOT need `docker compose up` to learn or develop this platform.**
> `docker compose` is only a *convenience bundle* for a full end‑to‑end demo.
> Nothing in the code requires services to be co‑located. Each capability is an
> independent Spring Boot app that talks to Kafka and nothing else — exactly so a
> different team can own each one and run it alone.

This guide shows how to run each service as a plain Java/Spring Boot application
in IntelliJ, why they no longer collide on a port, what each one actually needs,
and how a single team can exercise *just their* capability.

---

## 1. The mental model (why this is NOT tightly coupled)

```
            cap.<key>.request.v1                cap.<key>.response.v1
   ENGINE ───────────────────────▶  CAPABILITY  ───────────────────────▶ ENGINE
                                   (your service)
```

A capability's *entire* contract with the outside world is two Kafka topics
(`CapabilityRequest` in, `CapabilityResponse` out — both in `shared:shared-domain`).
It does not call other capabilities, does not know about journeys, and does not
know which channel started the run. So:

- **To develop one capability you run: Kafka + that one app.** Nothing else.
- Its vendor (Posidex/CIBIL/NSDL/FICO/FinnOne) defaults to an **in‑app mock**, so
  you don't even need the vendor mock containers for local coding.
- Teams stay decoupled: own your module directory, your topics, your run config.

`docker compose` just starts *all* of them together for a demo. That's a
deployment choice, not a code dependency.

---

## 2. Port map (the fix)

Previously every app inherited Spring's default `8080` (the shared build plugin
gives every service `spring-boot-starter-web`), so running two at once failed
with *"port 8080 already in use."* Each service now has a **distinct, stable
port**, overridable with the `SERVER_PORT` env var:

| # | Service | Port | Module |
|---|---|---|---|
| 1 | sfdc-ingress-edge | **8080** | `edges/sfdc-ingress-edge` |
| 2 | digital-partner-edge | **8081** | `edges/digital-partner-edge` |
| 3 | origination-journey (engine) | **8082** | `orchestration/origination-journey` |
| 4 | customer-party | **8090** | `capabilities/customer-party` |
| 5 | kyc | **8091** | `capabilities/kyc` |
| 6 | bureau | **8092** | `capabilities/bureau` |
| 7 | scoring | **8093** | `capabilities/scoring` |
| 8 | lending-origination | **8094** | `capabilities/lending-origination` |
| 9 | lending-servicing | **8095** | `capabilities/lending-servicing` |
| 10 | payments | **8096** | `capabilities/payments` |

The port is only for **health/metrics** (`GET /actuator/health`) — capabilities
do their real work over Kafka, not HTTP. But the distinct port means **you can
run all ten at the same time** on your laptop.

> Docker is unaffected: each container has its own network namespace, and the
> compose files don't set `SERVER_PORT`, so containers still listen on their
> defaults internally.

---

## 3. What each service actually needs (dependency matrix)

| Service | Kafka | Aerospike | Vendor mock | Other capabilities |
|---|:---:|:---:|:---:|:---:|
| any **capability** (customer-party, kyc, bureau, scoring, lending-origination, payments, lending-servicing) | ✅ | — | only if you set `<VENDOR>_MODE=real` | ❌ never |
| **origination-journey** (engine) | ✅ | only if `IDFC_ENGINE_STATE_STORE=aerospike` (default `in-memory`) | — | — |
| **sfdc-ingress-edge** | ✅ | ✅ (idempotency store) | — | — |
| **digital-partner-edge** | ✅ | ✅ (idempotency store) | — | — |

Key takeaways:
- **Capabilities: Kafka only.** Vendors default to `mock` (in‑app). You can flip a
  single capability to a live vendor with e.g. `CIBIL_MODE=real CIBIL_URL=...`.
- **Engine: Kafka only** by default (in‑memory run state). Turn on durable state
  with `IDFC_ENGINE_STATE_STORE=aerospike` + `AEROSPIKE_HOST=localhost`.
- **Edges: Kafka + Aerospike** (the idempotency store is Aerospike‑backed; there
  is no mock mode for it — run a local Aerospike).

---

## 4. Start the minimum infra (not the whole stack)

You only need the infra a given service depends on.

**Just Kafka + Aerospike (covers everything), without the app containers:**

```bash
docker compose -f docker-compose.infra.yml up -d aerospike kafka
# Kafka: localhost:29092 from your host (tools), kafka:9092 inside docker
# Aerospike: localhost:3000
```

If you're only running a capability, `... up -d kafka` is enough.
Add a vendor mock only when you set that vendor to `real`, e.g.
`... up -d kafka mock-cibil` for bureau in real mode.

You can also run Kafka/Aerospike any way you like (Homebrew, a local install) —
the apps just read `KAFKA_BOOTSTRAP_SERVERS` / `AEROSPIKE_HOST`.

---

## 5. Run a service in IntelliJ

**Option A — use the bundled run configs (recommended).**
This repo ships `.run/*.run.xml` — IntelliJ auto‑discovers them. After importing
the Gradle project you'll see ten entries in the Run dropdown, named e.g.
`04. customer-party :8090`. Pick one and hit ▶. (If IntelliJ can't match the
module on first import, open the config and re‑select the `…capabilities.customer-party.main`
module from the dropdown — a one‑time fixup.)

**Option B — right‑click the main class.**
Open e.g. `CustomerPartyApplication.java` → right‑click → **Run**. IntelliJ
creates a Spring Boot run config automatically. To override the port or a vendor,
edit the config's *Environment variables*, e.g. `SERVER_PORT=8090;CIBIL_MODE=real`.

Either way the only prerequisite is that the infra it needs (table in §3) is up.

---

## 6. Develop/poke ONE capability in isolation (the per‑team workflow)

This is the day‑to‑day loop for a team that owns, say, `customer-party`. No
engine, no edge, no other capability.

1. `docker compose -f docker-compose.infra.yml up -d kafka`
2. Run **customer-party** in IntelliJ (port 8090). It subscribes to
   `cap.customer-party.request.v1`.
3. Hand it a request and watch its reply. Open two terminals into the Kafka
   container (`docker exec -it idfc-kafka bash`):

   **Consume the response topic:**
   ```bash
   /opt/kafka/bin/kafka-console-consumer.sh --bootstrap-server localhost:9092 \
     --topic cap.customer-party.response.v1 --from-beginning
   ```

   **Produce a request** (the `CapabilityRequest` wire shape from
   `shared:shared-domain`):
   ```bash
   /opt/kafka/bin/kafka-console-producer.sh --bootstrap-server localhost:9092 \
     --topic cap.customer-party.request.v1
   > {"journeyInstanceId":"dev-1","correlationId":"dev-1","capabilityKey":"customer-party","nodeId":"n_customer","payload":{"applicationRef":"APP-1"},"collectedResults":{}}
   ```

4. The consumer prints a `CapabilityResponse` with `status:"OK"` and the
   capability's `result`. That's your whole capability, unit‑of‑work verified,
   with nothing else running.

> Payload fields are capability‑specific; the local mock returns a canned result,
> so any well‑formed request exercises the path. `scoring` additionally reads an
> upstream result — put it in `collectedResults`, e.g.
> `"collectedResults":{"bureau":{"bureauScore":780}}`.

Prefer a unit test? Each capability already has tests that drive it over an
in‑memory bus — run the module's `test` task; no Kafka needed at all.

---

## 7. The full end‑to‑end sequence (for the demo / integration)

When you *do* want the whole flow (e.g. to see a journey run end‑to‑end), the
order matters because each stage waits on Kafka topics:

```
1. Infra        docker compose -f docker-compose.infra.yml up -d aerospike kafka
2. Engine       run  origination-journey            (:8082)
3. Capabilities run  customer-party, kyc, bureau,   (:8090–8094)
                     scoring, lending-origination
4. Edge         run  sfdc-ingress-edge              (:8080)
5. Drive it     curl -XPOST localhost:8080/api/v1/sfdc/notifications \
                  -H 'X-Auth-Token: dev-token' -H 'Content-Type: application/json' \
                  -d '{"notificationId":"ntf-1","correlationId":"c-1","sfdcRecordId":"r-1",
                       "applicationRef":"APP-HIGH-1","orgId":"ORG1","type":"PERSONAL_LOAN",
                       "payload":{"amount":500000}}'
6. Watch        kafka-console-consumer ... --topic orig.decision.v1 --from-beginning
```

`applicationRef` containing `LOW` → CIBIL mock returns 540 → **REJECTED**;
anything else → 780 → **APPROVED + loanId**.

Order rationale: the engine and capabilities are Kafka consumers with
`auto-offset-reset: earliest`, so starting them before you POST guarantees they
see the messages. You can start them in any order *as long as they're all up
before step 5*; the sequence above is just the cleanest.

For the all‑in‑one path instead of running each by hand, see `docs/DEMO.md`
(`./demo.sh up`).

---

## 8. FAQ

**"If every service is the same app, isn't compose tight coupling?"**
No. Compose is a *packaging* convenience for a demo machine. The coupling that
matters is in the *code*, and there is none: a capability depends only on Kafka +
the shared contract. Delete every other module from your checkout and your
capability still builds, runs, and is testable. That's the whole point of the
hexagonal + capability‑per‑topic design.

**"Do capabilities need the engine running?"**
No. They react to `cap.<key>.request.v1` from anyone — the engine in the real
flow, or your console producer in dev.

**"Why does each capability open an HTTP port at all if it's Kafka‑only?"**
For health/metrics (`/actuator/health`, Prometheus) and so IntelliJ shows it as a
running web app. It carries no business endpoints.

**"How do I point one capability at a real vendor?"**
Set its `<VENDOR>_MODE=real` and `<VENDOR>_URL=...` in the run config's env, and
start that vendor (or its mock container). Everything else stays on mock.
