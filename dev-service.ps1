# ---------------------------------------------------------------------------
# dev-service.ps1 - restart / start / stop ONE service (the fast "edit one
# service, see it live" loop). A standalone COMPANION to run-services.ps1 -
# it does NOT modify that script.
#
# Why: `run-services.ps1` (right-click Run = `start`) intentionally SKIPS a
# service that is already running, so after a code change the live process
# keeps the old code. This script rebuilds just that ONE module's boot jar and
# bounces just that ONE process.
#
# It is fully interoperable with run-services.ps1: it reads the service table
# STRAIGHT FROM run-services.ps1 (so the two never drift) and uses the SAME
# .run\pids, .run\logs, jar paths and launch command - so `run-services.ps1
# status|logs|stop` still see services this script starts, and vice versa.
#
#   .\dev-service.ps1 restart customer-party   # stop -> rebuild its module -> start
#   .\dev-service.ps1 customer-party           # same (bare name defaults to restart)
#   .\dev-service.ps1 start   customer-party   # build module + start (skips if already up)
#   .\dev-service.ps1 stop    customer-party   # stop just that one
#   .\dev-service.ps1 restart customer-party -Clean    # clean module build first
#   .\dev-service.ps1 list                     # list service names / ports / modules
#
# Flags: -Clean (clean module build) - -NoBuild (skip build, just bounce) -
#        -Registry (engine journey-source=registry, like run-services.ps1).
# Requires: JDK 21 on PATH + the infra up (docker compose -f docker-compose.infra.yml up -d).
# ---------------------------------------------------------------------------
[CmdletBinding()]
param(
    [Parameter(Position = 0)] [string] $Command = 'restart',
    [Parameter(Position = 1)] [string] $Name,
    [switch] $NoBuild,
    [switch] $Clean,
    [switch] $Registry
)

$ErrorActionPreference = 'Stop'
Set-StrictMode -Version Latest

$Root     = $PSScriptRoot
$Version  = if ($env:VERSION) { $env:VERSION } else { '0.1.0-SNAPSHOT' }
$RunDir   = Join-Path $Root '.run'
$LogDir   = Join-Path $RunDir 'logs'
$PidDir   = Join-Path $RunDir 'pids'
$JavaOpts = if ($env:JAVA_OPTS) { $env:JAVA_OPTS } else { '' }

function Info($m) { Write-Host $m }
function Warn($m) { Write-Host $m -ForegroundColor Yellow }
function Err($m)  { Write-Host $m -ForegroundColor Red }
function Ok($m)   { Write-Host $m -ForegroundColor Green }

# --- Service table: parsed from run-services.ps1 (single source of truth) ----
function Get-Services {
    $launcher = Join-Path $Root 'run-services.ps1'
    if (-not (Test-Path $launcher)) {
        Err "run-services.ps1 not found next to this script ($launcher)"; exit 1
    }
    $rx = "name\s*=\s*'([^']+)'\s*;\s*module\s*=\s*'([^']+)'\s*;\s*port\s*=\s*(\d+)"
    $out = @()
    foreach ($line in Get-Content $launcher) {
        $m = [regex]::Match($line, $rx)
        if ($m.Success) {
            $out += [pscustomobject]@{
                name   = $m.Groups[1].Value
                module = $m.Groups[2].Value
                port   = [int]$m.Groups[3].Value
            }
        }
    }
    if ($out.Count -eq 0) { Err "could not read the service table from run-services.ps1"; exit 1 }
    return $out
}
$Services = @(Get-Services)

# --- effective port: .run\ports.env / PORT_<NAME> env override (parity) ------
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
function Get-EffPort($svcName, $default) {
    $var = 'PORT_' + ($svcName.ToUpper() -replace '[-]', '_')
    if ($PortOverrides.ContainsKey($var)) { return $PortOverrides[$var] }
    $envVal = [Environment]::GetEnvironmentVariable($var)
    if ($envVal) { return $envVal }
    return $default
}

function Test-PidAlive($processId) {
    if (-not $processId) { return $false }
    return [bool](Get-Process -Id $processId -ErrorAction SilentlyContinue)
}

function Get-Svc($svcName) {
    $svc = $Services | Where-Object { $_.name -eq $svcName } | Select-Object -First 1
    if (-not $svc) {
        Err "unknown service: $svcName"
        Info ('services: ' + (($Services | ForEach-Object { $_.name }) -join ', '))
        exit 1
    }
    return $svc
}

function Get-PidFor($svcName) {
    $pidfile = Join-Path $PidDir "$svcName.pid"
    if (-not (Test-Path $pidfile)) { return $null }
    $processId = (Get-Content $pidfile -ErrorAction SilentlyContinue | Select-Object -First 1)
    if (Test-PidAlive $processId) { return $processId }
    return $null
}

