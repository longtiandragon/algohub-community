param([switch]$NoOpen)
$ErrorActionPreference = 'Stop'
$projectRoot = Split-Path $PSScriptRoot -Parent
$stateDir = Join-Path $projectRoot '.audit/qoj-browser'
New-Item -ItemType Directory -Force -Path $stateDir | Out-Null
$pidFile = Join-Path $stateDir 'tunnel.pid'
$listenPort = 16081
$url = "http://127.0.0.1:$listenPort/vnc.html?autoconnect=true&resize=scale"
$listener = Get-NetTCPConnection -LocalPort $listenPort -State Listen -ErrorAction SilentlyContinue
if ($listener) {
    $savedId = if (Test-Path -LiteralPath $pidFile) { (Get-Content -LiteralPath $pidFile -Raw).Trim() } else { '' }
    if (@($listener | Where-Object { "$($_.OwningProcess)" -ne $savedId }).Count) {
        throw "Port $listenPort is owned by another process. No process was stopped."
    }
    $processInfo = Get-CimInstance Win32_Process -Filter "ProcessId=$savedId"
    if ($processInfo.Name -ne 'ssh.exe' -or $processInfo.CommandLine -notlike '*127.0.0.1:16081:127.0.0.1:6081*') {
        throw 'Existing listener is not the recorded browser tunnel.'
    }
} else {
    $sshPath = (Get-Command ssh.exe).Source
    $tunnel = Start-Process -FilePath $sshPath -ArgumentList @(
        '-N','-T','-o','BatchMode=yes','-o','ExitOnForwardFailure=yes',
        '-o','ServerAliveInterval=30','-o','ServerAliveCountMax=3',
        '-L','127.0.0.1:16081:127.0.0.1:6081','algohub'
    ) -WindowStyle Hidden -PassThru -RedirectStandardError (Join-Path $stateDir 'tunnel.log')
    [IO.File]::WriteAllText($pidFile, [string]$tunnel.Id)
    $ready = $false
    for ($attempt=0; $attempt -lt 20; $attempt++) {
        if ($tunnel.HasExited) { throw 'SSH tunnel exited. Inspect .audit/qoj-browser/tunnel.log.' }
        try {
            $response = Invoke-WebRequest -Uri "http://127.0.0.1:$listenPort/vnc.html" -TimeoutSec 2
            if ($response.StatusCode -eq 200) { $ready=$true; break }
        } catch { Start-Sleep -Milliseconds 500 }
    }
    if (-not $ready) { throw 'Browser tunnel is not ready. Check the remote algohub-qoj-browser service.' }
}
Write-Output "QOJ server browser: $url"
if (-not $NoOpen) { Start-Process $url }
