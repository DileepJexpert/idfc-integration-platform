#!/usr/bin/env bash
# DEMO 1 — brand-as-config: fire one device-validation message per brand at the
# engine's demo door (orig.device-validation.v1). The producer runs INSIDE the kafka
# container (docker exec), so it talks to the in-container INTERNAL listener
# localhost:9092 — the apache/kafka image only ships the .sh-suffixed CLI under
# /opt/kafka/bin. Usage: run-demo1.sh [BRAND [DEVICE]] — no args fires the full
# four-outcome set.
set -euo pipefail
BROKER="${KAFKA_BROKER:-localhost:9092}"
TOPIC="orig.device-validation.v1"

send() { # $1 correlationId  $2 brand  $3 deviceId
  local corr="$1" brand="$2" device="$3"
  printf '%s\n' "{\"transactionId\":\"$corr-t\",\"schemaVersion\":\"demo.v1\",\"source\":\"FILE_DEMO\",\"type\":\"DEVICE_VALIDATION\",\"notificationId\":\"$corr-n\",\"orgId\":\"DEMO-ORG\",\"sfdcRecordId\":\"$device\",\"applicationRef\":\"$corr-app\",\"correlationId\":\"$corr\",\"originalCorrelationId\":\"$corr\",\"payloadContentType\":\"application/json\",\"occurredAt\":\"$(date -u +%Y-%m-%dT%H:%M:%SZ)\",\"payload\":{\"brand\":\"$brand\",\"deviceId\":\"$device\"}}" |
    docker compose -f docker-compose.infra.yml exec -T kafka \
      /opt/kafka/bin/kafka-console-producer.sh --bootstrap-server "$BROKER" --topic "$TOPIC" >/dev/null
  echo "sent $brand deviceId=$device  -> ops search key: $corr"
}

STAMP="$(date +%s)"
if [[ $# -ge 1 ]]; then
  send "corr-${1,,}-$STAMP" "$1" "${2:-DEV-9}"
else
  send "corr-samsung-$STAMP"      SAMSUNG DEV-1        # validate + block -> APPROVED
  send "corr-godrej-$STAMP"       GODREJ  DEV-2        # block only      -> APPROVED
  send "corr-bosch-decl-$STAMP"   BOSCH   DEV-DECLINE  # vendor says no  -> DECLINED (teal)
  send "corr-samsung-fail-$STAMP" SAMSUNG DEV-FAIL     # vendor error    -> FAILED (PERMANENT)
fi
echo "open the ops view and search the keys above (or filter journey device-validation)"
