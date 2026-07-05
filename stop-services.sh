#!/usr/bin/env bash
# ---------------------------------------------------------------------------
# stop-services.sh - stop ALL IDFC microservices this launcher started.
# Thin wrapper over `run-services.sh stop` so you have a one-word command.
#     ./stop-services.sh
# ---------------------------------------------------------------------------
DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
exec "$DIR/run-services.sh" stop
