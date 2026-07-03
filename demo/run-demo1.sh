#!/usr/bin/env bash
# DEMO 1 — brand-as-config: fire one device-financing message per brand at the
# engine's demo door (orig.demo.device.v1). Kafka reachable on localhost:29092
# (the compose HOST listener). Usage: run-demo1.sh [BRAND [DEVICE]] — no args
# fires the full four-outcome set.
set -euo pipefail
BROKER="${KAFKA_BROKER:-localhost:29092}"
TOPIC="orig.demo.device.v1"

send() { # $1 correlationId  $2 brand  $3 deviceId
  local corr="$1" brand="$2" device="$3"
  printf '%s\n' "{\"transactionId\":\"$corr-t\",\"schemaVersion\":\"demo.v1\",\"source\":\"FILE_DEMO\",\"type\":\"DEVICE_FINANCING\",\"notificationId\":\"$corr-n\",\"orgId\":\"DEMO-ORG\",\"sfdcRecordId\":\"$device\",\"applicationRef\":\"$corr-app\",\"correlationId\":\"$corr\",\"originalCorrelationId\":\"$corr\",\"payloadContentType\":\"application/json\",\"occurredAt\":\"$(date -u +%Y-%m-%dT%H:%M:%SZ)\",\"payload\":{\"brand\":\"$brand\",\"deviceId\":\"$device\"}}" |
    docker compose -f docker-compose.infra.yml exec -T kafka \
      kafka-console-producer --bootstrap-server "$BROKER" --topic "$TOPIC" >/dev/null
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
echo "open the ops view and search the keys above (or filter journey device-financing)"
