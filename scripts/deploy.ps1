# Deploy to the production server (algohub.top). Only commits that are already on GitHub's main are deployed,
# so the server, GitHub and local main always describe the same code.
#
#   scripts/deploy.ps1              build origin/main in a temporary checkout, run all backend tests and the
#                                   frontend build, then upload; the server backs up the database before switching
#   scripts/deploy.ps1 -Status      show which commit the server runs and how far it is behind origin/main
#   scripts/deploy.ps1 -Rollback    switch the server back to the previous release (the database is not rolled back)
#   scripts/deploy.ps1 -SkipTests   skip backend tests (only right after a full test run of the same commit)
#
# Server side: deploy/release.sh (backup, atomic switch, health check, schema-checked switch-back on failure).
# SSH: host alias "algohub" in ~/.ssh/config with the key ~/.ssh/algohub_deploy.
# The pre-deploy database backup is copied back to backup/server/ (git-ignored).
param(
    [string]$Ref = 'origin/main',
    [switch]$Status,
    [switch]$Rollback,
    [switch]$SkipTests
)
$ErrorActionPreference = 'Stop'
$projectRoot = Split-Path $PSScriptRoot -Parent
$server = 'algohub'
. (Join-Path $PSScriptRoot 'backup-copy.ps1')
# Tests use their own database and Redis db so they never clean the dev/demo data or collide with another test run
$testDatabase = 'algohub_release_test'
$testRedisDb = 10

function Invoke-Native {
    param([Parameter(Mandatory)][string]$File, [string[]]$Arguments = @(), [string]$WorkingDirectory = $projectRoot)
    Push-Location -LiteralPath $WorkingDirectory
    try {
        & $File @Arguments
        if ($LASTEXITCODE -ne 0) { throw "$File $($Arguments -join ' ') failed (exit $LASTEXITCODE)" }
    } finally {
        Pop-Location
    }
}

function Get-ServerStatus {
    $lines = & ssh -o BatchMode=yes $server 'if [ -x /opt/algohub/deploy/release.sh ]; then bash /opt/algohub/deploy/release.sh status; else echo current=manual; fi'
    if ($LASTEXITCODE -ne 0) { throw "Cannot reach the server over ssh ($server). Check ~/.ssh/config and the key." }
    $status = @{}
    foreach ($line in $lines) {
        $index = $line.IndexOf('=')
        if ($index -gt 0) { $status[$line.Substring(0, $index)] = $line.Substring($index + 1) }
    }
    return $status
}

function Show-Status {
    Invoke-Native git @('fetch', 'origin', '--quiet')
    $status = Get-ServerStatus
    $main = (& git -C $projectRoot rev-parse origin/main).Trim()
    $deployed = $status['current.commit']
    Write-Output "Server release : $($status['current'])"
    Write-Output "Server commit  : $(if ($deployed) { $deployed } else { 'unknown (deployed by hand)' })"
    Write-Output "origin/main    : $main"
    if ($deployed -and $deployed -ne 'unknown') {
        $behind = (& git -C $projectRoot rev-list --count "$deployed..$main").Trim()
        $ahead = (& git -C $projectRoot rev-list --count "$main..$deployed").Trim()
        if ($behind -eq '0' -and $ahead -eq '0') { Write-Output 'In sync       : server runs origin/main' }
        else { Write-Output "In sync       : NO - server is $behind commit(s) behind and $ahead ahead of origin/main" }
    }
    Write-Output "Previous       : $($status['previous'])"
    Write-Output "Service/health : $($status['service']) / $($status['health'])"
    Write-Output "Latest backup  : $($status['latest_backup'])"
}

function Copy-ServerBackup([string]$RemotePath) {
    $target = Join-Path $projectRoot 'backup/server'
    return (Copy-VerifiedServerBackup -Server $server -RemotePath $RemotePath -Destination $target).Path
}

if ($Status) {
    Show-Status
    return
}

