<#
================================================================================================
Load data into the containerised database.

    .\load-data.ps1                              # loads db-snapshot\mlmsitty-local.sql
    .\load-data.ps1 -File other-dump.sql         # loads a different file
    .\load-data.ps1 -Force                       # skip the confirmation

This REPLACES everything in the container's database. Your native PostgreSQL on 5432 is never
touched.

Why it replaces rather than appends: the file is a full pg_dump — schema, data, grants, and
Flyway's migration history. By the time you run this the application has already started and
Flyway has already created those tables, so loading on top would fail on the first CREATE TABLE.
Dropping the schema first makes the load repeatable instead of a one-time trick.
================================================================================================
#>
param(
    [string]$File = 'db-snapshot\mlmsitty-local.sql',
    [switch]$Force
)

# Deliberately NOT 'Stop'.
#
# This script is almost entirely native commands, and `docker compose` writes its ordinary
# progress ("Container mlmsittu-app Stopping") to stderr. Windows PowerShell 5.1 turns a native
# command's stderr into ErrorRecords, so with ErrorActionPreference='Stop' the script aborts on a
# perfectly successful step. Redirecting with 2>&1 or 2>$null does not help — the records are
# created either way.
#
# So: let stderr through, and decide success from $LASTEXITCODE, which is what the exit code is
# for. Native failures are checked explicitly at each step below.
$ErrorActionPreference = 'Continue'
Set-Location $PSScriptRoot

$Compose = @('-f', 'docker-compose.local.yml')

function Fail($message) {
    Write-Host ""
    Write-Host $message -ForegroundColor Red
    exit 1
}

if (-not (Get-Command docker -ErrorAction SilentlyContinue)) {
    Fail "Docker is not installed or not on PATH. See LOCAL-DOCKER.md."
}

$path = Join-Path $PSScriptRoot $File
if (-not (Test-Path $path)) {
    Fail "No such file: $path`nRun .\dump-native.ps1 to create one from your local PostgreSQL."
}

# The database has to be up. The application does not — it is stopped in a moment anyway.
docker compose @Compose exec -T db pg_isready -U postgres -d mlmsitty | Out-Null
if ($LASTEXITCODE -ne 0) {
    Fail "The database container is not ready.`nStart it with:  docker compose -f docker-compose.local.yml up -d db"
}

$sizeKb = [math]::Round((Get-Item $path).Length / 1KB)
Write-Host ""
Write-Host "  File      $File ($sizeKb KB)"
Write-Host "  Into      mlmsitty in the 'db' container (localhost:5433)"
Write-Host "  Effect    everything currently in that database is DELETED first" -ForegroundColor Yellow
Write-Host "  Safe      your native PostgreSQL on 5432 is not touched" -ForegroundColor DarkGray
Write-Host ""

if (-not $Force) {
    $answer = Read-Host "Type 'load' to continue"
    if ($answer -ne 'load') { Write-Host "Cancelled."; exit 0 }
}

# Stop the application first. Restoring underneath a running app means Hibernate holds open
# connections to the tables being dropped, and DROP SCHEMA blocks behind them.
Write-Host "`nStopping the application..." -ForegroundColor Cyan
docker compose @Compose stop app | Out-Null
if ($LASTEXITCODE -ne 0) { Fail "Could not stop the app container." }

Write-Host "Clearing the database..." -ForegroundColor Cyan
$reset = @"
DROP SCHEMA public CASCADE;
CREATE SCHEMA public;
GRANT USAGE ON SCHEMA public TO mlmsittu_app;
"@
$reset | docker compose @Compose exec -T db psql -U postgres -d mlmsitty -v ON_ERROR_STOP=1 -q
if ($LASTEXITCODE -ne 0) { Fail "Could not clear the schema. Nothing has been loaded." }

Write-Host "Loading $File ..." -ForegroundColor Cyan
Get-Content $path -Raw -Encoding UTF8 |
    docker compose @Compose exec -T db psql -U postgres -d mlmsitty -v ON_ERROR_STOP=1 -q
if ($LASTEXITCODE -ne 0) {
    Write-Host ""
    Write-Host "The load failed partway through. The database is left as it is so you can look." -ForegroundColor Red
    Write-Host "Inspect it with:  docker compose -f docker-compose.local.yml exec db psql -U postgres -d mlmsitty"
    exit 1
}

Write-Host "Starting the application..." -ForegroundColor Cyan
docker compose @Compose start app | Out-Null
if ($LASTEXITCODE -ne 0) { Fail "Loaded, but the app container did not restart." }

# Report what actually landed rather than claiming success. A dump that restored without error but
# produced an empty database is a real outcome, and you want to see it here.
Write-Host "`nLoaded:" -ForegroundColor Green
$summary = @"
SELECT 'users        ' || count(*) FROM app_user
UNION ALL SELECT 'items        ' || count(*) FROM item
UNION ALL SELECT 'stores       ' || count(*) FROM location
UNION ALL SELECT 'orders       ' || count(*) FROM purchase_order
UNION ALL SELECT 'migrations   ' || count(*) FROM flyway_schema_history;
"@
$summary | docker compose @Compose exec -T db psql -U postgres -d mlmsitty -tA

Write-Host "`nThe application is restarting - give it 30-60 seconds, then open http://localhost:8081"
Write-Host "Follow it with:  docker compose -f docker-compose.local.yml logs -f app`n"