function Stop-Svc($svcName) {
    $processId = Get-PidFor $svcName
    if ($processId) {
        Stop-Process -Id $processId -Force -ErrorAction SilentlyContinue
        Err "  stopped $svcName (pid $processId)"
    } else {
        Info "  $svcName not running."
    }
    Remove-Item (Join-Path $PidDir "$svcName.pid") -Force -ErrorAction SilentlyContinue
}

function Build-Svc($svc) {
    $mod = ':' + ($svc.module -replace '/', ':')   # capabilities/customer-party -> :capabilities:customer-party
    [string[]] $tasks = if ($Clean) { "${mod}:clean", "${mod}:bootJar" } else { "${mod}:bootJar" }
    Info ("Building {0} (.\gradlew.bat {1})..." -f $svc.name, ($tasks -join ' '))
    Push-Location $Root
    try {
        & (Join-Path $Root 'gradlew.bat') @tasks --console=plain -q
        if ($LASTEXITCODE -ne 0) { Err "gradle build failed (exit $LASTEXITCODE)"; exit 1 }
    } finally {
        Pop-Location
    }
}

function Start-Svc($svc) {
    $svcName = $svc.name
    $port    = Get-EffPort $svcName $svc.port
    $jar     = Join-Path $Root "$($svc.module)/build/libs/$svcName-$Version.jar"
    if (-not (Test-Path $jar)) { Err "  ${svcName}: jar not found ($jar). Build first (drop -NoBuild)."; return }

    $journeySource = if ($Registry) { 'registry' } else { 'classpath' }
    $jarArgs = @()
    if ($JavaOpts) { $jarArgs += ($JavaOpts -split '\s+' | Where-Object { $_ }) }
    $jarArgs += '-jar', $jar, '--spring.profiles.active=local', "--server.port=$port"
    if ($svcName -eq 'origination-journey') { $jarArgs += "--idfc.engine.journey-source=$journeySource" }

    New-Item -ItemType Directory -Force -Path $LogDir, $PidDir | Out-Null
    $log    = Join-Path $LogDir "$svcName.log"
    $errlog = Join-Path $LogDir "$svcName.err.log"
    $proc = Start-Process -FilePath 'java' -ArgumentList $jarArgs `
        -RedirectStandardOutput $log -RedirectStandardError $errlog `
        -WindowStyle Hidden -PassThru
    $proc.Id | Out-File -FilePath (Join-Path $PidDir "$svcName.pid") -Encoding ascii
    Ok ('  {0,-22} port {1}  (pid {2})' -f "started $svcName", $port, $proc.Id)
}

# --- bare service name (no verb) defaults to restart -------------------------
$verbs = @('restart', 'start', 'stop', 'list')
if ($Command -and ($verbs -notcontains $Command) -and (-not $Name)) {
    $Name = $Command
    $Command = 'restart'
}

switch ($Command) {
    'list' {
        '{0,-22} {1,-6} {2}' -f 'SERVICE', 'PORT', 'MODULE' | Write-Host
        foreach ($s in $Services) { '{0,-22} {1,-6} {2}' -f $s.name, $s.port, $s.module | Write-Host }
    }
    'stop' {
        if (-not $Name) { Err 'usage: .\dev-service.ps1 stop <service>'; exit 1 }
        [void](Get-Svc $Name); Stop-Svc $Name
    }
    'start' {
        if (-not $Name) { Err 'usage: .\dev-service.ps1 start <service>'; exit 1 }
        $svc = Get-Svc $Name
        if (Get-PidFor $Name) { Warn "  $Name already running - use 'restart' to rebuild + bounce it"; break }
        if (-not $NoBuild) { Build-Svc $svc }
        Start-Svc $svc
    }
    'restart' {
        if (-not $Name) { Err 'usage: .\dev-service.ps1 restart <service>   (services: run `.\dev-service.ps1 list`)'; exit 1 }
        $svc = Get-Svc $Name
        Info "Restart single service: $Name"
        Stop-Svc $Name                       # stop first so the jar is not locked during the build (Windows)
        if (-not $NoBuild) { Build-Svc $svc }
        Start-Sleep -Milliseconds 300
        Start-Svc $svc
    }
    default {
        Err "unknown command: $Command"
        Info 'usage: .\dev-service.ps1 [restart|start|stop] <service> [-Clean] [-NoBuild] [-Registry]'
        Info '       .\dev-service.ps1 <service>        # bare name = restart'
        Info '       .\dev-service.ps1 list'
        exit 1
    }
}
