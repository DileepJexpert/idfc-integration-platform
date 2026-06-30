#!/usr/bin/env bash
# Full-flow demo driver for the IDFC integration platform.
#
#   edge (REST) -> Kafka origination -> origination-journey ENGINE
#       -> customer-party -> kyc -> bureau -> scoring -> [branch]
#           -> APPROVED: lending-origination (FinnOne booking) -> decision
#           -> REJECTED: no booking -> decision
#
# The branch is driven by applicationRef: anything is APPROVED (CIBIL 780);
# an applicationRef containing "LOW" is REJECTED (CIBIL 540).
#
# Usage:
#   ./demo.sh infra     # infra only (Aerospike+Kafka+mocks) — PULL-ONLY, no build
#   ./demo.sh images    # build the idfc/* service images (./gradlew bootBuildImage)
#   ./demo.sh services  # start the IDFC services (assumes infra is up)
#   ./demo.sh up        # infra (wait healthy) -> build images -> services
#   ./demo.sh ps        # status of the whole stack
#   ./demo.sh approved  # POST a high-score application (assisted / SFDC edge)
#   ./demo.sh rejected  # POST a low-score application (assisted / SFDC edge)
#   ./demo.sh digital   # POST via the DIGITAL partner edge -> SAME engine/core
#   ./demo.sh decisions # tail the engine's decision topic
#   ./demo.sh burst     # 10x burst on the edge (scale story)
#   ./demo.sh aql       # open Aerospike AQL shell (browse the idfc idempotency set)
#   ./demo.sh asadm     # open Aerospike admin (asadm) against the node
#   ./demo.sh down
#
# Browser UIs (started with infra): Kafka -> http://localhost:8085 ;
# SQL/Oracle (FinnOne) -> http://localhost:8978 (CloudBeaver).
set -euo pipefail

INFRA="docker compose -f docker-compose.infra.yml"
SERVICES="docker compose -f docker-compose.services.yml"
VERSION="${VERSION:-0.1.0-SNAPSHOT}"

# Bootable services that need an idfc/* image, mapped to their Gradle module dir.
# Each module's build/libs holds <name>-$VERSION.jar after `./gradlew bootJar`.
SERVICE_MODULES="
sfdc-ingress-edge:edges/sfdc-ingress-edge
digital-partner-edge:edges/digital-partner-edge
origination-journey:orchestration/origination-journey
customer-party:capabilities/customer-party
kyc:capabilities/kyc
bureau:capabilities/bureau
scoring:capabilities/scoring
lending-origination:capabilities/lending-origination
"

wait_healthy() { # wait until kafka + aerospike report healthy (or time out)
  echo "Waiting for infra to be healthy…"
  for _ in $(seq 1 60); do
    if docker inspect --format '{{.State.Health.Status}}' idfc-kafka idfc-aerospike 2>/dev/null \
         | grep -qv healthy; then sleep 3; else echo "infra healthy."; return 0; fi
  done
  echo "WARNING: infra not healthy after timeout; services will retry anyway."
}

build_images() { # boot jars -> idfc/* images via a plain JRE Dockerfile
  # NOTE: we deliberately avoid `./gradlew bootBuildImage`. Spring Boot 3.4.x's
  # buildpack plugin uses Docker API v1.24, which Docker Engine 29+ rejects.
  echo "Assembling boot jars (./gradlew bootJar)…"
  ./gradlew bootJar
  echo "Building idfc/* images from jars…"
  for entry in $SERVICE_MODULES; do
    name="${entry%%:*}"; module="${entry##*:}"
    echo "  -> idfc/${name}:${VERSION}"
    docker build -q -f infra/docker/Dockerfile \
      --build-arg JAR="${name}-${VERSION}.jar" \
      -t "idfc/${name}:${VERSION}" "${module}/build/libs" >/dev/null
  done
  echo "Images built."
}

EDGE="${EDGE:-localhost:8080}"
URL="$EDGE/api/v1/sfdc/notifications"
TOKEN="${TOKEN:-dev-token}"
KAFKA_HOST="${KAFKA_HOST:-localhost:29092}"

post() { # $1=notificationId $2=applicationRef
  curl -s -XPOST "$URL" \
    -H "X-Auth-Token: $TOKEN" -H 'Content-Type: application/json' \
    -d "{\"notificationId\":\"$1\",\"correlationId\":\"corr-$1\",\"sfdcRecordId\":\"rec-$1\",
         \"applicationRef\":\"$2\",\"orgId\":\"ORG1\",\"type\":\"PERSONAL_LOAN\",
         \"payload\":{\"amount\":500000}}"
  echo
}

case "${1:-}" in
  infra)
    echo "Starting infra (Aerospike + Kafka + vendor mocks) — no build needed…"
    $INFRA up -d
    wait_healthy
    ;;
  images)
    build_images
    ;;
  services)
    echo "Starting IDFC services (infra must already be up)…"
    $SERVICES up -d
    echo "Up. Watch health:  ./demo.sh ps"
    ;;
  up)
    echo "1/3 infra…"; $INFRA up -d; wait_healthy
    echo "2/3 building images…"; build_images
    echo "3/3 services…"; $SERVICES up -d
    echo "Up. Status:  ./demo.sh ps"
    ;;
  ps) docker compose ps ;;
  approved) post "ntf-$(date +%s)" "APP-HIGH-1" ;;
  rejected) post "ntf-$(date +%s)" "APP-LOW-1" ;;
  digital)
    # A fintech partner (CRED) originates over the DIGITAL edge (:8081) — the SAME
    # engine + capabilities handle it (envelope-identical proof). LOW -> rejected.
    REF="${2:-APP-HIGH-D1}"
    curl -s -XPOST "localhost:8081/api/v1/digital/origination" \
      -H "X-Partner-Token: ${CRED_TOKEN:-cred-dev-token}" -H 'Content-Type: application/json' \
      -d "{\"requestId\":\"req-$(date +%s)\",\"applicationRef\":\"$REF\",\"type\":\"PERSONAL_LOAN\",
           \"orgId\":\"ORG1\",\"payload\":{\"amount\":300000}}"
    echo ;;
  decisions)
    echo "Tailing orig.decision.v1 (Ctrl-C to stop)…"
    docker exec idfc-kafka /opt/kafka/bin/kafka-console-consumer.sh \
      --bootstrap-server localhost:9092 --topic orig.decision.v1 --from-beginning
    ;;
  burst)
    echo "10x burst on the edge (same notificationId resends are deduped)…"
    for i in $(seq 1 10); do post "burst-$i" "APP-HIGH-$i" & done; wait
    echo "Watch FinnOne concurrency stay bounded and the backlog drain:"
    echo "  docker compose logs -f lending-origination origination-journey"
    ;;
  aql)
    # Aerospike has no free CE web UI; AQL is the data-browsing shell.
    docker run --rm -it --network idfc aerospike/aerospike-tools:latest aql -h aerospike
    ;;
  asadm)
    docker run --rm -it --network idfc aerospike/aerospike-tools:latest asadm -h aerospike
    ;;
  down) docker compose down -v ;;
  *) grep '^#' "$0" | sed 's/^# \{0,1\}//' ;;
esac
