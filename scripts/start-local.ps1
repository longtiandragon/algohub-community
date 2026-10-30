param([ValidateRange(1024,65535)][int]$FrontendPort = 5174)
$ErrorActionPreference = 'Stop'
$projectRoot = Split-Path $PSScriptRoot -Parent
Set-Location -LiteralPath $projectRoot
. (Join-Path $PSScriptRoot 'local-process.ps1')

function Test-Endpoint([string]$Url) {
    try { return (Invoke-WebRequest -Uri $Url -TimeoutSec 3 -UseBasicParsing).StatusCode -eq 200 }
    catch { return $false }
}
function Wait-Endpoint([string]$Url, [System.Diagnostics.Process]$ServiceProcess = $null) {
    for ($attempt = 0; $attempt -lt 30; $attempt++) {
        if (Test-Endpoint $Url) { return }
        if ($null -ne $ServiceProcess -and $ServiceProcess.HasExited) {
            throw "Service exited during startup (PID $($ServiceProcess.Id), exit $($ServiceProcess.ExitCode)): $Url. Check backend/target/local-start or local-frontend logs; previous logs are preserved in backend/target/startup-logs."
        }
        Start-Sleep -Seconds 1
    }
    throw "Service did not become ready: $Url. Check backend/target/local-start logs."
}

$dockerPath = (Get-Command docker -ErrorAction Stop).Source
$commandLogs = Join-Path $projectRoot 'backend/target/startup-logs'
Invoke-LocalProcess -FilePath $dockerPath -Arguments @('info','--format','{{.ServerVersion}}') -LogDirectory $commandLogs -TimeoutSeconds 30
Invoke-LocalProcess -FilePath $dockerPath -Arguments @('compose','up','-d','--wait','--wait-timeout','90','postgres','redis') -LogDirectory $commandLogs -TimeoutSeconds 120

$backendUrl = 'http://127.0.0.1:8080/api/auth/email/status'
if (-not (Test-Endpoint $backendUrl)) {
    if (Get-NetTCPConnection -LocalPort 8080 -State Listen -ErrorAction SilentlyContinue) {
        throw 'Port 8080 is occupied but the API is unhealthy. Inspect logs before restarting.'
    }
    $javaPath = (Get-Command java -ErrorAction Stop).Source
    if (-not (Test-Path 'backend/target/algohub-0.1.0.jar')) { throw 'Build the backend JAR first.' }
    Backup-LocalLog "$projectRoot/backend/target/local-start.log"
    Backup-LocalLog "$projectRoot/backend/target/local-start.err.log"
    $backendProcess = Start-Process -FilePath $javaPath -ArgumentList '-Dfile.encoding=UTF-8','-XX:ErrorFile=target/hs_err_pid%p.log','-Dspring.config.additional-location=optional:file:local-secret/mail.properties,optional:file:local-secret/github.properties','-jar','target/algohub-0.1.0.jar' -WorkingDirectory "$projectRoot/backend" -WindowStyle Hidden -PassThru -RedirectStandardOutput "$projectRoot/backend/target/local-start.log" -RedirectStandardError "$projectRoot/backend/target/local-start.err.log"
    Wait-Endpoint $backendUrl $backendProcess
}

if (-not (Test-Endpoint "http://127.0.0.1:$FrontendPort/")) {
    if (Get-NetTCPConnection -LocalPort $FrontendPort -State Listen -ErrorAction SilentlyContinue) { throw "Port $FrontendPort is occupied but unhealthy." }
    $nodePath = (Get-Command node -ErrorAction Stop).Source
    if (-not (Test-Path 'frontend/node_modules/vite/bin/vite.js')) { throw 'Run npm install in frontend first.' }
    Backup-LocalLog "$projectRoot/backend/target/local-frontend.log"
    Backup-LocalLog "$projectRoot/backend/target/local-frontend.err.log"
    $frontendProcess = Start-Process -FilePath $nodePath -ArgumentList 'node_modules/vite/bin/vite.js','--host','127.0.0.1','--port',$FrontendPort.ToString(),'--strictPort' -WorkingDirectory "$projectRoot/frontend" -WindowStyle Hidden -PassThru -RedirectStandardOutput "$projectRoot/backend/target/local-frontend.log" -RedirectStandardError "$projectRoot/backend/target/local-frontend.err.log"
    Wait-Endpoint "http://127.0.0.1:$FrontendPort/" $frontendProcess
}
Wait-Endpoint "http://127.0.0.1:$FrontendPort/api/auth/email/status"
Write-Output "Frontend, backend and API proxy ready: http://127.0.0.1:$FrontendPort/login"
