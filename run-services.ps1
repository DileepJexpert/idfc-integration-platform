# ---------------------------------------------------------------------------
# run-services.ps1 - start ALL IDFC microservices on the HOST (fast dev loop).
#
# Native Windows / IntelliJ port of run-services.sh. Same behaviour: it does
# NOT build Docker images or run containers. It builds the boot jars ONCE
# (.\gradlew.bat bootJar) and launches each service as a plain `java -jar`
# process with the `local` Spring profile, wired to the dockerized infra over
# host ports (Kafka :29092, Aerospike :3000, vendor mocks :9101-05).
#
# YOU run the infra yourself, once (it is long-lived):
#     docker compose -f docker-compose.infra.yml up -d
#
# Then, from IntelliJ (or any PowerShell terminal):
#     .\run-services.ps1              # build jars + start every service in background
#     .\run-services.ps1 -NoBuild     # skip the build, just (re)start from existing jars
#     .\run-services.ps1 -Registry    # use the journey-registry seam (needs a published
#                                     # journey) instead of the classpath fallback
#     .\run-services.ps1 status       # what's up, on which port/PID
#     .\run-services.ps1 logs kyc     # tail one service's log
#     .\run-services.ps1 stop         # stop everything this script started
#     .\run-services.ps1 restart      # stop + start
#
# Logs:  .run\logs\<service>.log     PIDs:  .run\pids\<service>.pid
# Requires: JDK 21 on PATH (the jars are built for 21) and the infra up.
#
# If PowerShell blocks the script ("running scripts is disabled"), run once:
#     Set-ExecutionPolicy -Scope CurrentUser -ExecutionPolicy RemoteSigned
# or launch it as:  powershell -ExecutionPolicy Bypass -File .\run-services.ps1
# ---------------------------------------------------------------------------
[CmdletBinding()]
param(
    [Parameter(Position = 0)]
    [string] $Command = 'start',

    [Parameter(ValueFromRemainingArguments = $true)]
    [string[]] $Rest,

    [switch] $NoBuild,
    [switch] $Registry
)

$ErrorActionPreference = 'Stop'
Set-StrictMode -Version Latest

$Root    = $PSScriptRoot
$Version = if ($env:VERSION) { $env:VERSION } else { '0.1.0-SNAPSHOT' }
$RunDir  = Join-Path $Root '.run'
$LogDir  = Join-Path $RunDir 'logs'
$PidDir  = Join-Path $RunDir 'pids'
$JavaOpts = if ($env:JAVA_OPTS) { $env:JAVA_OPTS } else { '' }

# service | gradle module dir | http port  (registry first so it precedes the engine)
$Services = @(
    @{ name = 'journey-registry';    module = 'platform/journey-registry';         port = 8104 }
    @{ name = 'origination-journey'; module = 'orchestration/origination-journey';  port = 8082 }
    @{ name = 'sfdc-ingress-edge';   module = 'edges/sfdc-ingress-edge';            port = 8080 }
    @{ name = 'digital-partner-edge'; module = 'edges/digital-partner-edge';        port = 8081 }
    @{ name = 'customer-party';      module = 'capabilities/customer-party';        port = 8090 }
    @{ name = 'kyc';                 module = 'capabilities/kyc';                   port = 8091 }
    @{ name = 'bureau';              module = 'capabilities/bureau';                port = 8092 }
    @{ name = 'scoring';             module = 'capabilities/scoring';               port = 8093 }
    @{ name = 'lending-origination'; module = 'capabilities/lending-origination';   port = 8094 }
)

# --- Optional machine-local port overrides (gitignored) ---------------------
# Reads .run\ports.env lines like:  PORT_CUSTOMER_PARTY=8095
$PortOverrides = @{}
$portsFile = Join-Path $RunDir 'ports.env'
if (Test-Path $portsFile) {
    foreach ($line in Get-Content $portsFile) {
        $t = $line.Trim()
        if ($t -eq '' -or $t.StartsWith('#')) { continue }
        $kv = $t -split '=', 2
        if ($kv.Count -eq 2) { $PortOverrides[$kv[0].Trim()] = $kv[1].Trim() }
    }
}

