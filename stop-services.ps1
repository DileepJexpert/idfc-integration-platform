# ---------------------------------------------------------------------------
# stop-services.ps1 - stop ALL IDFC microservices this launcher started.
# Thin wrapper over `run-services.ps1 stop` so you have a one-word command.
#     .\stop-services.ps1
# ---------------------------------------------------------------------------
& (Join-Path $PSScriptRoot 'run-services.ps1') stop
exit $LASTEXITCODE
