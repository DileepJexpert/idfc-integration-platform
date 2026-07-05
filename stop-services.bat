@echo off
REM ===========================================================================
REM  stop-services.bat - ONE-CLICK stop for ALL IDFC microservices.
REM
REM  Double-click (or run from a terminal) to stop every service that
REM  run-services started. Thin wrapper over `run-services.ps1 stop`.
REM ===========================================================================
setlocal
cd /d "%~dp0"

powershell -NoProfile -ExecutionPolicy Bypass -File "%~dp0run-services.ps1" stop
set "RC=%ERRORLEVEL%"

echo.
echo Done (exit %RC%). Press any key to close this window . . .
pause >nul

endlocal
exit /b %RC%