function Info($msg)  { Write-Host $msg }
function Warn($msg)  { Write-Host $msg -ForegroundColor Yellow }
function Err($msg)   { Write-Host $msg -ForegroundColor Red }
function Ok($msg)    { Write-Host $msg -ForegroundColor Green }

# effective port: PORT_<NAME_UPPER_WITH_UNDERSCORES> (ports.env or env) wins, else default
function Get-EffPort($name, $default) {
    $var = 'PORT_' + ($name.ToUpper() -replace '[-]', '_')
    if ($PortOverrides.ContainsKey($var)) { return $PortOverrides[$var] }
    $envVal = [Environment]::GetEnvironmentVariable($var)
    if ($envVal) { return $envVal }
    return $default
}

# --- lightweight TCP probe --------------------------------------------------
function Test-Port($hostName, $port) {
    $client = New-Object System.Net.Sockets.TcpClient
    try {
        $async = $client.BeginConnect($hostName, [int]$port, $null, $null)
        $ok = $async.AsyncWaitHandle.WaitOne(400)
        if ($ok -and $client.Connected) { return $true }
        return $false
    } catch {
        return $false
    } finally {
        $client.Close()
    }
}

function Test-Infra {
    $ok = $true
    if (-not (Test-Port 'localhost' 29092)) { Warn '  Kafka   localhost:29092  NOT reachable'; $ok = $false }
    if (-not (Test-Port 'localhost' 3000))  { Warn '  Aerospike localhost:3000 NOT reachable'; $ok = $false }
    if (-not $ok) {
        Err 'Infra is not up. Start it first:  docker compose -f docker-compose.infra.yml up -d'
        exit 1
    }
    Ok 'Infra reachable (Kafka :29092, Aerospike :3000)'
}

function Test-PidAlive($processId) {
    if (-not $processId) { return $false }
    return [bool](Get-Process -Id $processId -ErrorAction SilentlyContinue)
}

function Start-All {
    param([bool]$SkipBuild, [string]$JourneySource)

    Test-Infra
    New-Item -ItemType Directory -Force -Path $LogDir, $PidDir | Out-Null

    if (-not $SkipBuild) {
        Info 'Building boot jars (.\gradlew.bat bootJar)...'
        Push-Location $Root
        try {
            & (Join-Path $Root 'gradlew.bat') bootJar --console=plain -q
            if ($LASTEXITCODE -ne 0) { Err "gradle build failed (exit $LASTEXITCODE)"; exit 1 }
        } finally {
            Pop-Location
        }
    }

    if ($JourneySource -eq 'classpath') {
        Info 'Engine journey-source=classpath (bundled journeys; use -Registry for the designer->engine seam)'
    } else {
        Warn 'Engine journey-source=registry - it will FAIL CLOSED unless a journey is published (see docs/testing/REGISTRY_RUNBOOK.md 2)'
    }

    foreach ($svc in $Services) {
        $name = $svc.name
        $port = Get-EffPort $name $svc.port
        $jar  = Join-Path $Root "$($svc.module)/build/libs/$name-$Version.jar"
        $pidfile = Join-Path $PidDir "$name.pid"

        if (Test-Path $pidfile) {
            $existing = (Get-Content $pidfile -ErrorAction SilentlyContinue | Select-Object -First 1)
            if (Test-PidAlive $existing) {
                Warn "  $name already running (pid $existing) - skipping"
                continue
            }
        }
        if (-not (Test-Path $jar)) {
            Err "  ${name}: jar not found ($jar). Run without -NoBuild."
            continue
        }

        $jarArgs = @()
        if ($JavaOpts) { $jarArgs += ($JavaOpts -split '\s+' | Where-Object { $_ }) }
        $jarArgs += '-jar', $jar, '--spring.profiles.active=local', "--server.port=$port"
        if ($name -eq 'origination-journey') {
            $jarArgs += "--idfc.engine.journey-source=$JourneySource"
        }

        $log    = Join-Path $LogDir "$name.log"
        $errlog = Join-Path $LogDir "$name.err.log"
        $proc = Start-Process -FilePath 'java' -ArgumentList $jarArgs `
            -RedirectStandardOutput $log -RedirectStandardError $errlog `
            -WindowStyle Hidden -PassThru
        $proc.Id | Out-File -FilePath $pidfile -Encoding ascii
        '  {0,-22} port {1}  (pid {2})' -f "started $name", $port, $proc.Id | ForEach-Object { Ok $_ }
    }

    Info ''
    Info 'All launched. Boot takes ~5-10s each; watch a log with:  .\run-services.ps1 logs <name>'
    Info 'Edges:  SFDC  http://localhost:8080   -  Digital http://localhost:8081'
    Info 'Engine: http://localhost:8082    -  Registry http://localhost:8104'
}

