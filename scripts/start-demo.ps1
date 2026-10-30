# Demo backend for the page redesign: port 8081, database algohub_demo, Redis db 14.
# Every start wipes algohub_demo and writes fresh sample data relative to today (DemoDataSeeder).
# Never touches the dev database (algohub) or the test database (algohub_test) and makes no requests to any OJ.
$ErrorActionPreference = 'Stop'
$projectRoot = Split-Path $PSScriptRoot -Parent
Set-Location -LiteralPath $projectRoot
. (Join-Path $PSScriptRoot 'local-process.ps1')

function Test-Endpoint([string]$Url) {
    try { return (Invoke-WebRequest -Uri $Url -TimeoutSec 3 -UseBasicParsing).StatusCode -eq 200 }
    catch { return $false }
}

$dockerPath = (Get-Command docker -ErrorAction Stop).Source
$commandLogs = Join-Path $projectRoot 'backend/target/startup-logs'
Invoke-LocalProcess -FilePath $dockerPath -Arguments @('compose','up','-d','--wait','--wait-timeout','90','postgres','redis') -LogDirectory $commandLogs -TimeoutSeconds 120
$exists = & $dockerPath exec algohub-postgres psql -U algohub -d postgres -tAc "select 1 from pg_database where datname='algohub_demo'"
if ("$exists".Trim() -ne '1') {
    Invoke-LocalProcess -FilePath $dockerPath -Arguments @('exec','algohub-postgres','psql','-U','algohub','-d','postgres','-c','CREATE DATABASE algohub_demo OWNER algohub;') -LogDirectory $commandLogs -TimeoutSeconds 30
}

$demoUrl = 'http://127.0.0.1:8081/api/auth/email/status'
if (Test-Endpoint $demoUrl) {
    Write-Output 'Demo backend is already running on http://127.0.0.1:8081. Stop it and run this script again to regenerate the data.'
    return
}
if (Get-NetTCPConnection -LocalPort 8081 -State Listen -ErrorAction SilentlyContinue) {
    throw 'Port 8081 is occupied but the demo API is unhealthy. Inspect backend/target/demo-start logs before restarting.'
}

$mavenPath = (Get-Command mvn -ErrorAction Stop).Source
Backup-LocalLog "$projectRoot/backend/target/demo-start.log"
Backup-LocalLog "$projectRoot/backend/target/demo-start.err.log"
# spring-boot:run compiles first, so the demo always uses the current seeder
$process = Start-Process -FilePath $mavenPath -ArgumentList '-q','spring-boot:run','-Dspring-boot.run.profiles=demo','-Dspring-boot.run.jvmArguments=-Dfile.encoding=UTF-8' -WorkingDirectory "$projectRoot/backend" -WindowStyle Hidden -PassThru -RedirectStandardOutput "$projectRoot/backend/target/demo-start.log" -RedirectStandardError "$projectRoot/backend/target/demo-start.err.log"
for ($attempt = 0; $attempt -lt 180; $attempt++) {
    if (Test-Endpoint $demoUrl) {
        Write-Output 'Demo backend ready: http://127.0.0.1:8081 (students: chenyiming / demo12345, admin: admin / admin12345)'
        return
    }
    if ($process.HasExited) {
        throw "Demo backend exited during startup (exit $($process.ExitCode)). Check backend/target/demo-start.log."
    }
    Start-Sleep -Seconds 1
}
throw 'Demo backend did not become ready in 180 seconds. Check backend/target/demo-start.log.'
