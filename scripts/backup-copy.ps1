# Shared by deployment and the scheduled pull. Publish only a fully verified download.
function Copy-VerifiedServerBackup {
    param(
        [Parameter(Mandatory)][string]$Server,
        [Parameter(Mandatory)][string]$RemotePath,
        [Parameter(Mandatory)][string]$Destination,
        [string]$ExpectedHash
    )
    if ($RemotePath -notmatch '^/var/backups/algohub/(algohub-[0-9A-Za-z._-]+\.dump)$') {
        throw 'Unexpected server backup path.'
    }
    $name = $Matches[1]
    New-Item -ItemType Directory -Path $Destination -Force | Out-Null
    $local = Join-Path (Resolve-Path -LiteralPath $Destination).Path $name
    $temporary = "$local.partial-$([Guid]::NewGuid().ToString('N'))"
    $manifest = "$temporary.sha256"
    $mutex = [Threading.Mutex]::new($false, 'Global\AlgoHub.Project16.BackupCopy')
    $held = $false
    try {
        try { $held = $mutex.WaitOne(0) }
        catch [Threading.AbandonedMutexException] { $held = $true }
        if (-not $held) { throw 'Another backup copy is running. Retry after it finishes.' }
        if (-not $ExpectedHash) {
            & scp -q "${Server}:$RemotePath.sha256" $manifest | Out-Null
            if ($LASTEXITCODE -ne 0) { throw "Cannot download checksum for $name." }
            $ExpectedHash = ((Get-Content -LiteralPath $manifest -Raw).Trim() -split '\s+')[0]
        }
        if ($ExpectedHash -notmatch '^[0-9a-fA-F]{64}$') { throw "Invalid checksum for $name." }
        $ExpectedHash = $ExpectedHash.ToLowerInvariant()
        $copied = $false
        if (-not (Test-Path -LiteralPath $local) -or
                (Get-FileHash -LiteralPath $local -Algorithm SHA256).Hash -ine $ExpectedHash) {
            & scp -q "${Server}:$RemotePath" $temporary | Out-Null
            if ($LASTEXITCODE -ne 0) { throw "Backup download failed for $name; existing copy was preserved." }
            if ((Get-FileHash -LiteralPath $temporary -Algorithm SHA256).Hash -ine $ExpectedHash) {
                throw "Checksum mismatch for $name; existing copy was preserved."
            }
            Move-Item -LiteralPath $temporary -Destination $local -Force
            $copied = $true
        }
        [IO.File]::WriteAllText($manifest, "$ExpectedHash  $name`n")
        Move-Item -LiteralPath $manifest -Destination "$local.sha256" -Force
        return [pscustomobject]@{ Path = $local; Copied = $copied }
    } finally {
        foreach ($partial in @($temporary, $manifest)) {
            if (Test-Path -LiteralPath $partial) { Remove-Item -LiteralPath $partial -Force -ErrorAction SilentlyContinue }
        }
        if ($held) { $mutex.ReleaseMutex() }
        $mutex.Dispose()
    }
}
