# Profiles & deployment — local (docker infra) and EKS

Three ways to run a service, selected by **Spring profile**. The base
`application.yml` of each service holds neutral, env-overridable defaults; a
profile overlays the differences.

| Profile | Where | Infra it talks to | Vendors |
|---|---|---|---|
| *(none)* | laptop, quick dev | Kafka `localhost:9092` | **in-app mock** (Kafka only) |
| `local` | laptop, against docker | docker-compose infra (Kafka `localhost:29092`, Aerospike `:3000`, mocks `:910x`, Oracle `:1521`) | **real wire → docker mocks** |
| `eks` | the cluster | from the ConfigMap/Secret (MSK, Aerospike, real vendors) | **real** |

> Why two non-prod modes? *No profile* is the lightest loop for the team that
> owns one capability (see `LOCAL_DEV.md`): only Kafka, vendor mocked in-process.
> The `local` profile exercises the **full wire** end-to-end against the
> dockerised infra — closer to production, still on your laptop.

---

## 1. `local` profile — run on your machine against the docker infra

**Start the infra once:**

```bash
docker compose -f docker-compose.infra.yml up -d      # Kafka, Aerospike, vendor mocks, Oracle
```

**Run any service with the `local` profile.** The bundled IntelliJ run configs
(`.run/*.run.xml`) already set `Active profiles = local`, so just hit ▶ on
`04. customer-party :8090`. From the CLI:

```bash
SPRING_PROFILES_ACTIVE=local ./gradlew :capabilities:customer-party:bootRun
# or a jar:
SPRING_PROFILES_ACTIVE=local java -jar capabilities/customer-party/build/libs/*.jar
```

What `local` changes vs. the base config:

- **Kafka → `localhost:29092`** — the docker broker's *host* listener. (The
  internal listener advertises `kafka:9092`, which only resolves inside the
  docker network, so a host-run app must use 29092.)
- **Vendors → `real`** pointing at the docker mocks
  (`customer-party→:9101`, `bureau→:9102`, `scoring→:9103`, `kyc→:9104`).
- **lending-origination → real FinnOne** over Oracle at `localhost:1521`
  (`XEPDB1`, `finnone/finnone`). Oracle-XE takes ~1–2 min to go healthy; override
  `FINNONE_MODE=mock` in the run config to skip it.
- **engine → durable Aerospike run state** at `localhost:3000`.

You do **not** need the `idfc/*` service containers for this — only the infra.
Run just the services you're working on.

---

## 2. `eks` profile + manifests — deploy to the cluster

The `eks` profile sets the production **behaviour** (real vendors, durable engine
state, real Oracle datasource); the **endpoints** come from a ConfigMap + Secret
via the env‑var placeholders already in each `application.yml`.

Manifests live in `deploy/eks/` (Kustomize):

```
deploy/eks/
  namespace.yaml          idfc namespace
  configmap.yaml          idfc-platform-config  — endpoints (Kafka/MSK, Aerospike, vendor URLs, FinnOne JDBC)
  secret.yaml             idfc-platform-secrets — tokens + DB password (PLACEHOLDERS; source from AWS SM in real life)
  ingress.yaml            ALB Ingress — exposes ONLY the two edges
  services/<svc>.yaml     Deployment + ClusterIP Service per service (SPRING_PROFILES_ACTIVE=eks)
  kustomization.yaml      ties it together; `images:` sets registry/tag
```

### Build & push images

```bash
./gradlew bootBuildImage                  # produces idfc/<module>:0.1.0-SNAPSHOT
# tag + push each to ECR, e.g.:
docker tag idfc/customer-party:0.1.0-SNAPSHOT \
  1234567890.dkr.ecr.ap-south-1.amazonaws.com/idfc/customer-party:1.0.0
docker push 1234567890.dkr.ecr.ap-south-1.amazonaws.com/idfc/customer-party:1.0.0
```

Point Kustomize at your registry/tag (no file edits) in `kustomization.yaml`:

```yaml
images:
  - name: idfc/customer-party
    newName: 1234567890.dkr.ecr.ap-south-1.amazonaws.com/idfc/customer-party
    newTag: 1.0.0
  # ...one per service
```

### Configure endpoints & secrets

- Edit `configmap.yaml` placeholders (`KAFKA_BOOTSTRAP_SERVERS`, `AEROSPIKE_HOST`,
  the `*_URL` vendor endpoints, `FINNONE_JDBC_URL`).
- **Do not commit real secrets.** Replace `secret.yaml` with an
  ExternalSecret (External Secrets Operator → AWS Secrets Manager) or create it
  out of band:
  ```bash
  kubectl -n idfc create secret generic idfc-platform-secrets \
    --from-literal=SFDC_EDGE_TOKEN=… --from-literal=CRED_TOKEN=… \
    --from-literal=FINNONE_DB_PASSWORD=…
  ```

### Apply

```bash
kubectl apply -k deploy/eks
kubectl -n idfc get pods,svc,ingress
```

### What the cluster topology looks like

```
            ALB Ingress (HTTPS)            ── only the edges are public ──
                  │
        ┌─────────┴──────────┐
   sfdc-ingress-edge   digital-partner-edge          (ClusterIP, 2 replicas)
        └─────────┬──────────┘
                  ▼  Kafka (MSK)
           origination-journey  ◀───────────────┐    (engine, durable state in Aerospike)
                  │  cap.*.request.v1            │ cap.*.response.v1
   ┌──────┬───────┼────────┬──────────┬──────────┘
customer  kyc  bureau   scoring   lending-origination   payments   lending-servicing
 -party                                                            (all ClusterIP, internal-only)
```

Prereqs provided by the platform team (not by this kustomization): the EKS
cluster, the AWS Load Balancer Controller, Kafka (MSK) and Aerospike reachable at
the configured endpoints, and the images pushed to your registry.

---

## 3. Independent teams

Each Deployment is its own release unit: a team owning `bureau` builds
`idfc/bureau`, bumps its tag in `kustomization.yaml`, and `kubectl apply -k`
re-rolls **only** that Deployment. No service shares a process, a port, or a
deploy with another — the same independence the code already enforces
(`LOCAL_DEV.md`), now carried through to the cluster.