function Stop-All {
    if (-not (Test-Path $PidDir)) { Info 'Nothing to stop.'; return }
    foreach ($svc in $Services) {
        $name = $svc.name
        $pidfile = Join-Path $PidDir "$name.pid"
        if (-not (Test-Path $pidfile)) { continue }
        $processId = (Get-Content $pidfile -ErrorAction SilentlyContinue | Select-Object -First 1)
        if (Test-PidAlive $processId) {
            Stop-Process -Id $processId -Force -ErrorAction SilentlyContinue
            Err "  stopped $name (pid $processId)"
        }
        Remove-Item $pidfile -Force -ErrorAction SilentlyContinue
    }
}

function Get-StatusAll {
    '{0,-22} {1,-6} {2,-8} {3}' -f 'SERVICE', 'PORT', 'PID', 'STATE' | Write-Host
    foreach ($svc in $Services) {
        $name = $svc.name
        $port = Get-EffPort $name $svc.port
        $pidfile = Join-Path $PidDir "$name.pid"
        $processId = '-'
        $state = 'stopped'; $color = 'DarkGray'
        if (Test-Path $pidfile) {
            $processId = (Get-Content $pidfile -ErrorAction SilentlyContinue | Select-Object -First 1)
            if (Test-PidAlive $processId) {
                if (Test-Port 'localhost' $port) { $state = 'up'; $color = 'Green' }
                else { $state = 'booting'; $color = 'Yellow' }
            } else {
                $processId = '-'
            }
        }
        $line = '{0,-22} {1,-6} {2,-8} ' -f $name, $port, $processId
        Write-Host $line -NoNewline
        Write-Host $state -ForegroundColor $color
    }
}

function Get-LogsOne($name) {
    if (-not $name) { Err 'usage: .\run-services.ps1 logs <service>'; exit 1 }
    $f = Join-Path $LogDir "$name.log"
    if (-not (Test-Path $f)) { Err "no log for '$name' at $f"; exit 1 }
    Get-Content -Path $f -Wait -Tail 40
}

# --- dispatch ---------------------------------------------------------------
# Allow a leading flag as the command:  .\run-services.ps1 --no-build
if ($Command -like '--*') {
    switch ($Command) {
        '--no-build' { $NoBuild = $true }
        '--registry' { $Registry = $true }
    }
    $Command = 'start'
}
# Also accept bash-style flags mixed into the remaining args.
if ($Rest) {
    if ($Rest -contains '--no-build') { $NoBuild = $true }
    if ($Rest -contains '--registry') { $Registry = $true }
}

$journeySource = if ($Registry) { 'registry' } else { 'classpath' }

switch ($Command) {
    { $_ -in 'start', '' }   { Start-All -SkipBuild:$NoBuild -JourneySource $journeySource }
    'stop'                   { Stop-All }
    'restart'                { Stop-All; Start-Sleep -Seconds 1; Start-All -SkipBuild:$NoBuild -JourneySource $journeySource }
    'status'                 { Get-StatusAll }
    'logs'                   { Get-LogsOne ($Rest | Select-Object -First 1) }
    default {
        Err "unknown command: $Command"
        Info 'commands: start | stop | restart | status | logs <name>'
        exit 1
    }
}
