# 仅模拟发布命令和进程互斥；不访问 GitHub、服务器或 Docker。
$ErrorActionPreference = 'Stop'
$deployScript = Join-Path (Split-Path $PSScriptRoot -Parent) 'scripts/deploy.ps1'
function ssh { $global:LASTEXITCODE=0; @('current=fixture', 'service=mock', 'health=mock') }
function git { $global:LASTEXITCODE=0; 'mock-main' }
$heldByOtherProcess = Start-Job {
    $mutex=[System.Threading.Mutex]::new($false,'Global\AlgoHub.Project16.Release')
    $held=$mutex.WaitOne(0)
    if (-not $held) { throw 'Fixture could not acquire release lock' }
    try { 'READY'; Start-Sleep -Seconds 30 }
    finally { $mutex.ReleaseMutex(); $mutex.Dispose() }
}
try {
    $ready=$false
    for ($i=0; $i -lt 50; $i++) {
        if ((Receive-Job $heldByOtherProcess -Keep) -contains 'READY') { $ready=$true; break }
        Start-Sleep -Milliseconds 100
    }
    if (-not $ready) { throw 'Fixture process did not become ready' }
    $blocked=$false
    try { & $deployScript -Rollback | Out-Null }
    catch { $blocked=$_.Exception.Message -eq 'Another local release or rollback is running. Wait for it to finish.' }
    if (-not $blocked) { throw 'Concurrent local deployment was not blocked' }
    'PASS: another process blocks deployment before test/build/server commands'
} finally {
    Stop-Job $heldByOtherProcess
    Remove-Job $heldByOtherProcess -Force
}
# 回滚分支以 mock 正常完成两次，确认 return/finally 不会留下锁。
& $deployScript -Rollback | Out-Null
& $deployScript -Rollback | Out-Null
'PASS: normal return releases the mutex; repeated mock rollback succeeds'
