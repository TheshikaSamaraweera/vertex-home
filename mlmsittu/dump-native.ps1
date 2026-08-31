<#
================================================================================================
Dump your NATIVE PostgreSQL database (localhost:5432) to a file that .\load-data.ps1 can load
into the containers.

    .\dump-native.ps1
    .\dump-native.ps1 -Out db-snapshot\before-i-broke-it.sql

This reads only. Nothing is changed in either database.

Run it whenever the native database has data you want the containers to start from — the two
drift apart the moment you start using both.
================================================================================================
#>
param(
    [string]$Out = 'db-snapshot\mlmsitty-local.sql',
    [string]$Database = 'mlmsitty'
)

$ErrorActionPreference = 'Stop'
Set-Location $PSScriptRoot

$pgDump = 'C:\Program Files\PostgreSQL\18\bin\pg_dump.exe'
if (-not (Test-Path $pgDump)) {
    Write-Host "pg_dump not found at:`n  $pgDump" -ForegroundColor Red
    Write-Host "Edit the path at the top of this script if PostgreSQL is installed elsewhere."
    exit 1
}

$path = Join-Path $PSScriptRoot $Out
New-Item -ItemType Directory -Force (Split-Path $path) | Out-Null

$env:PGPASSWORD = 'postgres'

# --no-owner because the container restores as `postgres` and the ownership lines would refer to
# roles that may not exist there.
#
# Privileges are deliberately NOT excluded. The dump carries Flyway's history, so on restore
# Flyway sees every migration as already applied and never re-runs them — which means the GRANTs
# to mlmsittu_app must come from the dump itself, or the application cannot read its own tables.
& $pgDump -U postgres -h localhost -d $Database --no-owner -f $path
if (-not $?) {
    Write-Host "pg_dump failed. Is PostgreSQL running on localhost:5432?" -ForegroundColor Red
    exit 1
}

$kb = [math]::Round((Get-Item $path).Length / 1KB)
$grants = (Select-String -Path $path -Pattern '^GRANT' -AllMatches).Count

Write-Host ""
Write-Host "  Wrote     $Out ($kb KB)" -ForegroundColor Green
Write-Host "  Grants    $grants  (must be > 0, or the app cannot read its tables)"
Write-Host ""
Write-Host "Load it with:  .\load-data.ps1"
Write-Host ""
