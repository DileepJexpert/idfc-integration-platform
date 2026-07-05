@echo off
REM ===========================================================================
REM  run-services.bat - ONE-CLICK launcher for ALL IDFC microservices.
REM
REM  Double-click this file (or run it from cmd/PowerShell) to build the boot
REM  jars once and start every service as a plain `java -jar` process with the
REM  `local` Spring profile. It just wraps run-services.ps1 so you don't have to
REM  deal with PowerShell's execution policy or type the full command.
REM
REM  PREREQUISITE (you do this manually, once - it is long-lived):
REM      docker compose -f docker-compose.infra.yml up -d
REM  If infra isn't up yet the script WARNS but still launches; the services
REM  retry their Kafka/Aerospike connections until infra appears.
REM
REM  Usage:
REM      run-services.bat                 (double-click) build jars + start all
REM      run-services.bat --clean         clean build (clean bootJar) when a change won't reflect
REM      run-services.bat --no-build      skip build, restart from existing jars
REM      run-services.bat --registry      use the journey-registry seam
REM      run-services.bat status          what's up, on which port/PID
REM      run-services.bat logs kyc        tail one service's log
REM      run-services.bat stop            stop everything this launcher started
REM      run-services.bat restart         stop + start
REM
REM  Requires: JDK 21 on PATH.
REM ===========================================================================
setlocal
cd /d "%~dp0"

powershell -NoProfile -ExecutionPolicy Bypass -File "%~dp0run-services.ps1" %*
set "RC=%ERRORLEVEL%"

REM Keep the window open when launched by double-click so you can read output.
REM (Skipped for `logs`, which tails and blocks on its own.)
if /I "%~1"=="logs" goto :end
echo.
echo Done (exit %RC%). Press any key to close this window . . .
pause >nul

:end
endlocal
exit /b %RC%