# 本机发布共享测试库与 Redis 分区；在构建前就互斥，避免两次测试互相清库。
$releaseMutex = [System.Threading.Mutex]::new($false, 'Global\AlgoHub.Project16.Release')
$releaseLockHeld = $false
try {
    try { $releaseLockHeld = $releaseMutex.WaitOne(0) }
    catch [System.Threading.AbandonedMutexException] { $releaseLockHeld = $true }
    if (-not $releaseLockHeld) { throw 'Another local release or rollback is running. Wait for it to finish.' }

if ($Rollback) {
    & ssh -o BatchMode=yes $server 'bash /opt/algohub/deploy/release.sh rollback'
    if ($LASTEXITCODE -ne 0) { throw 'Rollback failed. Check the output above and journalctl -u algohub on the server.' }
    Show-Status
    return
}

Invoke-Native git @('fetch', 'origin', '--quiet')
$sha = (& git -C $projectRoot rev-parse --verify "$Ref^{commit}").Trim()
& git -C $projectRoot merge-base --is-ancestor $sha origin/main
if ($LASTEXITCODE -ne 0) { throw "$Ref ($sha) is not on origin/main. Merge and push it to GitHub first, then deploy." }
$subject = (& git -C $projectRoot log -1 --format=%s $sha).Trim()
$id = '{0}-{1}' -f (Get-Date).ToUniversalTime().ToString("yyyyMMdd'T'HHmmss'Z'"), $sha.Substring(0, 7)
$build = Join-Path ([IO.Path]::GetTempPath()) "algohub-release-$id"
Write-Output "Deploying $sha ($subject) as release $id"

Invoke-Native git @('worktree', 'add', '--detach', $build, $sha)
try {
    Write-Output '== Frontend: npm ci + build'
    Invoke-Native npm.cmd @('ci', '--no-audit', '--no-fund') "$build/frontend"
    Invoke-Native npm.cmd @('run', 'build') "$build/frontend"
    foreach ($check in @('test:browser-sync', 'test:announcement-editor', 'test:friend-actions', 'test:list-recovery', 'test:sync-view', 'test:difficulty')) {
        Invoke-Native npm.cmd @('run', $check) "$build/frontend"
    }

    if ($SkipTests) {
        Write-Output '== Backend: package (tests skipped)'
        Invoke-Native mvn.cmd @('-q', '-Prelease', '-DskipTests', 'package') "$build/backend"
    } else {
        Write-Output "== Backend: all tests + package (database $testDatabase, Redis db $testRedisDb)"
        $exists = & docker exec algohub-postgres psql -U algohub -d postgres -tAc "select 1 from pg_database where datname='$testDatabase'"
        if ("$exists".Trim() -ne '1') {
            Invoke-Native docker @('exec', 'algohub-postgres', 'psql', '-U', 'algohub', '-d', 'postgres', '-c', "CREATE DATABASE $testDatabase OWNER algohub;")
        }
        Invoke-Native mvn.cmd @('-q', '-Prelease', 'verify',
            "-Dspring.datasource.url=jdbc:postgresql://127.0.0.1:5433/$testDatabase",
            "-Dspring.data.redis.database=$testRedisDb") "$build/backend"
    }

    Write-Output '== Package'
    $stage = Join-Path $build 'release-stage'
    New-Item -ItemType Directory -Path $stage -Force | Out-Null
    Copy-Item -LiteralPath "$build/backend/target/algohub-release.jar" -Destination "$stage/algohub.jar"
    Copy-Item -LiteralPath "$build/frontend/dist" -Destination "$stage/frontend" -Recurse
    Copy-Item -LiteralPath "$build/deploy" -Destination "$stage/deploy" -Recurse
    $release = @(
        "id=$id",
        "commit=$sha",
        "subject=$subject",
        "built_at=$((Get-Date).ToString('o'))",
        "built_by=$env:USERNAME@$env:COMPUTERNAME",
        "tests=$(if ($SkipTests) { 'skipped' } else { 'passed' })"
    ) -join "`n"
    [IO.File]::WriteAllText("$stage/RELEASE", "$release`n", (New-Object Text.UTF8Encoding $false))
    $archive = Join-Path $build "$id.tar.gz"
    Invoke-Native "$env:SystemRoot\System32\tar.exe" @('-czf', $archive, '-C', $stage, '.')

    Write-Output '== Upload and switch'
    Invoke-Native ssh @('-o', 'BatchMode=yes', $server, 'install -d -m 700 /opt/algohub/incoming')
    Invoke-Native scp @('-q', $archive, "${server}:/opt/algohub/incoming/$id.tar.gz")
    Invoke-Native scp @('-q', "$build/deploy/release.sh", "${server}:/opt/algohub/incoming/release-$id.sh")
    # Print the server's progress live and keep it for the result line
    $output = & ssh -o BatchMode=yes $server "bash /opt/algohub/incoming/release-$id.sh activate /opt/algohub/incoming/$id.tar.gz" |
        ForEach-Object { Write-Host $_; $_ }
    $activateExit = $LASTEXITCODE
    & ssh -o BatchMode=yes $server "rm -f /opt/algohub/incoming/$id.tar.gz /opt/algohub/incoming/release-$id.sh" | Out-Null

    $result = $output | Where-Object { $_ -match '^RELEASE_(OK|FAILED) ' } | Select-Object -Last 1
    $backup = if ($result -match 'backup=(\S+)') { $Matches[1] } else { $null }
    if ($backup) {
        $local = Copy-ServerBackup $backup
        Write-Output "Pre-deploy backup copied to $local"
    }
    if ($activateExit -ne 0 -or $result -notmatch '^RELEASE_OK ') {
        if ($result -match 'rollback=BLOCKED_SCHEMA') {
            throw "Release $id failed its health check. Automatic rollback was blocked by changed or unknown database schema; the new release remains selected. Inspect the server and verify schema compatibility before recovery. See the output above."
        }
        throw "Release $id did not complete successfully. Check the server output and current status; do not assume rollback succeeded."
    }
    Write-Output "== Done: $id is live"
    Show-Status
} finally {
    # A failed cleanup must not hide the real error; the folder is only a temporary checkout
    Push-Location -LiteralPath $projectRoot
    try {
        & git worktree remove --force $build
        if ($LASTEXITCODE -ne 0) { Write-Warning "Could not remove $build; delete it and run 'git worktree prune' later." }
    } finally {
        Pop-Location
    }
}
} finally {
    if ($releaseLockHeld) { $releaseMutex.ReleaseMutex() }
    $releaseMutex.Dispose()
}
