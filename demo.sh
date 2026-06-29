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
#   ./demo.sh up        # build images + start everything
#   ./demo.sh approved  # POST a high-score application (assisted / SFDC edge)
#   ./demo.sh rejected  # POST a low-score application (assisted / SFDC edge)
#   ./demo.sh digital   # POST via the DIGITAL partner edge -> SAME engine/core
#   ./demo.sh decisions # tail the engine's decision topic
#   ./demo.sh burst     # 10x burst on the edge (scale story)
#   ./demo.sh down
set -euo pipefail

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
  up)
    echo "Building service images (Spring Boot buildpacks)…"
    ./gradlew bootBuildImage
    echo "Starting infra + services + mock vendors…"
    docker compose up -d
    echo "Up. Watch health:  docker compose ps"
    ;;
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
  down) docker compose down -v ;;
  *) grep '^#' "$0" | sed 's/^# \{0,1\}//' ;;
esac
