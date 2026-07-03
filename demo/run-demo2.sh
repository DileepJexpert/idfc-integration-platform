#!/usr/bin/env bash
# DEMO 2 — file-batch: drop the sample CSV (5 records, EMP-004 crafted to
# fail) into the demo inbox. The fusion-hcm-demo app (started with
# --demo.batch.enabled=true) picks it up within ~2s and starts one engine run
# per record. Re-running this script re-drops the SAME content: the ledger —
# and, ledger or not, the engine's dedup — refuse a re-run.
set -euo pipefail
DIR="$(cd "$(dirname "$0")" && pwd)"
cp "$DIR/employees-sample.csv" "$DIR/batch-inbox/employees-$(date +%s).csv"
echo "dropped 5-record sample into demo/batch-inbox/ — watch the ops view:"
echo "  the batch id (notificationId, batch-...) is ONE search key for all 5 runs"
